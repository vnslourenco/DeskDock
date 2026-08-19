package com.deskdock.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.location.Geocoder
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.deskdock.app.data.CalendarRepository
import com.deskdock.app.data.LocationRepository
import com.deskdock.app.data.WeatherRepository
import com.deskdock.app.model.BatteryInfo
import com.deskdock.app.model.WeatherSnapshot
import com.deskdock.app.ui.DockView
import java.util.Locale

@OptIn(UnstableApi::class)
class MainActivity : Activity() {
    private lateinit var dockView: DockView
    private lateinit var root: FrameLayout
    private lateinit var cameraFrame: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var cameraStatus: TextView
    private lateinit var calendarRepo: CalendarRepository
    private lateinit var locationRepo: LocationRepository
    private val weatherRepo = WeatherRepository()
    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var cameraUsingTcp = false
    private val prefs by lazy { getSharedPreferences("deskdock", MODE_PRIVATE) }

    private val tick = object : Runnable {
        override fun run() { dockView.setNow(System.currentTimeMillis()); handler.postDelayed(this, 1000) }
    }
    private val shift = object : Runnable {
        override fun run() { dockView.shiftForBurnInProtection(); handler.postDelayed(this, 90_000) }
    }
    private val refresh = object : Runnable {
        override fun run() { refreshAll(); handler.postDelayed(this, 30 * 60_000L) }
    }
    private var dailyForecastVisible = false
    private val forecastSwitch = object : Runnable {
        override fun run() {
            dailyForecastVisible = !dailyForecastVisible
            dockView.setForecastModeDaily(dailyForecastVisible)
            handler.postDelayed(this, if (dailyForecastVisible) 30_000L else 60_000L)
        }
    }
    private val cameraRetry = Runnable { startCamera(false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersive()
        calendarRepo = CalendarRepository(this)
        locationRepo = LocationRepository(this)

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        dockView = DockView(this).apply { onRefreshRequested = { refreshAll() } }
        root.addView(dockView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        createCameraOverlay()
        setContentView(root)
        root.post { positionCameraOverlay() }

        requestNeededPermissions()
        refreshAll()
        handler.post(tick)
        handler.post(shift)
        handler.post(refresh)
        handler.postDelayed(forecastSwitch, 60_000L)
        startCamera(false)
    }

    override fun onResume() {
        super.onResume()
        enterImmersive()
        refreshCalendar()
        if (player == null) startCamera(false)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun createCameraOverlay() {
        cameraFrame = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = 18f
            }
            setOnLongClickListener {
                showCameraConfigDialog()
                true
            }
        }
        playerView = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            setShutterBackgroundColor(Color.BLACK)
        }
        cameraStatus = TextView(this).apply {
            setTextColor(Color.rgb(166,166,178))
            textSize = 15f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.TRANSPARENT)
            text = "Segure aqui para configurar a câmera"
        }
        cameraFrame.addView(playerView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        cameraFrame.addView(cameraStatus, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(cameraFrame)
    }

    private fun positionCameraOverlay() {
        val w = root.width.toFloat()
        val h = root.height.toFloat()
        if (w <= 0 || h <= 0) return
        val cardLeft = w * .012f
        val cardTop = h * .325f
        val cardWidth = w * .315f
        val cardBottom = h * .965f
        val cardHeight = cardBottom - cardTop
        val left = cardLeft + cardWidth * .035f
        val top = cardTop + cardHeight * .15f
        val right = cardLeft + cardWidth - cardWidth * .035f
        val bottom = cardBottom - cardHeight * .045f
        cameraFrame.layoutParams = FrameLayout.LayoutParams((right-left).toInt(), (bottom-top).toInt()).apply {
            leftMargin = left.toInt()
            topMargin = top.toInt()
        }
    }

    private fun showCameraConfigDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setText(prefs.getString("camera_rtsp_url", "rtsp://admin:@192.168.0.138:554/cam/realmonitor?channel=1&subtype=1"))
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Câmera RTSP")
            .setMessage("Cole a URL RTSP completa. Ela fica salva somente neste aparelho.")
            .setView(input)
            .setPositiveButton("Salvar") { _, _ ->
                val url = input.text.toString().trim()
                prefs.edit().putString("camera_rtsp_url", url).apply()
                startCamera(false)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun startCamera(forceTcp: Boolean) {
        handler.removeCallbacks(cameraRetry)
        cameraUsingTcp = forceTcp
        val url = prefs.getString("camera_rtsp_url", null)?.trim().orEmpty()
        if (url.isBlank()) {
            cameraStatus.visibility = View.VISIBLE
            cameraStatus.text = "Segure aqui para configurar a câmera"
            return
        }
        cameraStatus.visibility = View.VISIBLE
        cameraStatus.text = if (forceTcp) "Conectando câmera · TCP…" else "Conectando câmera…"

        val exo = player ?: ExoPlayer.Builder(this).build().also { p ->
            player = p
            playerView.player = p
            p.volume = 0f
            p.repeatMode = Player.REPEAT_MODE_ONE
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) cameraStatus.visibility = View.GONE
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (!cameraUsingTcp) {
                        cameraStatus.visibility = View.VISIBLE
                        cameraStatus.text = "Tentando modo TCP…"
                        handler.postDelayed({ startCamera(true) }, 700L)
                    } else {
                        val detail = error.cause?.message?.lineSequence()?.firstOrNull()?.take(55)
                            ?: error.errorCodeName
                        cameraStatus.visibility = View.VISIBLE
                        cameraStatus.text = "Câmera indisponível\n$detail\nTentando novamente…"
                        handler.postDelayed(cameraRetry, 10_000L)
                    }
                }
            })
        }

        runCatching {
            val item = MediaItem.fromUri(url)
            val factory = RtspMediaSource.Factory().setTimeoutMs(10_000)
            if (forceTcp) factory.setForceUseRtpTcp(true)
            val source = factory.createMediaSource(item)
            exo.stop()
            exo.clearMediaItems()
            exo.setMediaSource(source)
            exo.prepare()
            exo.playWhenReady = true
        }.onFailure {
            cameraStatus.visibility = View.VISIBLE
            cameraStatus.text = "URL RTSP inválida\n${it.message.orEmpty().take(55)}\nSegure para configurar"
        }
    }

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
                            state.events.isEmpty() && source.isNotBlank() -> "Sem mais compromissos hoje · $source"
                            state.events.isEmpty() -> "Sem mais compromissos hoje"
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
