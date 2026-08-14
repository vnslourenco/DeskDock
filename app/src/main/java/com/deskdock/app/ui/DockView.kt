package com.deskdock.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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

    private val d = resources.displayMetrics.density
    private val regular = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
    private val medium = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * d }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private var now = System.currentTimeMillis()
    private var weather: WeatherSnapshot? = null
    private var battery = BatteryInfo(0, false)
    private var events: List<CalendarEvent> = emptyList()
    private var locationLabel = "Local atual"
    private var weatherLoading = false
    private var weatherError = false
    private var calendarPermission = true
    private var calendarStatus = "Atualizando agenda…"
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
    fun setCalendarStatus(value: String) { calendarStatus = value; invalidate() }

    fun shiftForBurnInProtection() {
        shiftX = Random.nextInt(-6, 7) * d
        shiftY = Random.nextInt(-4, 5) * d
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        canvas.save()
        canvas.translate(shiftX, shiftY)

        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 24f * d
        val gap = 14f * d
        val leftW = w * .34f
        val rightX = leftW + gap
        val rightW = w - rightX - pad

        drawTopBar(canvas, w, pad)

        val clockY = 90f * d
        drawText(canvas, timeText(), pad, clockY, 68f * d, WHITE, true)
        drawText(canvas, dateText(), pad + 3f*d, clockY + 29f*d, 16f*d, MUTED)

        val weatherCard = RectF(pad, 154f*d, leftW - gap, h - 22f*d)
        drawCard(canvas, weatherCard)
        drawWeatherCard(canvas, weatherCard)

        val hourlyCard = RectF(rightX, 52f*d, w-pad, 184f*d)
        val dailyCard = RectF(rightX, 198f*d, w-pad, 334f*d)
        val agendaCard = RectF(rightX, 348f*d, w-pad, h-22f*d)

        drawCard(canvas, hourlyCard)
        drawHourly(canvas, hourlyCard)

        drawCard(canvas, dailyCard)
        drawDaily(canvas, dailyCard)

        drawCard(canvas, agendaCard)
        drawAgenda(canvas, agendaCard)

        canvas.restore()
    }

    private fun drawTopBar(canvas: Canvas, w: Float, pad: Float) {
        val batteryText = if (battery.charging) "${battery.percent}%  carregando" else "${battery.percent}%"
        drawBatteryIcon(canvas, w - pad - 86f*d, 25f*d, battery.percent, battery.charging)
        drawText(canvas, batteryText, w-pad-58f*d, 31f*d, 12f*d, if (battery.charging) GREEN else MUTED, true)

        drawRefreshIcon(canvas, w-pad-10f*d, 28f*d)
    }

    private fun drawWeatherCard(canvas: Canvas, r: RectF) {
        val x = r.left + 20f*d
        val top = r.top + 24f*d
        drawText(canvas, locationLabel.uppercase(Locale("pt", "BR")), x, top, 11f*d, DIM, true)

        if (weatherLoading && weather == null) {
            drawText(canvas, "Atualizando clima…", x, top + 48f*d, 17f*d, MUTED)
            return
        }
        if (weatherError && weather == null) {
            drawText(canvas, "Clima indisponível", x, top + 48f*d, 17f*d, MUTED)
            return
        }

        weather?.let { data ->
            drawWeatherIcon(canvas, data.weatherCode, x + 26f*d, top + 72f*d, 26f*d)
            drawText(canvas, "${data.temperatureC}°", x + 70f*d, top + 92f*d, 54f*d, WHITE, true)
            drawText(canvas, WeatherCode.labelPtBr(data.weatherCode), x, top + 130f*d, 18f*d, WHITE, true)
            drawText(canvas, "Sensação ${data.feelsLikeC}°", x, top + 157f*d, 14f*d, MUTED)

            line(canvas, x, top + 182f*d, r.right - 20f*d, top + 182f*d)

            drawText(canvas, "MÁX", x, top + 211f*d, 10f*d, DIM, true)
            drawText(canvas, "${data.todayMaxC}°", x, top + 238f*d, 22f*d, WHITE, true)
            drawText(canvas, "MÍN", x + 78f*d, top + 211f*d, 10f*d, DIM, true)
            drawText(canvas, "${data.todayMinC}°", x + 78f*d, top + 238f*d, 22f*d, WHITE, true)

            drawText(canvas, "ECMWF IFS  •  Open-Meteo", x, r.bottom - 18f*d, 9.5f*d, DIM)
        }
    }

    private fun drawHourly(canvas: Canvas, r: RectF) {
        val x = r.left + 18f*d
        drawSectionTitle(canvas, "PRÓXIMAS HORAS", x, r.top + 24f*d)
        val data = weather?.hourly?.take(6).orEmpty()
        if (data.isEmpty()) return

        val cellW = (r.width() - 36f*d) / 6f
        data.forEachIndexed { i, item ->
            val cx = x + cellW*i + cellW/2
            drawTextCentered(canvas, item.hour, cx, r.top + 51f*d, 11f*d, MUTED, true)
            drawWeatherIcon(canvas, item.weatherCode, cx, r.top + 75f*d, 12f*d)
            drawTextCentered(canvas, "${item.temperatureC}°", cx, r.top + 104f*d, 18f*d, WHITE, true)
            drawRainDrop(canvas, cx - 11f*d, r.top + 121f*d)
            drawText(canvas, "${item.rainChance}%", cx - 3f*d, r.top + 125f*d, 9.5f*d, BLUE, true)
        }
    }

    private fun drawDaily(canvas: Canvas, r: RectF) {
        val x = r.left + 18f*d
        drawSectionTitle(canvas, "PRÓXIMOS 3 DIAS", x, r.top + 24f*d)
        val data = weather?.nextDays?.take(3).orEmpty()
        if (data.isEmpty()) return

        val rowH = 33f*d
        data.forEachIndexed { i, day ->
            val y = r.top + 52f*d + i*rowH
            if (i > 0) line(canvas, x, y - 20f*d, r.right - 18f*d, y - 20f*d)
            drawText(canvas, day.dayLabel, x, y, 14f*d, WHITE, true)
            drawWeatherIcon(canvas, day.weatherCode, x + 82f*d, y - 5f*d, 10f*d)
            drawText(canvas, WeatherCode.labelPtBr(day.weatherCode), x + 101f*d, y, 11f*d, MUTED)
            drawRainDrop(canvas, r.right - 142f*d, y - 4f*d)
            drawText(canvas, "${day.rainChance}%", r.right - 130f*d, y, 10f*d, BLUE, true)
            drawTextRight(canvas, "${day.maxC}°  ${day.minC}°", r.right - 18f*d, y, 14f*d, WHITE, true)
        }
    }

    private fun drawAgenda(canvas: Canvas, r: RectF) {
        val x = r.left + 18f*d
        drawSectionTitle(canvas, "AGENDA", x, r.top + 24f*d)
        drawTextRight(canvas, "7 dias", r.right-18f*d, r.top+24f*d, 10f*d, DIM, true)

        if (!calendarPermission || events.isEmpty()) {
            drawCalendarIcon(canvas, x + 12f*d, r.top + 58f*d)
            drawText(canvas, calendarStatus, x + 36f*d, r.top + 62f*d, 12f*d, MUTED, true)
            if (calendarPermission && calendarStatus.contains("Nenhum calendário")) {
                drawText(canvas, "Ative ‘Sincronizar calendários’ no Outlook", x + 36f*d, r.top + 83f*d, 10.5f*d, DIM)
            }
            return
        }

        val next = events.first()
        val pill = RectF(x, r.top + 40f*d, r.right - 18f*d, r.top + 87f*d)
        fill.color = CARD_HIGHLIGHT
        canvas.drawRoundRect(pill, 13f*d, 13f*d, fill)
        drawText(canvas, eventTime(next), pill.left + 14f*d, pill.top + 20f*d, 11f*d, BLUE, true)
        drawText(canvas, ellipsize(next.title, 42), pill.left + 14f*d, pill.top + 40f*d, 14f*d, WHITE, true)

        events.drop(1).take(2).forEachIndexed { i, event ->
            val y = r.top + (112 + i*31)*d
            drawText(canvas, eventTime(event), x, y, 10.5f*d, BLUE, true)
            drawText(canvas, ellipsize(event.title, 38), x + 58f*d, y, 12f*d, MUTED, true)
        }
    }

    private fun drawCard(canvas: Canvas, r: RectF) {
        fill.color = CARD
        canvas.drawRoundRect(r, 20f*d, 20f*d, fill)
        stroke.color = BORDER
        canvas.drawRoundRect(r, 20f*d, 20f*d, stroke)
    }

    private fun drawSectionTitle(canvas: Canvas, text: String, x: Float, y: Float) {
        drawText(canvas, text, x, y, 10.5f*d, DIM, true)
    }

    private fun drawWeatherIcon(canvas: Canvas, code: Int, cx: Float, cy: Float, radius: Float) {
        stroke.strokeWidth = 1.8f*d
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.color = ICON
        fill.color = ICON

        when (code) {
            0 -> drawSun(canvas, cx, cy, radius)
            1, 2 -> { drawSun(canvas, cx-radius*.35f, cy-radius*.25f, radius*.62f); drawCloud(canvas, cx+radius*.25f, cy+radius*.2f, radius*.78f) }
            3, 45, 48 -> drawCloud(canvas, cx, cy, radius)
            51,53,55,56,57,61,63,65,66,67,80,81,82 -> { drawCloud(canvas, cx, cy-radius*.15f, radius*.85f); drawRain(canvas, cx, cy+radius*.55f, radius) }
            71,73,75,77,85,86 -> { drawCloud(canvas, cx, cy-radius*.15f, radius*.85f); drawSnow(canvas, cx, cy+radius*.55f, radius) }
            95,96,99 -> { drawCloud(canvas, cx, cy-radius*.2f, radius*.85f); drawLightning(canvas, cx, cy+radius*.35f, radius) }
            else -> drawCloud(canvas, cx, cy, radius)
        }
    }

    private fun drawSun(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        canvas.drawCircle(cx, cy, r*.45f, stroke)
        for (i in 0 until 8) {
            val a = Math.toRadians((i*45).toDouble())
            val x1 = cx + kotlin.math.cos(a).toFloat()*r*.68f
            val y1 = cy + kotlin.math.sin(a).toFloat()*r*.68f
            val x2 = cx + kotlin.math.cos(a).toFloat()*r
            val y2 = cy + kotlin.math.sin(a).toFloat()*r
            canvas.drawLine(x1,y1,x2,y2,stroke)
        }
    }

    private fun drawCloud(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val p = android.graphics.Path()
        p.moveTo(cx-r*.8f, cy+r*.3f)
        p.cubicTo(cx-r*.9f,cy-r*.05f,cx-r*.55f,cy-r*.3f,cx-r*.27f,cy-r*.2f)
        p.cubicTo(cx-r*.12f,cy-r*.72f,cx+r*.55f,cy-r*.65f,cx+r*.56f,cy-r*.15f)
        p.cubicTo(cx+r*.95f,cy-r*.14f,cx+r*.98f,cy+r*.35f,cx+r*.62f,cy+r*.4f)
        p.lineTo(cx-r*.55f,cy+r*.4f)
        p.cubicTo(cx-r*.7f,cy+r*.4f,cx-r*.8f,cy+r*.35f,cx-r*.8f,cy+r*.3f)
        canvas.drawPath(p, stroke)
    }

    private fun drawRain(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        for (i in -1..1) {
            val x = cx + i*r*.38f
            canvas.drawLine(x, cy, x-r*.12f, cy+r*.42f, stroke)
        }
    }

    private fun drawSnow(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        for (i in -1..1) canvas.drawCircle(cx+i*r*.38f, cy+r*.16f, 1.7f*d, fill)
    }

    private fun drawLightning(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val p = android.graphics.Path()
        p.moveTo(cx+r*.15f,cy-r*.25f); p.lineTo(cx-r*.1f,cy+r*.12f); p.lineTo(cx+r*.08f,cy+r*.1f); p.lineTo(cx-r*.15f,cy+r*.55f)
        canvas.drawPath(p, stroke)
    }

    private fun drawRainDrop(canvas: Canvas, x: Float, y: Float) {
        fill.color = BLUE
        canvas.drawCircle(x, y, 2.3f*d, fill)
    }

    private fun drawBatteryIcon(canvas: Canvas, x: Float, y: Float, percent: Int, charging: Boolean) {
        stroke.color = if (charging) GREEN else MUTED
        stroke.strokeWidth = 1.2f*d
        val r = RectF(x, y-7f*d, x+18f*d, y+4f*d)
        canvas.drawRoundRect(r, 2.5f*d, 2.5f*d, stroke)
        canvas.drawLine(x+19f*d,y-3f*d,x+20.5f*d,y-3f*d,stroke)
        fill.color = if (charging) GREEN else MUTED
        val level = (percent.coerceIn(0,100)/100f)*14f*d
        canvas.drawRoundRect(RectF(x+2f*d,y-5f*d,x+2f*d+level,y+2f*d),1.3f*d,1.3f*d,fill)
    }

    private fun drawRefreshIcon(canvas: Canvas, cx: Float, cy: Float) {
        stroke.color = DIM; stroke.strokeWidth = 1.4f*d
        val oval = RectF(cx-8f*d,cy-8f*d,cx+8f*d,cy+8f*d)
        canvas.drawArc(oval,-55f,280f,false,stroke)
        canvas.drawLine(cx+7f*d,cy-6f*d,cx+9f*d,cy-10f*d,stroke)
    }

    private fun drawCalendarIcon(canvas: Canvas, cx: Float, cy: Float) {
        stroke.color = DIM; stroke.strokeWidth = 1.4f*d
        val r = RectF(cx-10f*d,cy-9f*d,cx+10f*d,cy+9f*d)
        canvas.drawRoundRect(r,3f*d,3f*d,stroke)
        canvas.drawLine(cx-10f*d,cy-3f*d,cx+10f*d,cy-3f*d,stroke)
        canvas.drawLine(cx-5f*d,cy-12f*d,cx-5f*d,cy-6f*d,stroke)
        canvas.drawLine(cx+5f*d,cy-12f*d,cx+5f*d,cy-6f*d,stroke)
    }

    private fun eventTime(event: CalendarEvent): String {
        if (event.allDay) return "DIA TODO"
        val eventDate = SimpleDateFormat("dd/MM", Locale("pt","BR")).format(Date(event.startMillis))
        val today = SimpleDateFormat("dd/MM", Locale("pt","BR")).format(Date(now))
        val time = SimpleDateFormat("HH:mm", Locale("pt","BR")).format(Date(event.startMillis))
        return if (eventDate == today) time else "$eventDate  $time"
    }

    private fun timeText() = SimpleDateFormat("HH:mm", Locale("pt","BR")).format(Date(now))
    private fun dateText() = SimpleDateFormat("EEEE, d 'de' MMMM", Locale("pt","BR")).format(Date(now)).replaceFirstChar { it.uppercase(Locale("pt","BR")) }

    private fun drawText(canvas: Canvas, text: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean=false) {
        val p = if (bold) medium else regular
        p.textSize = size; p.color = color
        canvas.drawText(text, x, y, p)
    }

    private fun drawTextCentered(canvas: Canvas, text: String, cx: Float, y: Float, size: Float, color: Int, bold: Boolean=false) {
        val p = if (bold) medium else regular
        p.textSize = size; p.color = color
        canvas.drawText(text, cx - p.measureText(text)/2f, y, p)
    }

    private fun drawTextRight(canvas: Canvas, text: String, right: Float, y: Float, size: Float, color: Int, bold: Boolean=false) {
        val p = if (bold) medium else regular
        p.textSize = size; p.color = color
        canvas.drawText(text, right-p.measureText(text), y, p)
    }

    private fun line(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
        stroke.color = BORDER; stroke.strokeWidth = .8f*d
        canvas.drawLine(x1,y1,x2,y2,stroke)
    }

    private fun ellipsize(value: String, max: Int): String = if (value.length <= max) value else value.take(max-1) + "…"

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP && event.x > width - 100*d && event.y < 80*d) {
            onRefreshRequested?.invoke()
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    companion object {
        private val WHITE = Color.rgb(242,242,247)
        private val MUTED = Color.rgb(168,168,178)
        private val DIM = Color.rgb(100,100,110)
        private val CARD = Color.rgb(10,10,12)
        private val CARD_HIGHLIGHT = Color.rgb(18,25,36)
        private val BORDER = Color.rgb(35,35,40)
        private val BLUE = Color.rgb(125,178,255)
        private val GREEN = Color.rgb(122,214,151)
        private val ICON = Color.rgb(220,220,226)
    }
}
