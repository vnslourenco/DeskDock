package com.deskdock.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import com.deskdock.app.data.CalendarRepository
import com.deskdock.app.data.LocationRepository
import com.deskdock.app.data.WeatherRepository
import com.deskdock.app.model.BatteryInfo
import com.deskdock.app.model.WeatherSnapshot
import com.deskdock.app.ui.DockView
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var dockView: DockView
    private lateinit var calendarRepo: CalendarRepository
    private lateinit var locationRepo: LocationRepository
    private val weatherRepo = WeatherRepository()
    private val handler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() { dockView.setNow(System.currentTimeMillis()); handler.postDelayed(this, 1000) }
    }
    private val shift = object : Runnable {
        override fun run() { dockView.shiftForBurnInProtection(); handler.postDelayed(this, 90_000) }
    }
    private val refresh = object : Runnable {
        override fun run() { refreshAll(); handler.postDelayed(this, 30 * 60_000L) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersive()
        calendarRepo = CalendarRepository(this)
        locationRepo = LocationRepository(this)
        dockView = DockView(this).apply { onRefreshRequested = { refreshAll() } }
        setContentView(dockView)
        requestNeededPermissions()
        refreshAll()
        handler.post(tick)
        handler.post(shift)
        handler.post(refresh)
    }

    override fun onResume() { super.onResume(); enterImmersive(); refreshCalendar() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }

    private fun requestNeededPermissions() {
        val p = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) p += Manifest.permission.ACCESS_FINE_LOCATION
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) p += Manifest.permission.ACCESS_COARSE_LOCATION
        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) p += Manifest.permission.READ_CALENDAR
        if (p.isNotEmpty()) requestPermissions(p.toTypedArray(), 42)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 42) refreshAll()
    }

    private fun refreshAll() {
        refreshBattery()
        refreshCalendar()
        refreshWeather()
    }

    private fun refreshBattery() {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        dockView.setBattery(BatteryInfo((level * 100f / scale).toInt(), plugged))
    }

    private fun refreshCalendar() {
        val allowed = checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        dockView.setCalendarPermission(allowed)
        if (!allowed) {
            dockView.setCalendarStatus("Permita acesso ao calendário")
            return dockView.setEvents(emptyList())
        }

        dockView.setCalendarStatus("Atualizando agenda…")
        Thread {
            val state = runCatching { calendarRepo.loadState(5) }.getOrNull()
            runOnUiThread {
                if (state == null) {
                    dockView.setEvents(emptyList())
                    dockView.setCalendarStatus("Não foi possível ler a agenda")
                } else {
                    dockView.setEvents(state.events)
                    val source = state.calendarNames.take(2).joinToString(" · ")
                    dockView.setCalendarStatus(
                        when {
                            state.visibleCalendars == 0 -> "Nenhum calendário visível no Android"
                            state.events.isEmpty() && source.isNotBlank() -> "Sem eventos · $source"
                            state.events.isEmpty() -> "Sem compromissos nos próximos 7 dias"
                            else -> "${state.visibleCalendars} calendário(s) · $source"
                        }
                    )
                }
            }
        }.start()
    }

    private fun refreshWeather() {
        dockView.setWeatherLoading(true)
        locationRepo.getCurrent { c ->
            if (c == null) {
                dockView.setLocationLabel("São Paulo · fallback")
                weatherRepo.fetch(-23.5505, -46.6333) { applyWeatherResult(it) }
                return@getCurrent
            }

            val precision = if (c.accuracyMeters > 0) " · ±${c.accuracyMeters.toInt()} m" else ""
            dockView.setLocationLabel("Local atual$precision")
            resolveLocationLabel(c.latitude, c.longitude, precision)
            weatherRepo.fetch(c.latitude, c.longitude) { applyWeatherResult(it) }
        }
    }

    private fun applyWeatherResult(result: Result<WeatherSnapshot>) {
        dockView.setWeatherLoading(false)
        result.onSuccess { dockView.setWeather(it) }
        result.onFailure { dockView.setWeatherError() }
    }

    private fun resolveLocationLabel(latitude: Double, longitude: Double, precision: String) {
        Thread {
            val label = runCatching {
                @Suppress("DEPRECATION")
                val address = Geocoder(this, Locale("pt", "BR")).getFromLocation(latitude, longitude, 1)?.firstOrNull()
                address?.subLocality ?: address?.locality ?: address?.subAdminArea ?: "Local atual"
            }.getOrDefault("Local atual")
            runOnUiThread { dockView.setLocationLabel("$label$precision") }
        }.start()
    }

    private fun enterImmersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }
}
