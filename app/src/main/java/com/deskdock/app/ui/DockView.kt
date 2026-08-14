package com.deskdock.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
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

    private val regular = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
    private val medium = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private var now = System.currentTimeMillis()
    private var weather: WeatherSnapshot? = null
    private var battery = BatteryInfo(0, false)
    private var events: List<CalendarEvent> = emptyList()
    private var location = "Local atual"
    private var calStatus = "Atualizando agenda…"
    private var calPermission = true
    private var weatherLoading = false
    private var weatherError = false
    private var shiftX = 0f
    private var shiftY = 0f

    fun setNow(v: Long) { now = v; invalidate() }
    fun setWeather(v: WeatherSnapshot) { weather = v; weatherError = false; invalidate() }
    fun setWeatherLoading(v: Boolean) { weatherLoading = v; invalidate() }
    fun setWeatherError() { weatherError = true; invalidate() }
    fun setBattery(v: BatteryInfo) { battery = v; invalidate() }
    fun setEvents(v: List<CalendarEvent>) { events = v; invalidate() }
    fun setLocationLabel(v: String) { location = v; invalidate() }
    fun setCalendarPermission(v: Boolean) { calPermission = v; invalidate() }
    fun setCalendarStatus(v: String) { calStatus = v; invalidate() }

    fun shiftForBurnInProtection() {
        shiftX = Random.nextInt(-4, 5).toFloat()
        shiftY = Random.nextInt(-3, 4).toFloat()
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        c.drawColor(Color.BLACK)
        c.save()
        c.translate(shiftX, shiftY)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val pad = w * 0.028f
        val gap = w * 0.012f
        val leftRight = w * 0.355f
        val rightLeft = leftRight + gap
        val right = w - pad
        val corner = h * 0.028f

        val clockSize = h * 0.125f
        val dateSize = h * 0.035f
        val labelSize = h * 0.022f
        val bodySize = h * 0.027f
        val smallSize = h * 0.020f

        text(c, time(), pad, h * 0.17f, clockSize, WHITE, true)
        text(c, date(), pad, h * 0.23f, dateSize, MUTED)
        battery(c, right - w * 0.105f, h * 0.075f, smallSize)
        refresh(c, right - w * 0.012f, h * 0.075f, h * 0.045f)

        val weatherCard = RectF(pad, h * 0.305f, leftRight, h - h * 0.04f)
        val hourlyCard = RectF(rightLeft, h * 0.115f, right, h * 0.42f)
        val dailyCard = RectF(rightLeft, h * 0.455f, right, h * 0.705f)
        val agendaCard = RectF(rightLeft, h * 0.74f, right, h - h * 0.04f)

        card(c, weatherCard, corner)
        card(c, hourlyCard, corner)
        card(c, dailyCard, corner)
        card(c, agendaCard, corner)

        weather(c, weatherCard, h)
        hourly(c, hourlyCard, h)
        daily(c, dailyCard, h)
        agenda(c, agendaCard, h)

        c.restore()
    }

    private fun weather(c: Canvas, r: RectF, h: Float) {
        val x = r.left + r.width() * 0.09f
        val top = r.top
        val label = h * 0.021f
        val temp = h * 0.092f
        val title = h * 0.032f
        val body = h * 0.025f
        val tiny = h * 0.017f

        text(c, shortLocation(), x, top + r.height() * 0.11f, label, DIM, true)

        if (weatherLoading && weather == null) {
            text(c, "Carregando clima…", x, top + r.height() * 0.36f, title, MUTED, true)
            return
        }
        if (weatherError && weather == null) {
            text(c, "Clima indisponível", x, top + r.height() * 0.36f, title, MUTED, true)
            return
        }

        weather?.let { v ->
            icon(c, v.weatherCode, x + r.width() * 0.11f, top + r.height() * 0.39f, h * 0.043f)
            text(c, "${v.temperatureC}°", x + r.width() * 0.28f, top + r.height() * 0.43f, temp, WHITE, true)
            text(c, WeatherCode.labelPtBr(v.weatherCode), x, top + r.height() * 0.62f, title, WHITE, true)
            text(c, "Sensação ${v.feelsLikeC}°", x, top + r.height() * 0.72f, body, MUTED)
            line(c, x, top + r.height() * 0.81f, r.right - r.width() * 0.08f, top + r.height() * 0.81f, h)
            text(c, "Máx ${v.todayMaxC}°", x, top + r.height() * 0.91f, body, WHITE, true)
            text(c, "Mín ${v.todayMinC}°", x + r.width() * 0.38f, top + r.height() * 0.91f, body, WHITE, true)
            textRight(c, "ECMWF IFS · Open-Meteo", r.right - r.width() * 0.07f, r.bottom - r.height() * 0.04f, tiny, DIM)
        }
    }

    private fun hourly(c: Canvas, r: RectF, h: Float) {
        val x = r.left + r.width() * 0.04f
        text(c, "PRÓXIMAS HORAS", x, r.top + r.height() * 0.14f, h * 0.021f, DIM, true)
        val items = weather?.hourly?.take(6).orEmpty()
        val contentTop = r.top + r.height() * 0.26f
        val cw = (r.width() - r.width() * 0.08f) / 6f

        items.forEachIndexed { i, v ->
            val cx = x + cw * i + cw / 2f
            textCenter(c, v.hour, cx, contentTop, h * 0.022f, MUTED, true)
            icon(c, v.weatherCode, cx, contentTop + r.height() * 0.21f, h * 0.023f)
            textCenter(c, "${v.temperatureC}°", cx, contentTop + r.height() * 0.47f, h * 0.036f, WHITE, true)
            textCenter(c, "•  ${v.rainChance}%", cx, contentTop + r.height() * 0.68f, h * 0.021f, BLUE, true)
        }
    }

    private fun daily(c: Canvas, r: RectF, h: Float) {
        val x = r.left + r.width() * 0.04f
        text(c, "PRÓXIMOS 3 DIAS", x, r.top + r.height() * 0.17f, h * 0.021f, DIM, true)
        val days = weather?.nextDays?.take(3).orEmpty()

        days.forEachIndexed { i, v ->
            val rowTop = r.top + r.height() * (0.34f + i * 0.245f)
            if (i > 0) line(c, x, rowTop - r.height() * 0.11f, r.right - r.width() * 0.04f, rowTop - r.height() * 0.11f, h)
            text(c, v.dayLabel, x, rowTop, h * 0.030f, WHITE, true)
            icon(c, v.weatherCode, x + r.width() * 0.18f, rowTop - h * 0.008f, h * 0.018f)
            text(c, WeatherCode.labelPtBr(v.weatherCode), x + r.width() * 0.22f, rowTop, h * 0.022f, MUTED)
            text(c, "•  ${v.rainChance}%", r.right - r.width() * 0.26f, rowTop, h * 0.021f, BLUE, true)
            textRight(c, "${v.maxC}°  ${v.minC}°", r.right - r.width() * 0.04f, rowTop, h * 0.030f, WHITE, true)
        }
    }

    private fun agenda(c: Canvas, r: RectF, h: Float) {
        val x = r.left + r.width() * 0.04f
        val titleY = r.top + r.height() * 0.23f
        text(c, "AGENDA", x, titleY, h * 0.021f, DIM, true)
        textRight(c, "7 dias", r.right - r.width() * 0.04f, titleY, h * 0.019f, DIM, true)

        if (events.isEmpty()) {
            val msg = if (!calPermission) "Permita acesso ao calendário" else calStatus
            text(c, msg, x, r.top + r.height() * 0.62f, h * 0.024f, MUTED, true)
            return
        }

        val visible = events.take(3)
        val startY = r.top + r.height() * 0.48f
        val rowGap = r.height() * 0.22f
        visible.forEachIndexed { i, e ->
            val y = startY + i * rowGap
            text(c, eventTime(e), x, y, h * 0.021f, BLUE, true)
            text(c, ell(e.title, 44), x + r.width() * 0.17f, y, h * 0.024f, if (i == 0) WHITE else MUTED, true)
        }
    }

    private fun card(c: Canvas, r: RectF, radius: Float) {
        fill.color = CARD
        c.drawRoundRect(r, radius, radius, fill)
        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = 1.5f
        stroke.color = BORDER
        c.drawRoundRect(r, radius, radius, stroke)
    }

    private fun icon(c: Canvas, code: Int, x: Float, y: Float, radius: Float) {
        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = (radius * 0.10f).coerceAtLeast(2f)
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.color = ICON

        if (code == 0) {
            c.drawCircle(x, y, radius * 0.48f, stroke)
            for (i in 0 until 8) {
                val a = Math.toRadians((i * 45).toDouble())
                val x1 = x + kotlin.math.cos(a).toFloat() * radius * 0.70f
                val y1 = y + kotlin.math.sin(a).toFloat() * radius * 0.70f
                val x2 = x + kotlin.math.cos(a).toFloat() * radius * 0.95f
                val y2 = y + kotlin.math.sin(a).toFloat() * radius * 0.95f
                c.drawLine(x1, y1, x2, y2, stroke)
            }
            return
        }

        if (code in 1..3) {
            c.drawCircle(x - radius * 0.45f, y - radius * 0.35f, radius * 0.34f, stroke)
        }

        val q = Path()
        q.moveTo(x - radius * 0.78f, y + radius * 0.22f)
        q.cubicTo(x - radius * 0.82f, y - radius * 0.20f, x - radius * 0.38f, y - radius * 0.32f, x - radius * 0.20f, y - radius * 0.18f)
        q.cubicTo(x, y - radius * 0.68f, x + radius * 0.56f, y - radius * 0.50f, x + radius * 0.56f, y - radius * 0.10f)
        q.cubicTo(x + radius * 0.90f, y - radius * 0.08f, x + radius * 0.92f, y + radius * 0.36f, x + radius * 0.56f, y + radius * 0.36f)
        q.lineTo(x - radius * 0.56f, y + radius * 0.36f)
        c.drawPath(q, stroke)

        if (code in 51..82) {
            for (i in -1..1) {
                c.drawLine(
                    x + i * radius * 0.30f,
                    y + radius * 0.55f,
                    x + i * radius * 0.30f - radius * 0.10f,
                    y + radius * 0.90f,
                    stroke
                )
            }
        }
    }

    private fun battery(c: Canvas, x: Float, y: Float, size: Float) {
        text(c, "▭  ${battery.percent}%", x, y, size, if (battery.charging) GREEN else MUTED, true)
    }

    private fun refresh(c: Canvas, x: Float, y: Float, size: Float) {
        text(c, "↻", x, y, size, DIM, true)
    }

    private fun line(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, h: Float) {
        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = (h * 0.0015f).coerceAtLeast(1f)
        stroke.color = BORDER
        c.drawLine(x1, y1, x2, y2, stroke)
    }

    private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false) {
        val q = if (bold) medium else regular
        q.textSize = size
        q.color = color
        c.drawText(s, x, y, q)
    }

    private fun textCenter(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false) {
        val q = if (bold) medium else regular
        q.textSize = size
        q.color = color
        c.drawText(s, x - q.measureText(s) / 2f, y, q)
    }

    private fun textRight(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false) {
        val q = if (bold) medium else regular
        q.textSize = size
        q.color = color
        c.drawText(s, x - q.measureText(s), y, q)
    }

    private fun time() = SimpleDateFormat("HH:mm", Locale("pt", "BR")).format(Date(now))
    private fun date() = SimpleDateFormat("EEEE, d 'de' MMMM", Locale("pt", "BR")).format(Date(now)).replaceFirstChar { it.uppercase() }
    private fun eventTime(e: CalendarEvent) = if (e.allDay) "Dia todo" else SimpleDateFormat("EEE HH:mm", Locale("pt", "BR")).format(Date(e.startMillis)).replaceFirstChar { it.uppercase() }
    private fun shortLocation(): String {
        val base = location.substringBefore(" · ±")
        return if (base.length > 30) base.take(29) + "…" else base.uppercase(Locale("pt", "BR"))
    }
    private fun ell(s: String, n: Int) = if (s.length <= n) s else s.take(n - 1) + "…"

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP && e.x > width * 0.90f && e.y < height * 0.14f) {
            onRefreshRequested?.invoke()
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    companion object {
        val WHITE = Color.rgb(244, 244, 248)
        val MUTED = Color.rgb(165, 165, 175)
        val DIM = Color.rgb(100, 100, 112)
        val CARD = Color.rgb(12, 12, 15)
        val BORDER = Color.rgb(38, 38, 44)
        val BLUE = Color.rgb(112, 169, 255)
        val GREEN = Color.rgb(110, 220, 150)
        val ICON = Color.rgb(225, 225, 232)
    }
}
