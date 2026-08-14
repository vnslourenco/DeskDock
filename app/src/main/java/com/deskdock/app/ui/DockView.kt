package com.deskdock.app.ui

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import com.deskdock.app.model.*
import com.deskdock.app.util.WeatherCode
import java.text.SimpleDateFormat
import java.util.*
import kotlin.random.Random

class DockView(context: Context) : View(context) {
    var onRefreshRequested: (() -> Unit)? = null
    private val d=resources.displayMetrics.density
    private val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{typeface=Typeface.create("sans-serif",0)}
    private val m=Paint(Paint.ANTI_ALIAS_FLAG).apply{typeface=Typeface.create("sans-serif-medium",0)}
    private val fill=Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeWidth=d}
    private var now=System.currentTimeMillis(); private var weather:WeatherSnapshot?=null; private var battery=BatteryInfo(0,false)
    private var events:List<CalendarEvent> = emptyList(); private var location="Local atual"; private var calStatus="Atualizando agenda…"; private var calPermission=true
    private var sx=0f; private var sy=0f
    fun setNow(v:Long){now=v;invalidate()}; fun setWeather(v:WeatherSnapshot){weather=v;invalidate()}; fun setWeatherLoading(v:Boolean){}; fun setWeatherError(){}
    fun setBattery(v:BatteryInfo){battery=v;invalidate()}; fun setEvents(v:List<CalendarEvent>){events=v;invalidate()}; fun setLocationLabel(v:String){location=v;invalidate()}
    fun setCalendarPermission(v:Boolean){calPermission=v;invalidate()}; fun setCalendarStatus(v:String){calStatus=v;invalidate()}
    fun shiftForBurnInProtection(){sx=Random.nextInt(-4,5)*d;sy=Random.nextInt(-3,4)*d;invalidate()}

    override fun onDraw(c:Canvas){
        c.drawColor(Color.BLACK); c.save(); c.translate(sx,sy)
        val w=width.toFloat(); val h=height.toFloat(); val pad=24f*d; val gap=14f*d
        val left= w*.34f; val rx=left+gap; val right=w-pad
        text(c,time(),pad,72f*d,62f*d,WHITE,true); text(c,date(),pad,100f*d,15f*d,MUTED)
        battery(c,right-90f*d,28f*d); refresh(c,right-8f*d,28f*d)
        val wc=RectF(pad,130f*d,left-gap,h-24f*d); card(c,wc); weather(c,wc)
        val usable=h-44f*d; val hourlyH=130f*d; val dailyH=132f*d; val agendaTop=44f*d+hourlyH+gap+dailyH+gap
        val hc=RectF(rx,44f*d,right,44f*d+hourlyH); val dc=RectF(rx,hc.bottom+gap,right,hc.bottom+gap+dailyH); val ac=RectF(rx,agendaTop,right,h-24f*d)
        card(c,hc); hourly(c,hc); card(c,dc); daily(c,dc); card(c,ac); agenda(c,ac)
        c.restore()
    }
    private fun weather(c:Canvas,r:RectF){val x=r.left+18f*d; text(c,shortLocation(),x,r.top+25f*d,10f*d,DIM,true); weather?.let{v->
        icon(c,v.weatherCode,x+24f*d,r.top+76f*d,22f*d); text(c,"${v.temperatureC}°",x+62f*d,r.top+91f*d,50f*d,WHITE,true)
        text(c,WeatherCode.labelPtBr(v.weatherCode),x,r.top+132f*d,16f*d,WHITE,true); text(c,"Sensação ${v.feelsLikeC}°",x,r.top+158f*d,13f*d,MUTED)
        line(c,x,r.top+178f*d,r.right-18f*d,r.top+178f*d); text(c,"${v.todayMaxC}°  máx",x,r.top+207f*d,14f*d,WHITE,true); text(c,"${v.todayMinC}°  mín",x+90f*d,r.top+207f*d,14f*d,WHITE,true)
        text(c,"ECMWF IFS  •  Open-Meteo",x,r.bottom-16f*d,9f*d,DIM)}}
    private fun hourly(c:Canvas,r:RectF){val x=r.left+18f*d;text(c,"PRÓXIMAS HORAS",x,r.top+23f*d,10f*d,DIM,true);val a=weather?.hourly?.take(6).orEmpty();val cw=(r.width()-36f*d)/6
        a.forEachIndexed{i,v->val cx=x+cw*i+cw/2;textCenter(c,v.hour,cx,r.top+48f*d,10f*d,MUTED,true);icon(c,v.weatherCode,cx,r.top+70f*d,10f*d);textCenter(c,"${v.temperatureC}°",cx,r.top+98f*d,17f*d,WHITE,true);textCenter(c,"${v.rainChance}%",cx,r.top+118f*d,9f*d,BLUE,true)}}
    private fun daily(c:Canvas,r:RectF){val x=r.left+18f*d;text(c,"PRÓXIMOS 3 DIAS",x,r.top+22f*d,10f*d,DIM,true);weather?.nextDays?.take(3)?.forEachIndexed{i,v->val y=r.top+(49+i*27)*d;text(c,v.dayLabel,x,y,13f*d,WHITE,true);icon(c,v.weatherCode,x+76f*d,y-4f*d,8f*d);text(c,WeatherCode.labelPtBr(v.weatherCode),x+94f*d,y,10f*d,MUTED);text(c,"${v.rainChance}%",r.right-130f*d,y,9f*d,BLUE,true);textRight(c,"${v.maxC}°  ${v.minC}°",r.right-16f*d,y,13f*d,WHITE,true)}}
    private fun agenda(c:Canvas,r:RectF){val x=r.left+18f*d;text(c,"AGENDA",x,r.top+22f*d,10f*d,DIM,true);textRight(c,"7 dias",r.right-18f*d,r.top+22f*d,9f*d,DIM,true)
        if(events.isEmpty()){text(c,if(!calPermission)"Permita acesso ao calendário" else calStatus,x,r.top+51f*d,11f*d,MUTED,true);return}
        events.take(3).forEachIndexed{i,e->val y=r.top+(50+i*29)*d;text(c,eventTime(e),x,y,10f*d,BLUE,true);text(c,ell(e.title,48),x+64f*d,y,11f*d,if(i==0)WHITE else MUTED,true)}}
    private fun card(c:Canvas,r:RectF){fill.color=CARD;c.drawRoundRect(r,18f*d,18f*d,fill);stroke.color=BORDER;c.drawRoundRect(r,18f*d,18f*d,stroke)}
    private fun icon(c:Canvas,code:Int,x:Float,y:Float,r:Float){stroke.color=ICON;stroke.strokeWidth=1.7f*d;stroke.strokeCap=Paint.Cap.ROUND;if(code==0){c.drawCircle(x,y,r*.45f,stroke);return};val q=Path();q.moveTo(x-r*.75f,y+r*.25f);q.cubicTo(x-r*.8f,y-r*.2f,x-r*.35f,y-r*.3f,x-r*.2f,y-r*.2f);q.cubicTo(x,y-r*.7f,x+r*.55f,y-r*.5f,x+r*.55f,y-r*.1f);q.cubicTo(x+r*.9f,y-r*.1f,x+r*.9f,y+r*.35f,x+r*.55f,y+r*.35f);q.lineTo(x-r*.55f,y+r*.35f);c.drawPath(q,stroke);if(code in 51..82){for(i in -1..1)c.drawLine(x+i*r*.3f,y+r*.55f,x+i*r*.3f-r*.1f,y+r*.9f,stroke)}}
    private fun battery(c:Canvas,x:Float,y:Float){text(c,"▭  ${battery.percent}%",x,y,12f*d,if(battery.charging)GREEN else MUTED,true)}
    private fun refresh(c:Canvas,x:Float,y:Float){text(c,"↻",x,y,22f*d,DIM,true)}
    private fun line(c:Canvas,a:Float,b:Float,x:Float,y:Float){stroke.color=BORDER;c.drawLine(a,b,x,y,stroke)}
    private fun text(c:Canvas,s:String,x:Float,y:Float,z:Float,col:Int,b:Boolean=false){val q=if(b)m else p;q.textSize=z;q.color=col;c.drawText(s,x,y,q)}
    private fun textCenter(c:Canvas,s:String,x:Float,y:Float,z:Float,col:Int,b:Boolean=false){val q=if(b)m else p;q.textSize=z;q.color=col;c.drawText(s,x-q.measureText(s)/2,y,q)}
    private fun textRight(c:Canvas,s:String,x:Float,y:Float,z:Float,col:Int,b:Boolean=false){val q=if(b)m else p;q.textSize=z;q.color=col;c.drawText(s,x-q.measureText(s),y,q)}
    private fun time()=SimpleDateFormat("HH:mm",Locale("pt","BR")).format(Date(now));private fun date()=SimpleDateFormat("EEEE, d 'de' MMMM",Locale("pt","BR")).format(Date(now)).replaceFirstChar{it.uppercase()}
    private fun eventTime(e:CalendarEvent)=if(e.allDay)"Dia todo" else SimpleDateFormat("EEE HH:mm",Locale("pt","BR")).format(Date(e.startMillis)).replaceFirstChar{it.uppercase()}
    private fun shortLocation():String { val base=location.substringBefore(" · ±"); return if(base.length>28) base.take(27)+"…" else base.uppercase(Locale("pt","BR")) }
    private fun ell(s:String,n:Int)=if(s.length<=n)s else s.take(n-1)+"…"
    override fun onTouchEvent(e:MotionEvent):Boolean{if(e.action==MotionEvent.ACTION_UP&&e.x>width-120*d&&e.y<90*d){onRefreshRequested?.invoke();performClick()};return true};override fun performClick():Boolean{super.performClick();return true}
    companion object{val WHITE=Color.rgb(244,244,248);val MUTED=Color.rgb(165,165,175);val DIM=Color.rgb(100,100,112);val CARD=Color.rgb(12,12,15);val BORDER=Color.rgb(38,38,44);val BLUE=Color.rgb(112,169,255);val GREEN=Color.rgb(110,220,150);val ICON=Color.rgb(225,225,232)}
}
