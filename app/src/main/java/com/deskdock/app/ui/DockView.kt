package com.deskdock.app.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import com.deskdock.app.model.*
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
    private var calStatus="Atualizando agenda…"
    private var calPermission=true
    private var error=false
    private var sx=0f
    private var sy=0f
    private var showDaily=false
    private var cameraLive=false
    private var forecastAlpha=1f
    private var forecastAnimator:ValueAnimator?=null

    fun setNow(v:Long){now=v;invalidate()}
    fun setWeather(v:WeatherSnapshot){weather=v;error=false;invalidate()}
    fun setWeatherLoading(v:Boolean){invalidate()}
    fun setWeatherError(){error=true;invalidate()}
    fun setBattery(v:BatteryInfo){battery=v;invalidate()}
    fun setEvents(v:List<CalendarEvent>){events=v;invalidate()}
    fun setLocationLabel(v:String){invalidate()}
    fun setCalendarPermission(v:Boolean){calPermission=v;invalidate()}
    fun setCalendarStatus(v:String){calStatus=v;invalidate()}
    fun setCameraLive(v:Boolean){cameraLive=v;invalidate()}

    fun setForecastModeDaily(v:Boolean){
        if(v==showDaily && forecastAlpha>=.99f) return
        forecastAnimator?.cancel()
        var cancelled=false
        forecastAnimator=ValueAnimator.ofFloat(forecastAlpha,0f).apply{
            duration=220L
            addUpdateListener{forecastAlpha=it.animatedValue as Float;invalidate()}
            addListener(object:AnimatorListenerAdapter(){
                override fun onAnimationCancel(animation:Animator){cancelled=true}
                override fun onAnimationEnd(animation:Animator){
                    if(cancelled)return
                    showDaily=v
                    forecastAnimator=ValueAnimator.ofFloat(0f,1f).apply{
                        duration=320L
                        addUpdateListener{forecastAlpha=it.animatedValue as Float;invalidate()}
                        start()
                    }
                }
            })
            start()
        }
    }

    fun shiftForBurnInProtection(){sx=Random.nextInt(-7,8).toFloat();sy=Random.nextInt(-5,6).toFloat();invalidate()}

    override fun onDraw(c:Canvas){
        super.onDraw(c)
        c.drawColor(Color.BLACK)
        c.save(); c.translate(sx,sy)
        val w=width.toFloat(); val h=height.toFloat(); if(w<=0||h<=0){c.restore();return}

        val pad=w*.012f
        val gap=w*.010f
        val leftW=w*.390f
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
        cameraPanel(c,leftBottom,h)
        val layer=c.saveLayerAlpha(rightTop,(forecastAlpha*255f).toInt().coerceIn(0,255))
        if(showDaily) dailyWide(c,rightTop,h) else hourly(c,rightTop,h)
        c.restoreToCount(layer)
        agenda(c,rightBottom,h)
        c.restore()
    }

    private fun topLeft(c:Canvas,r:RectF,h:Float){
        val split=r.left+r.width()*.64f
        line(c,split,r.top+h*.018f,split,r.bottom-h*.018f,h)
        val clockX=r.left+r.width()*.035f
        text(c,time(),clockX,r.top+r.height()*.47f,h*.120f,WHITE,true)
        val dateX=r.left+r.width()*.04f
        val dateMaxWidth=split-dateX-h*.018f
        fitText(c,date(),dateX,r.top+r.height()*.80f,h*.034f,h*.025f,dateMaxWidth,MUTED,true)

        val batterySize=h*.026f
        val bx=split-r.width()*.17f
        val by=r.top+r.height()*.18f
        battery(c,bx,by,batterySize)

        weather?.let{v->
            val cx=split+(r.right-split)*.50f
            textCenter(c,"ATUAL",cx,r.top+r.height()*.16f,h*.026f,WHITE,true)
            icon(c,v.weatherCode,cx,r.top+r.height()*.34f,h*.034f)
            textCenter(c,"${v.temperatureC}°",cx,r.top+r.height()*.72f,h*.076f,WHITE,true)
            textCenter(c,"Sensação ${v.feelsLikeC}°",cx,r.top+r.height()*.91f,h*.031f,MUTED,true)
        }?:run{textCenter(c,if(error)"--" else "…",split+(r.right-split)*.5f,r.top+r.height()*.62f,h*.060f,MUTED,true)}
    }

    private fun cameraPanel(c:Canvas,r:RectF,h:Float){
        val x=r.left+r.width()*.045f
        header(c,"CÂMERA",x,r.top+r.height()*.095f,h*.029f)
        if(cameraLive) textRight(c,"AO VIVO",r.right-r.width()*.04f,r.top+r.height()*.095f,h*.022f,BLUE,true)
    }

    private fun hourly(c:Canvas,r:RectF,h:Float){
        val x=r.left+r.width()*.035f
        header(c,"PRÓXIMAS HORAS",x,r.top+r.height()*.12f,h*.030f)
        textRight(c,"60 s",r.right-r.width()*.025f,r.top+r.height()*.12f,h*.021f,DIM,true)
        val a=weather?.hourly?.take(5).orEmpty(); if(a.isEmpty()) return
        val usable=r.width()*.92f; val cw=usable/5f; val start=x
        a.forEachIndexed{i,v->
            val cx=start+cw*i+cw/2f
            textCenter(c,v.hour,cx,r.top+r.height()*.28f,h*.045f,WHITE,true)
            icon(c,v.weatherCode,cx,r.top+r.height()*.46f,h*.039f)
            textCenter(c,"${v.temperatureC}°",cx,r.top+r.height()*.70f,h*.047f,WHITE,true)
            textCenter(c,"${v.rainChance}%",cx,r.top+r.height()*.89f,h*.045f,BLUE,true)
        }
    }

    private fun dailyWide(c:Canvas,r:RectF,h:Float){
        val x=r.left+r.width()*.035f
        header(c,"PRÓXIMOS 3 DIAS",x,r.top+r.height()*.12f,h*.030f)
        textRight(c,"30 s",r.right-r.width()*.025f,r.top+r.height()*.12f,h*.021f,DIM,true)
        val a=weather?.nextDays?.take(3).orEmpty(); if(a.isEmpty()) return
        val usable=r.width()*.90f; val cw=usable/3f; val start=x
        a.forEachIndexed{i,v->
            val cx=start+cw*i+cw/2f
            if(i>0) line(c,start+cw*i,r.top+r.height()*.20f,start+cw*i,r.bottom-r.height()*.08f,h)
            textCenter(c,v.dayLabel.uppercase(Locale("pt","BR")),cx,r.top+r.height()*.30f,h*.047f,WHITE,true)
            icon(c,v.weatherCode,cx,r.top+r.height()*.49f,h*.041f)
            textCenter(c,"${v.maxC}° / ${v.minC}°",cx,r.top+r.height()*.72f,h*.047f,WHITE,true)
            textCenter(c,"${v.rainChance}% chuva",cx,r.top+r.height()*.90f,h*.037f,BLUE,true)
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
            text(c,ell(e.title,28),x+r.width()*.25f,rowTop+rowH*.63f,h*.043f,if(i==0)WHITE else MUTED,true)
        }
    }

    private fun surface(c:Canvas,r:RectF,h:Float){fill.color=CARD;c.drawRoundRect(r,h*.020f,h*.020f,fill);stroke.strokeWidth=(h*.0012f).coerceAtLeast(1f);stroke.color=BORDER;c.drawRoundRect(r,h*.020f,h*.020f,stroke)}
    private fun header(c:Canvas,s:String,x:Float,y:Float,z:Float)=text(c,s,x,y,z,WHITE,true)
    private fun battery(c:Canvas,x:Float,y:Float,s:Float){val bw=s*1.45f;val bh=s*.65f;stroke.strokeWidth=1.7f;stroke.color=if(battery.charging)GREEN else MUTED;c.drawRoundRect(RectF(x,y-bh,x+bw,y),bh*.2f,bh*.2f,stroke);fill.color=stroke.color;val f=battery.percent.coerceIn(0,100)/100f;c.drawRect(x+3,y-bh+3,x+3+(bw-6)*f,y-3,fill);text(c,"${battery.percent}%",x+bw+s*.45f,y,s,MUTED,true)}
    private fun line(c:Canvas,x1:Float,y1:Float,x2:Float,y2:Float,h:Float){stroke.strokeWidth=(h*.0011f).coerceAtLeast(1f);stroke.color=BORDER;c.drawLine(x1,y1,x2,y2,stroke)}
    private fun text(c:Canvas,s:String,x:Float,y:Float,z:Float,col:Int,b:Boolean=false){val q=if(b)med else reg;q.textSize=z;q.color=col;c.drawText(s,x,y,q)}
    private fun textCenter(c:Canvas,s:String,x:Float,y:Float,z:Float,col:Int,b:Boolean=false){val q=if(b)med else reg;q.textSize=z;q.color=col;c.drawText(s,x-q.measureText(s)/2f,y,q)}
    private fun textRight(c:Canvas,s:String,x:Float,y:Float,z:Float,col:Int,b:Boolean=false){val q=if(b)med else reg;q.textSize=z;q.color=col;c.drawText(s,x-q.measureText(s),y,q)}
    private fun fitText(c:Canvas,s:String,x:Float,y:Float,maxSize:Float,minSize:Float,maxWidth:Float,col:Int,b:Boolean=false){val q=if(b)med else reg;var z=maxSize;q.textSize=z;while(q.measureText(s)>maxWidth && z>minSize){z-=1f;q.textSize=z};q.color=col;c.drawText(s,x,y,q)}

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
