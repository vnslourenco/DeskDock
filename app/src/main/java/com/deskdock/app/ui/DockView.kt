package com.deskdock.app.ui

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import com.deskdock.app.model.*
import com.deskdock.app.util.WeatherCode
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class DockView(context: Context) : View(context) {
    var onRefreshRequested:(()->Unit)?=null
    private val reg=Paint(1).apply{typeface=Typeface.create("sans-serif",Typeface.NORMAL)}
    private val med=Paint(1).apply{typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)}
    private val fill=Paint(1)
    private val stroke=Paint(1).apply{style=Paint.Style.STROKE}

    private var now=System.currentTimeMillis()
    private var weather:WeatherSnapshot?=null
    private var battery=BatteryInfo(0,false)
    private var events:List<CalendarEvent> = emptyList()
    private var location="Local atual"
    private var calStatus="Atualizando agenda…"
    private var calPermission=true
    private var loading=false
    private var error=false
    private var sx=0f
    private var sy=0f

    fun setNow(v:Long){now=v;invalidate()}
    fun setWeather(v:WeatherSnapshot){weather=v;error=false;invalidate()}
    fun setWeatherLoading(v:Boolean){loading=v;invalidate()}
    fun setWeatherError(){error=true;invalidate()}
    fun setBattery(v:BatteryInfo){battery=v;invalidate()}
    fun setEvents(v:List<CalendarEvent>){events=v;invalidate()}
    fun setLocationLabel(v:String){location=v;invalidate()}
    fun setCalendarPermission(v:Boolean){calPermission=v;invalidate()}
    fun setCalendarStatus(v:String){calStatus=v;invalidate()}

    fun shiftForBurnInProtection(){sx=Random.nextInt(-7,8).toFloat();sy=Random.nextInt(-5,6).toFloat();invalidate()}

    override fun onDraw(c:Canvas){
        super.onDraw(c)
        c.drawColor(Color.BLACK)
        c.save(); c.translate(sx,sy)
        val w=width.toFloat(); val h=height.toFloat(); if(w<=0||h<=0){c.restore();return}

        val pad=w*.012f
        val gap=w*.010f
        val leftW=w*.285f
        val lx=pad
        val rx=lx+leftW+gap
        val right=w-pad
        val top=h*.028f
        val bottom=h*.965f

        val leftTop=RectF(lx,top,lx+leftW,h*.305f)
        val leftBottom=RectF(lx,h*.325f,lx+leftW,bottom)
        val rightTop=RectF(rx,top,right,h*.455f)
        val rightBottom=RectF(rx,h*.475f,right,bottom)

        surface(c,leftTop,h); surface(c,leftBottom,h); surface(c,rightTop,h); surface(c,rightBottom,h)
        topLeft(c,leftTop,h)
        daily(c,leftBottom,h)
        hourly(c,rightTop,h)
        agenda(c,rightBottom,h)
        c.restore()
    }

    private fun topLeft(c:Canvas,r:RectF,h:Float){
        val split=r.left+r.width()*.62f
        line(c,split,r.top+h*.018f,split,r.bottom-h*.018f,h)
        text(c,time(),r.left+r.width()*.035f,r.top+r.height()*.47f,h*.120f,WHITE,true)
        text(c,date(),r.left+r.width()*.04f,r.top+r.height()*.78f,h*.034f,MUTED,true)
        val bx=r.left+r.width()*.04f; val by=r.bottom-h*.025f
        battery(c,bx,by,h*.020f)

        weather?.let{v->
            val cx=split+(r.right-split)*.50f
            textCenter(c,"ATUAL",cx,r.top+r.height()*.18f,h*.024f,DIM,true)
            icon(c,v.weatherCode,cx,r.top+r.height()*.39f,h*.035f)
            textCenter(c,"${v.temperatureC}°",cx,r.top+r.height()*.69f,h*.068f,WHITE,true)
            textCenter(c,"Sensação ${v.feelsLikeC}°",cx,r.top+r.height()*.88f,h*.028f,MUTED,true)
        }?:run{
            val msg=if(error)"--" else "…"
            textCenter(c,msg,split+(r.right-split)*.5f,r.top+r.height()*.62f,h*.055f,MUTED,true)
        }
    }

    private fun hourly(c:Canvas,r:RectF,h:Float){
        val x=r.left+r.width()*.035f
        header(c,"PRÓXIMAS HORAS",x,r.top+r.height()*.12f,h*.030f)
        textRight(c,"↻",r.right-r.width()*.025f,r.top+r.height()*.12f,h*.034f,DIM,true)
        val a=weather?.hourly?.take(6).orEmpty(); if(a.isEmpty()) return
        val usable=r.width()*.93f; val cw=usable/6f; val start=x
        a.forEachIndexed{i,v->
            val cx=start+cw*i+cw/2f
            textCenter(c,v.hour,cx,r.top+r.height()*.28f,h*.038f,WHITE,true)
            icon(c,v.weatherCode,cx,r.top+r.height()*.46f,h*.034f)
            textCenter(c,"${v.temperatureC}°",cx,r.top+r.height()*.70f,h*.046f,WHITE,true)
            textCenter(c,"${v.rainChance}%",cx,r.top+r.height()*.89f,h*.038f,BLUE,true)
        }
    }

    private fun daily(c:Canvas,r:RectF,h:Float){
        val x=r.left+r.width()*.055f
        header(c,"PRÓXIMOS 3 DIAS",x,r.top+r.height()*.095f,h*.029f)
        weather?.nextDays?.take(3)?.forEachIndexed{i,v->
            val colW=r.width()*.89f/3f; val cx=x+colW*i+colW/2f
            if(i>0) line(c,x+colW*i,r.top+r.height()*.17f,x+colW*i,r.bottom-r.height()*.08f,h)
            textCenter(c,v.dayLabel.uppercase(Locale("pt","BR")),cx,r.top+r.height()*.25f,h*.045f,WHITE,true)
            icon(c,v.weatherCode,cx,r.top+r.height()*.43f,h*.039f)
            textCenter(c,"${v.maxC}°",cx,r.top+r.height()*.64f,h*.054f,WHITE,true)
            textCenter(c,"${v.minC}°",cx,r.top+r.height()*.77f,h*.043f,MUTED,true)
            textCenter(c,"${v.rainChance}%",cx,r.top+r.height()*.91f,h*.041f,BLUE,true)
        }
    }

    private fun agenda(c:Canvas,r:RectF,h:Float){
        val x=r.left+r.width()*.035f
        header(c,"AGENDA DE HOJE",x,r.top+r.height()*.10f,h*.030f)
        if(events.isEmpty()){
            text(c,if(!calPermission)"Permita acesso ao calendário" else calStatus,x,r.top+r.height()*.48f,h*.034f,MUTED,true)
            return
        }
        val visible=events.take(4)
        val top=r.top+r.height()*.18f
        val rowH=r.height()*.18f
        visible.forEachIndexed{i,e->
            val rowTop=top+i*rowH
            if(i>0) line(c,x,rowTop,r.right-r.width()*.035f,rowTop,h)
            if(i==0){fill.color=ACCENT;c.drawRoundRect(RectF(x-r.width()*.008f,rowTop+h*.010f,r.right-r.width()*.03f,rowTop+rowH-h*.008f),h*.014f,h*.014f,fill)}
            text(c,eventTime(e),x,rowTop+rowH*.63f,h*.048f,if(i==0)BLUE else WHITE,true)
            text(c,ell(e.title,38),x+r.width()*.22f,rowTop+rowH*.63f,h*.046f,if(i==0)WHITE else MUTED,true)
        }
    }

    private fun surface(c:Canvas,r:RectF,h:Float){fill.color=CARD;c.drawRoundRect(r,h*.020f,h*.020f,fill);stroke.strokeWidth=(h*.0012f).coerceAtLeast(1f);stroke.color=BORDER;c.drawRoundRect(r,h*.020f,h*.020f,stroke)}
    private fun header(c:Canvas,s:String,x:Float,y:Float,z:Float)=text(c,s,x,y,z,WHITE,true)
    private fun battery(c:Canvas,x:Float,y:Float,s:Float){val bw=s*1.45f;val bh=s*.65f;stroke.strokeWidth=1.5f;stroke.color=if(battery.charging)GREEN else MUTED;c.drawRoundRect(RectF(x,y-bh,x+bw,y),bh*.2f,bh*.2f,stroke);fill.color=stroke.color;val f=battery.percent.coerceIn(0,100)/100f;c.drawRect(x+3,y-bh+3,x+3+(bw-6)*f,y-3,fill);text(c,"${battery.percent}%",x+bw+s*.45f,y,s,MUTED,true)}
    private fun line(c:Canvas,x1:Float,y1:Float,x2:Float,y2:Float,h:Float){stroke.strokeWidth=(h*.0011f).coerceAtLeast(1f);stroke.color=BORDER;c.drawLine(x1,y1,x2,y2,stroke)}
    private fun text(c:Canvas,s:String,x:Float,y:Float,z:Float,col:Int,b:Boolean=false){val q=if(b)med else reg;q.textSize=z;q.color=col;c.drawText(s,x,y,q)}
    private fun textCenter(c:Canvas,s:String,x:Float,y:Float,z:Float,col:Int,b:Boolean=false){val q=if(b)med else reg;q.textSize=z;q.color=col;c.drawText(s,x-q.measureText(s)/2f,y,q)}
    private fun textRight(c:Canvas,s:String,x:Float,y:Float,z:Float,col:Int,b:Boolean=false){val q=if(b)med else reg;q.textSize=z;q.color=col;c.drawText(s,x-q.measureText(s),y,q)}

    private fun icon(c:Canvas,code:Int,x:Float,y:Float,r:Float){
        stroke.strokeWidth=(r*.10f).coerceAtLeast(1.8f);stroke.strokeCap=Paint.Cap.ROUND;stroke.color=ICON
        if(code==0){c.drawCircle(x,y,r*.46f,stroke);for(i in 0..7){val a=Math.toRadians(i*45.0);c.drawLine(x+cos(a).toFloat()*r*.67f,y+sin(a).toFloat()*r*.67f,x+cos(a).toFloat()*r*.90f,y+sin(a).toFloat()*r*.90f,stroke)};return}
        if(code in 1..3)c.drawCircle(x-r*.45f,y-r*.35f,r*.32f,stroke)
        val q=Path();q.moveTo(x-r*.78f,y+r*.22f);q.cubicTo(x-r*.82f,y-r*.20f,x-r*.38f,y-r*.32f,x-r*.20f,y-r*.18f);q.cubicTo(x,y-r*.68f,x+r*.56f,y-r*.50f,x+r*.56f,y-r*.10f);q.cubicTo(x+r*.90f,y-r*.08f,x+r*.92f,y+r*.36f,x+r*.56f,y+r*.36f);q.lineTo(x-r*.56f,y+r*.36f);c.drawPath(q,stroke)
        if(code in 51..82)for(i in -1..1)c.drawLine(x+i*r*.3f,y+r*.55f,x+i*r*.3f-r*.1f,y+r*.9f,stroke)
    }

    private fun time()=SimpleDateFormat("HH:mm",Locale("pt","BR")).format(Date(now))
    private fun date()=SimpleDateFormat("EEEE, d 'de' MMMM",Locale("pt","BR")).format(Date(now)).replaceFirstChar{it.uppercase()}
    private fun eventTime(e:CalendarEvent)=if(e.allDay)"Dia todo" else SimpleDateFormat("HH:mm",Locale("pt","BR")).format(Date(e.startMillis))
    private fun ell(s:String,n:Int)=if(s.length<=n)s else s.take(n-1)+"…"

    override fun onTouchEvent(e:MotionEvent):Boolean{if(e.action==MotionEvent.ACTION_UP&&e.x>width*.88f&&e.y<height*.16f){onRefreshRequested?.invoke();performClick()};return true}
    override fun performClick():Boolean{super.performClick();return true}

    companion object{
        val WHITE=Color.rgb(246,246,249);val MUTED=Color.rgb(166,166,178);val DIM=Color.rgb(100,100,112)
        val CARD=Color.rgb(8,8,11);val BORDER=Color.rgb(31,31,39);val BLUE=Color.rgb(102,169,255)
        val GREEN=Color.rgb(100,220,145);val ICON=Color.rgb(231,231,237);val ACCENT=Color.rgb(15,26,44)
    }
}
