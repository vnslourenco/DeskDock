package com.deskdock.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import com.deskdock.app.model.BatteryInfo
import com.deskdock.app.model.CalendarEvent
import com.deskdock.app.model.WeatherSnapshot
import com.deskdock.app.util.WeatherCode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

class DockView(context: Context) : View(context) {
    var onRefreshRequested: (() -> Unit)? = null
    private val density = resources.displayMetrics.density
    private val regular = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans", Typeface.NORMAL) }
    private val medium = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans", Typeface.BOLD) }
    private var now = System.currentTimeMillis()
    private var weather: WeatherSnapshot? = null
    private var battery = BatteryInfo(0, false)
    private var events: List<CalendarEvent> = emptyList()
    private var locationLabel = "Local atual"
    private var weatherLoading = false
    private var weatherError = false
    private var calendarPermission = true
    private var shiftX = 0f
    private var shiftY = 0f

    fun setNow(value: Long) { now = value; invalidate() }
    fun setWeather(value: WeatherSnapshot) { weather = value; weatherError = false; invalidate() }
    fun setWeatherLoading(value: Boolean) { weatherLoading = value; invalidate() }
    fun setWeatherError() { weatherError = true; invalidate() }
    fun setBattery(value: BatteryInfo) { battery = value; invalidate() }
    fun setEvents(value: List<CalendarEvent>) { events = value; invalidate() }
    fun setLocationLabel(value: String) { locationLabel = value; invalidate() }
    fun setCalendarPermission(value: Boolean) { calendarPermission = value; invalidate() }

    fun shiftForBurnInProtection() {
        shiftX = Random.nextInt(-8, 9) * density
        shiftY = Random.nextInt(-5, 6) * density
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        canvas.save()
        canvas.translate(shiftX, shiftY)
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 34f * density
        val leftWidth = w * .37f

        drawText(canvas, SimpleDateFormat("HH:mm", Locale("pt","BR")).format(Date(now)), pad, 92f*density, 72f*density, Color.WHITE, true)
        drawText(canvas, SimpleDateFormat("EEEE, d 'de' MMMM", Locale("pt","BR")).format(Date(now)).replaceFirstChar { it.uppercase() }, pad, 124f*density, 18f*density, GREY)

        val wx = pad
        val wy = 190f*density
        drawText(canvas, locationLabel.uppercase(), wx, wy, 12f*density, DIM, true)
        if (weatherLoading && weather == null) {
            drawText(canvas, "Carregando clima…", wx, wy + 42f*density, 20f*density, GREY)
        } else if (weatherError && weather == null) {
            drawText(canvas, "Clima indisponível", wx, wy + 42f*density, 20f*density, GREY)
        } else weather?.let { data ->
            drawText(canvas, "${data.temperatureC}°", wx, wy + 84f*density, 64f*density, Color.WHITE, true)
            drawText(canvas, WeatherCode.labelPtBr(data.weatherCode), wx, wy + 116f*density, 19f*density, GREY)
            drawText(canvas, "Sensação ${data.feelsLikeC}°  ·  Máx ${data.todayMaxC}°  ·  Mín ${data.todayMinC}°", wx, wy + 146f*density, 15f*density, GREY)
            drawText(canvas, "ECMWF IFS · Open-Meteo", wx, wy + 174f*density, 10f*density, DIM)
        }

        val batteryText = if (battery.charging) "⚡ ${battery.percent}% · carregando" else "Bateria ${battery.percent}%"
        drawText(canvas, batteryText, pad, h - 30f*density, 15f*density, if (battery.charging) CHARGE else GREY, true)

        val rx = leftWidth + 28f*density
        drawText(canvas, "PRÓXIMAS HORAS", rx, 56f*density, 12f*density, DIM, true)
        weather?.hourly?.take(6)?.forEachIndexed { index, item ->
            val cell = (w-rx-pad)/6f
            val cx = rx + cell*index
            drawText(canvas, item.hour, cx, 91f*density, 14f*density, GREY, true)
            drawText(canvas, "${item.temperatureC}°", cx, 126f*density, 24f*density, Color.WHITE, true)
            drawText(canvas, "${item.rainChance}%", cx, 151f*density, 12f*density, BLUE)
        }

        line(canvas, rx, 177f*density, w-pad, 177f*density)
        drawText(canvas, "PRÓXIMOS 3 DIAS", rx, 208f*density, 12f*density, DIM, true)
        weather?.nextDays?.take(3)?.forEachIndexed { index, day ->
            val y = (245 + index*54)*density
            drawText(canvas, day.dayLabel, rx, y, 17f*density, Color.WHITE, true)
            drawText(canvas, WeatherCode.labelPtBr(day.weatherCode), rx+92f*density, y, 14f*density, GREY)
            drawText(canvas, "${day.maxC}° / ${day.minC}°", w-170f*density, y, 16f*density, Color.WHITE, true)
            drawText(canvas, "${day.rainChance}% chuva", w-85f*density, y, 12f*density, BLUE)
        }

        line(canvas, rx, 420f*density, w-pad, 420f*density)
        drawText(canvas, "AGENDA", rx, 450f*density, 12f*density, DIM, true)
        if (!calendarPermission) {
            drawText(canvas, "Permita acesso ao calendário", rx, 486f*density, 15f*density, GREY)
        } else if (events.isEmpty()) {
            drawText(canvas, "Nenhum compromisso nas próximas 36 h", rx, 486f*density, 15f*density, GREY)
        } else events.take(3).forEachIndexed { index, event ->
            val y = (486 + index*40)*density
            val time = if (event.allDay) "Dia todo" else SimpleDateFormat("HH:mm", Locale("pt","BR")).format(Date(event.startMillis))
            drawText(canvas, time, rx, y, 15f*density, BLUE, true)
            drawText(canvas, ellipsize(event.title, 42), rx+70f*density, y, 16f*density, Color.WHITE, true)
        }

        drawText(canvas, "↻", w-58f*density, 52f*density, 24f*density, DIM, true)
        canvas.restore()
    }

    private fun drawText(canvas: Canvas, text: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean=false) {
        val p = if (bold) medium else regular
        p.textSize = size; p.color = color
        canvas.drawText(text, x, y, p)
    }

    private fun line(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
        regular.color = DIVIDER; regular.strokeWidth = density
        canvas.drawLine(x1,y1,x2,y2,regular)
    }

    private fun ellipsize(value: String, max: Int): String = if (value.length <= max) value else value.take(max-1) + "…"

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP && event.x > width - 120*density && event.y < 100*density) {
            onRefreshRequested?.invoke(); performClick()
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }

    companion object {
        private val GREY = Color.rgb(170,170,178)
        private val DIM = Color.rgb(105,105,115)
        private val DIVIDER = Color.rgb(38,38,42)
        private val BLUE = Color.rgb(120,180,255)
        private val CHARGE = Color.rgb(120,220,150)
    }
}
