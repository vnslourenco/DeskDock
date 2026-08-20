package com.deskdock.app.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import com.deskdock.app.model.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class DockView(context: Context) : View(context) {
    var onRefreshRequested:(()->Unit)?=null
    var onSettingsRequested:(()->Unit)?=null

    private val reg=Paint(Paint.ANTI_ALIAS_FLAG).apply{typeface=Typeface.create("sans-serif",Typeface.NORMAL)}
    private val med=Paint(Paint.ANTI_ALIAS_FLAG).apply{typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)}
    private val fill=Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE}

    private var now=System.currentTimeMillis()
    private var weather:WeatherSnapshot?=null
    private var battery=BatteryInfo(0,false)
    private var events:List<CalendarEvent> = emptyList()
    private var calStatus="Atualizando agenda…"
    private var calPermission=true
    private var locationLabel=""
    private var error=false
    private var sx=0f; private var sy=0f
    private var showDaily=false; private var cameraLive=false
    private var forecastAlpha=1f; private var forecastAnimator:ValueAnimator?=null
    private var showUv=true; private var showAir=true; private var showSun=true; private var showCountdown=true
    private var downAt=0L; private var downX=0f; private var downY=0f

    fun setNow(v:Long){now=v;invalidate()}
    fun setWeather(v:WeatherSnapshot){weather=v;error=false;invalidate()}
    fun setWeatherLoading(v:Boolean){invalidate()}
    fun setWeatherError(){error=true;invalidate()}
    fun setBattery(v:BatteryInfo){battery=v;invalidate()}
    fun setEvents(v:List<CalendarEvent>){events=v;invalidate()}
    fun setLocationLabel(v:String){locationLabel=v.substringBefore(" · ±");invalidate()}
    fun setCalendarPermission(v:Boolean){calPermission=v;invalidate()}
    fun setCalendarStatus(v:String){calStatus=v;invalidate()}
    fun setCameraLive(v:Boolean){cameraLive=v;invalidate()}
    fun setInfoOptions(uv:Boolean,air:Boolean,sun:Boolean,countdown:Boolean){showUv=uv;showAir=air;showSun=sun;showCountdown=countdown;invalidate()}

    fun setForecastModeDaily(v:Boolean){
        if(v==showDaily && forecastAlpha>=.99f)return
        forecastAnimator?.cancel();var cancelled=false
        forecastAnimator=ValueAnimator.ofFloat(forecastAlpha,0f).apply{
            duration=1300L;interpolator=AccelerateDecelerateInterpolator()
            addUpdateListener{forecastAlpha=it.animatedValue as Float;invalidate()}
            addListener(object:AnimatorListenerAdapter(){
                override fun onAnimationCancel(animation:Animator){cancelled=true}
                override fun onAnimationEnd(animation:Animator){
                    if(cancelled)return
                    showDaily=v
                    forecastAnimator=ValueAnimator.ofFloat(0f,1f).apply{
                        duration=1750L;interpolator=AccelerateDecelerateInterpolator()
                        addUpdateListener{forecastAlpha=it.animatedValue as Float;invalidate()};start()
                    }
                }
            });start()
        }
    }

    fun shiftForBurnInProtection(){sx=Random.nextInt(-6,7).toFloat();sy=Random.nextInt(-4,5).toFloat();invalidate()}

    override fun onDraw(c:Canvas){
        super.onDraw(c);c.drawColor(Color.BLACK);c.save();c.translate(sx,sy)
        val w=width.toFloat();val h=height.toFloat();if(w<=0||h<=0){c.restore();return}
        val pad=w*.014f;val gap=w*.011f;val heroTop=h*.027f;val heroBottom=h*.315f;val lowerTop=h*.34f;val bottom=h*.965f
        val leftW=w*.392f
        val hero=RectF(pad,heroTop,w-pad,heroBottom)
        val camera=RectF(pad,lowerTop,pad+leftW,bottom)
        val info=RectF(camera.right+gap,lowerTop,w-pad,bottom)

        surface(c,hero,h);surface(c,camera,h);surface(c,info,h)
        hero(c,hero,h)
        cameraPanel(c,camera,h)
        val forecastArea=RectF(info.left,info.top,info.right,info.top+info.height()*.46f)
        val agendaArea=RectF(info.left,forecastArea.bottom,info.right,info.bottom)
        line(c,info.left+info.width()*.035f,forecastArea.bottom,info.right-info.width()*.035f,forecastArea.bottom,h)
        val layer=c.saveLayerAlpha(forecastArea,(forecastAlpha*255f).toInt().coerceIn(0,255))
        if(showDaily)dailyWide(c,forecastArea,h) else hourly(c,forecastArea,h)
        c.restoreToCount(layer)
        agenda(c,agendaArea,h)
        c.restore()
    }

    private fun hero(c:Canvas,r:RectF,h:Float){
        val split=r.left+r.width()*.43f
        val clockX=r.left+r.width()*.035f
        text(c,time(),clockX,r.top+r.height()*.48f,h*.128f,WHITE,true)
        fitText(c,date(),clockX,r.top+r.height()*.72f,h*.034f,h*.025f,split-clockX-r.width()*.03f,MUTED,true)
        battery(c,split-r.width()*.115f,r.top+r.height()*.22f,h*.027f)

        val priority=priorityLine()
        if(priority!=null){
            val pr=RectF(clockX,r.bottom-r.height()*.17f,split-r.width()*.025f,r.bottom-r.height()*.045f)
            fill.color=ACCENT;c.drawRoundRect(pr,h*.014f,h*.014f,fill)
            fitText(c,priority,pr.left+h*.012f,pr.centerY()+h*.010f,h*.026f,h*.019f,pr.width()-h*.024f,BLUE,true)
        }

        line(c,split,r.top+r.height()*.12f,split,r.bottom-r.height()*.12f,h)
        weather?.let{v->
            val wx=split+r.width()*.035f
            val contentRight=r.right-r.width()*.028f
            if(locationLabel.isNotBlank()) textRight(c,locationLabel.uppercase(Locale("pt","BR")),contentRight,r.top+r.height()*.17f,h*.019f,DIM,true)
            text(c,"AGORA",wx,r.top+r.height()*.17f,h*.025f,WHITE,true)
            val iconX=wx+r.width()*.075f;val iconY=r.top+r.height()*.48f
            icon(c,v.weatherCode,iconX,iconY,h*.047f)
            text(c,"${v.temperatureC}°",wx+r.width()*.145f,r.top+r.height()*.57f,h*.092f,WHITE,true)
            text(c,"Sensação ${v.feelsLikeC}°",wx+r.width()*.145f,r.top+r.height()*.76f,h*.028f,MUTED,true)

            val chips=mutableListOf<String>()
            if(showUv)v.uvIndex?.let{chips += "UV ${String.format(Locale.US,"%.1f",it)}"}
            if(showAir)v.airQualityIndex?.let{chips += "AR ${aqLabel(it).uppercase(Locale("pt","BR"))}"}
            if(showSun){val hr=Calendar.getInstance().get(Calendar.HOUR_OF_DAY);val s=if(hr<12)v.sunrise?.let{"SOL $it"} else v.sunset?.let{"SOL $it"};if(s!=null)chips+=s}
            var cx=wx
            chips.take(3).forEach{label->
                val tw=measure(label,h*.021f,true);val cr=RectF(cx,r.bottom-r.height()*.16f,cx+tw+h*.025f,r.bottom-r.height()*.045f)
                fill.color=CHIP;c.drawRoundRect(cr,h*.012f,h*.012f,fill);text(c,label,cx+h*.012f,cr.centerY()+h*.008f,h*.021f,MUTED,true);cx=cr.right+h*.009f
            }
        }?:run{textCenter(c,if(error)"Sem clima" else "Atualizando…",split+(r.right-split)*.5f,r.centerY(),h*.035f,MUTED,true)}
    }

    private fun priorityLine():String?{
        val next=events.firstOrNull{!it.allDay && it.endMillis>now}
        if(next!=null){
            if(now in next.startMillis..next.endMillis)return "AGORA · ${ell(next.title,25)}"
            val diff=next.startMillis-now
            if(diff in 1..(60*60*1000L))return "${ell(next.title,22)} · em ${((diff+59999)/60000)} min"
        }
        val w=weather
        if(showUv && (w?.uvIndex?:0.0)>=6.0)return "UV ${String.format(Locale.US,"%.1f",w?.uvIndex)} · proteção recomendada"
        if(showAir && (w?.airQualityIndex?:0)>100)return "Qualidade do ar · ${aqLabel(w?.airQualityIndex?:0)}"
        return null
    }

    private fun cameraPanel(c:Canvas,r:RectF,h:Float){
        val x=r.left+r.width()*.045f
        header(c,"CÂMERA",x,r.top+r.height()*.085f,h*.029f)
        if(cameraLive) chipRight(c,"AO VIVO",r.right-r.width()*.045f,r.top+r.height()*.083f,h)
        else textRight(c,"TOQUE PARA ABRIR",r.right-r.width()*.045f,r.top+r.height()*.085f,h*.019f,DIM,true)
    }

    private fun hourly(c:Canvas,r:RectF,h:Float){
        val x=r.left+r.width()*.035f;header(c,"PRÓXIMAS HORAS",x,r.top+r.height()*.17f,h*.030f)
        val a=weather?.hourly?.take(5).orEmpty();if(a.isEmpty())return
        val cw=r.width()*.92f/5f;val start=x
        a.forEachIndexed{i,v->val cx=start+cw*i+cw/2f;textCenter(c,v.hour,cx,r.top+r.height()*.38f,h*.041f,WHITE,true);icon(c,v.weatherCode,cx,r.top+r.height()*.57f,h*.034f);textCenter(c,"${v.temperatureC}°",cx,r.top+r.height()*.77f,h*.044f,WHITE,true);textCenter(c,"${v.rainChance}%",cx,r.top+r.height()*.93f,h*.033f,BLUE,true)}
    }

    private fun dailyWide(c:Canvas,r:RectF,h:Float){
        val x=r.left+r.width()*.035f;header(c,"PRÓXIMOS 3 DIAS",x,r.top+r.height()*.17f,h*.030f)
        val a=weather?.nextDays?.take(3).orEmpty();if(a.isEmpty())return
        val cw=r.width()*.90f/3f;val start=x
        a.forEachIndexed{i,v->val cx=start+cw*i+cw/2f;if(i>0)line(c,start+cw*i,r.top+r.height()*.28f,start+cw*i,r.bottom-r.height()*.08f,h);textCenter(c,v.dayLabel.uppercase(Locale("pt","BR")),cx,r.top+r.height()*.43f,h*.040f,WHITE,true);icon(c,v.weatherCode,cx,r.top+r.height()*.61f,h*.035f);textCenter(c,"${v.maxC}° / ${v.minC}°",cx,r.top+r.height()*.80f,h*.041f,WHITE,true);textCenter(c,"${v.rainChance}% chuva",cx,r.top+r.height()*.94f,h*.029f,BLUE,true)}
    }

    private fun agenda(c:Canvas,r:RectF,h:Float){
        val x=r.left+r.width()*.035f;header(c,"HOJE",x,r.top+r.height()*.14f,h*.030f)
        if(events.isEmpty()){text(c,if(!calPermission)"Permita acesso ao calendário" else calStatus,x,r.top+r.height()*.52f,h*.032f,MUTED,true);return}
        val visible=events.take(4);val listTop=r.top+r.height()*.22f;val rowH=r.height()*.18f
        visible.forEachIndexed{i,e->
            val top=listTop+i*rowH
            val active=i==0 && !e.allDay && (e.startMillis-now)<=60*60*1000L
            if(active){fill.color=ACCENT;c.drawRoundRect(RectF(x-r.width()*.008f,top+h*.006f,r.right-r.width()*.028f,top+rowH-h*.006f),h*.014f,h*.014f,fill)} else if(i>0) line(c,x,top,r.right-r.width()*.035f,top,h)
            text(c,eventTime(e),x,top+rowH*.55f,h*.045f,if(active)BLUE else WHITE,true)
            val titleX=x+r.width()*.22f
            text(c,ell(e.title,30),titleX,top+rowH*.53f,h*.039f,if(active)WHITE else MUTED,true)
            if(showCountdown && i==0 && !e.allDay)countdownLabel(e)?.let{chip(c,it,titleX,top+rowH*.73f,h)}
        }
    }

    private fun countdownLabel(e:CalendarEvent):String?{val diff=e.startMillis-now;return when{now in e.startMillis..e.endMillis->"AGORA";diff in 1..(60*60*1000L)->"EM ${((diff+59999)/60000)} MIN";else->null}}
    private fun aqLabel(aqi:Int)=when{aqi<=50->"boa";aqi<=100->"moderada";aqi<=150->"ruim";aqi<=200->"muito ruim";else->"crítica"}

    private fun surface(c:Canvas,r:RectF,h:Float){fill.color=CARD;c.drawRoundRect(r,h*.021f,h*.021f,fill);stroke.strokeWidth=(h*.0011f).coerceAtLeast(1f);stroke.color=BORDER;c.drawRoundRect(r,h*.021f,h*.021f,stroke)}
    private fun header(c:Canvas,s:String,x:Float,y:Float,z:Float)=text(c,s,x,y,z,WHITE,true)
    private fun chip(c:Canvas,s:String,x:Float,y:Float,h:Float){val z=h*.019f;val tw=measure(s,z,true);val r=RectF(x,y-h*.017f,x+tw+h*.022f,y+h*.008f);fill.color=ACCENT;c.drawRoundRect(r,h*.010f,h*.010f,fill);text(c,s,x+h*.010f,y,z,BLUE,true)}
    private fun chipRight(c:Canvas,s:String,right:Float,y:Float,h:Float){val z=h*.018f;val tw=measure(s,z,true);val r=RectF(right-tw-h*.024f,y-h*.017f,right,y+h*.010f);fill.color=ACCENT;c.drawRoundRect(r,h*.010f,h*.010f,fill);text(c,s,r.left+h*.011f,y,z,BLUE,true)}
    private fun battery(c:Canvas,x:Float,y:Float,s:Float){val bw=s*1.45f;val bh=s*.65f;stroke.strokeWidth=1.7f;stroke.color=if(battery.charging)GREEN else MUTED;c.drawRoundRect(RectF(x,y-bh,x+bw,y),bh*.2f,bh*.2f,stroke);fill.color=stroke.color;val f=battery.percent.coerceIn(0,100)/100f;c.drawRect(x+3,y-bh+3,x+3+(bw-6)*f,y-3,fill);text(c,"${battery.percent}%",x+bw+s*.40f,y,s,MUTED,true)}
    private fun line(c:Canvas,x1:Float,y1:Float,x2:Float,y2:Float,h:Float){stroke.strokeWidth=(h*.0010f).coerceAtLeast(1f);stroke.color=BORDER;c.drawLine(x1,y1,x2,y2,stroke)}
    private fun text(c:Canvas,s:String,x:Float,y:Float,z:Float,col:Int,b:Boolean=false){val q=if(b)med else reg;q.textSize=z;q.color=col;c.drawText(s,x,y,q)}
    private fun textCenter(c:Canvas,s:String,x:Float,y:Float,z:Float,col:Int,b:Boolean=false){val q=if(b)med else reg;q.textSize=z;q.color=col;c.drawText(s,x-q.measureText(s)/2f,y,q)}
    private fun textRight(c:Canvas,s:String,x:Float,y:Float,z:Float,col:Int,b:Boolean=false){val q=if(b)med else reg;q.textSize=z;q.color=col;c.drawText(s,x-q.measureText(s),y,q)}
    private fun measure(s:String,z:Float,b:Boolean=false):Float{val q=if(b)med else reg;q.textSize=z;return q.measureText(s)}
    private fun fitText(c:Canvas,s:String,x:Float,y:Float,maxSize:Float,minSize:Float,maxWidth:Float,col:Int,b:Boolean=false){val q=if(b)med else reg;var z=maxSize;q.textSize=z;while(q.measureText(s)>maxWidth&&z>minSize){z-=1f;q.textSize=z};q.color=col;c.drawText(s,x,y,q)}

    private fun icon(c:Canvas,code:Int,x:Float,y:Float,r:Float){stroke.strokeWidth=(r*.10f).coerceAtLeast(1.8f);stroke.strokeCap=Paint.Cap.ROUND;stroke.color=ICON;if(code==0){c.drawCircle(x,y,r*.46f,stroke);for(i in 0..7){val a=Math.toRadians(i*45.0);c.drawLine(x+cos(a).toFloat()*r*.67f,y+sin(a).toFloat()*r*.67f,x+cos(a).toFloat()*r*.90f,y+sin(a).toFloat()*r*.90f,stroke)};return};if(code in 1..3)c.drawCircle(x-r*.45f,y-r*.35f,r*.32f,stroke);val q=Path();q.moveTo(x-r*.78f,y+r*.22f);q.cubicTo(x-r*.82f,y-r*.20f,x-r*.38f,y-r*.32f,x-r*.20f,y-r*.18f);q.cubicTo(x,y-r*.68f,x+r*.56f,y-r*.50f,x+r*.56f,y-r*.10f);q.cubicTo(x+r*.90f,y-r*.08f,x+r*.92f,y+r*.36f,x+r*.56f,y+r*.36f);q.lineTo(x-r*.56f,y+r*.36f);c.drawPath(q,stroke);if(code in 51..82)for(i in -1..1)c.drawLine(x+i*r*.3f,y+r*.55f,x+i*r*.3f-r*.1f,y+r*.9f,stroke)}

    private fun time()=SimpleDateFormat("HH:mm",Locale("pt","BR")).format(Date(now))
    private fun date()=SimpleDateFormat("EEEE, d 'de' MMMM",Locale("pt","BR")).format(Date(now)).replaceFirstChar{it.uppercase()}
    private fun eventTime(e:CalendarEvent)=if(e.allDay)"Dia todo" else SimpleDateFormat("HH:mm",Locale("pt","BR")).format(Date(e.startMillis))
    private fun ell(s:String,n:Int)=if(s.length<=n)s else s.take(n-1)+"…"

    override fun onTouchEvent(e:MotionEvent):Boolean{
        when(e.action){
            MotionEvent.ACTION_DOWN->{downAt=System.currentTimeMillis();downX=e.x;downY=e.y;return true}
            MotionEvent.ACTION_UP->{
                val held=System.currentTimeMillis()-downAt
                if(held>=700 && downX<width*.44f && downY<height*.32f){onSettingsRequested?.invoke();performClick();return true}
                if(e.x>width*.88f&&e.y<height*.16f){onRefreshRequested?.invoke();performClick()}
                return true
            }
        }
        return true
    }
    override fun performClick():Boolean{super.performClick();return true}

    companion object{
        val WHITE=Color.rgb(246,246,249);val MUTED=Color.rgb(166,166,178);val DIM=Color.rgb(96,96,108)
        val CARD=Color.rgb(8,8,11);val BORDER=Color.rgb(29,29,37);val BLUE=Color.rgb(105,172,255)
        val GREEN=Color.rgb(100,220,145);val ICON=Color.rgb(231,231,237);val ACCENT=Color.rgb(15,26,44);val CHIP=Color.rgb(14,14,20)
    }
}
