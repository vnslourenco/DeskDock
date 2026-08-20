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
    var onCameraRequested:(()->Unit)?=null
    var onCameraSettingsRequested:(()->Unit)?=null

    private val reg=Paint(Paint.ANTI_ALIAS_FLAG).apply{typeface=Typeface.create("sans-serif",Typeface.NORMAL)}
    private val med=Paint(Paint.ANTI_ALIAS_FLAG).apply{typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)}
    private val fill=Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
    private val bitmapPaint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private var now=System.currentTimeMillis()
    private var weather:WeatherSnapshot?=null
    private var battery=BatteryInfo(0,false)
    private var events:List<CalendarEvent> = emptyList()
    private var calStatus="Atualizando agenda…"
    private var calPermission=true
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
    fun setLocationLabel(v:String){invalidate()}
    fun setCalendarPermission(v:Boolean){calPermission=v;invalidate()}
    fun setCalendarStatus(v:String){calStatus=v;invalidate()}
    fun setCameraLive(v:Boolean){cameraLive=v;invalidate()}
    fun setInfoOptions(uv:Boolean,air:Boolean,sun:Boolean,countdown:Boolean){showUv=uv;showAir=air;showSun=sun;showCountdown=countdown;invalidate()}

    fun setForecastModeDaily(v:Boolean){
        if(v==showDaily && forecastAlpha>=.99f)return
        forecastAnimator?.cancel();var cancelled=false
        forecastAnimator=ValueAnimator.ofFloat(forecastAlpha,0f).apply{
            duration=1200;interpolator=AccelerateDecelerateInterpolator()
            addUpdateListener{forecastAlpha=it.animatedValue as Float;invalidate()}
            addListener(object:AnimatorListenerAdapter(){
                override fun onAnimationCancel(animation:Animator){cancelled=true}
                override fun onAnimationEnd(animation:Animator){
                    if(cancelled)return;showDaily=v
                    forecastAnimator=ValueAnimator.ofFloat(0f,1f).apply{
                        duration=1600;interpolator=AccelerateDecelerateInterpolator()
                        addUpdateListener{forecastAlpha=it.animatedValue as Float;invalidate()};start()
                    }
                }
            });start()
        }
    }

    fun shiftForBurnInProtection(){sx=Random.nextInt(-4,5).toFloat();sy=Random.nextInt(-3,4).toFloat();invalidate()}

    override fun onDraw(c:Canvas){
        super.onDraw(c);c.drawColor(Color.BLACK);c.save();c.translate(sx,sy)
        val w=width.toFloat();val h=height.toFloat();if(w<=0||h<=0){c.restore();return}
        drawAtmosphere(c,w,h)
        drawHero(c,w,h)
        val forecast=RectF(w*.025f,h*.335f,w*.600f,h*.555f)
        val layer=c.saveLayerAlpha(forecast,(forecastAlpha*255).toInt().coerceIn(0,255))
        if(showDaily)drawDaily(c,forecast,h) else drawHourly(c,forecast,h)
        c.restoreToCount(layer)
        drawNext(c,w,h)
        drawAgenda(c,w,h)
        drawFooter(c,w,h)
        drawCamera(c,w,h)
        c.restore()
    }

    private fun drawAtmosphere(c:Canvas,w:Float,h:Float){
        val code=weather?.weatherCode?:2
        val bmp=when{code==0->WeatherArt.clear;code in 51..99->WeatherArt.rain;else->WeatherArt.partly}?:return
        val dst=RectF(w*.20f,h*.005f,w*.745f,h*.315f)
        bitmapPaint.alpha=205
        c.drawBitmap(bmp,null,dst,bitmapPaint)
        bitmapPaint.alpha=255
        fill.shader=LinearGradient(dst.left,dst.top,dst.left+w*.13f,dst.top,Color.BLACK,Color.TRANSPARENT,Shader.TileMode.CLAMP);c.drawRect(dst,fill);fill.shader=null
        fill.shader=LinearGradient(dst.right-w*.15f,dst.top,dst.right,dst.top,Color.TRANSPARENT,Color.BLACK,Shader.TileMode.CLAMP);c.drawRect(dst,fill);fill.shader=null
        fill.shader=LinearGradient(0f,h*.20f,0f,h*.33f,Color.TRANSPARENT,Color.BLACK,Shader.TileMode.CLAMP);c.drawRect(0f,h*.19f,w,h*.34f,fill);fill.shader=null
    }

    private fun drawHero(c:Canvas,w:Float,h:Float){
        val x=w*.045f
        text(c,time(),x,h*.155f,h*.120f,WHITE,false)
        text(c,date(),x,h*.220f,h*.039f,LIGHT,false)
        battery(c,x,h*.279f,h*.031f)

        weather?.let{v->
            val wx=w*.648f
            text(c,"${v.temperatureC}°",wx,h*.151f,h*.100f,WHITE,false)
            text(c,"Sensação ${v.feelsLikeC}°",wx,h*.211f,h*.036f,LIGHT,false)
            text(c,conditionLabel(v.weatherCode),wx,h*.260f,h*.034f,LIGHT,false)
            var chipX=w*.310f
            if(showAir)v.airQualityIndex?.let{chipX=chip(c,chipX,h*.292f,"AR ${aqShort(it).uppercase(Locale("pt","BR"))}",airColor(it),h)}
            if(showUv)v.uvIndex?.let{chipX=chip(c,chipX,h*.292f,"UV ${uvText(it)}",uvColor(it),h)}
            if(showSun){val hr=Calendar.getInstance().get(Calendar.HOUR_OF_DAY);val s=if(hr<12)v.sunrise else v.sunset;if(!s.isNullOrBlank())chip(c,chipX,h*.292f,if(hr<12)"SOL $s" else "PÔR $s",ORANGE,h)}
        }?:text(c,if(error)"--" else "…",w*.648f,h*.151f,h*.100f,MUTED,false)
    }

    private fun chip(c:Canvas,x:Float,y:Float,label:String,color:Int,h:Float):Float{
        med.textSize=h*.029f;val tw=med.measureText(label);val px=h*.020f
        val r=RectF(x,y-h*.040f,x+tw+px*2.4f,y+h*.012f)
        fill.color=Color.argb(178,7,9,13);c.drawRoundRect(r,h*.026f,h*.026f,fill)
        stroke.strokeWidth=1.2f;stroke.color=Color.argb(150,Color.red(color),Color.green(color),Color.blue(color));c.drawRoundRect(r,h*.026f,h*.026f,stroke)
        fill.color=color;c.drawCircle(x+px*.80f,y-h*.012f,h*.0065f,fill)
        text(c,label,x+px*1.35f,y,h*.029f,color,true)
        return r.right+h*.012f
    }

    private fun drawHourly(c:Canvas,r:RectF,h:Float){
        glass(c,r,h)
        text(c,"PRÓXIMAS HORAS",r.left+h*.025f,r.top+h*.043f,h*.027f,LIGHT,true)
        val a=weather?.hourly?.take(5).orEmpty();if(a.isEmpty())return
        val top=r.top+h*.055f;val cw=(r.width()-h*.025f)/5f
        a.forEachIndexed{i,v->
            val cx=r.left+h*.012f+cw*(i+.5f)
            textCenter(c,v.hour,cx,top+h*.050f,h*.035f,WHITE,true)
            weatherIcon(c,v.weatherCode,cx,top+h*.103f,h*.033f)
            textCenter(c,"${v.temperatureC}°",cx,top+h*.160f,h*.041f,WHITE,true)
            textCenter(c,"${v.rainChance}%",cx,top+h*.205f,h*.031f,if(v.rainChance>0)BLUE else DIM,false)
        }
    }

    private fun drawDaily(c:Canvas,r:RectF,h:Float){
        glass(c,r,h)
        text(c,"PRÓXIMOS 3 DIAS",r.left+h*.025f,r.top+h*.043f,h*.027f,LIGHT,true)
        val a=weather?.nextDays?.take(3).orEmpty();if(a.isEmpty())return
        val cw=(r.width()-h*.025f)/3f
        a.forEachIndexed{i,v->
            val cx=r.left+h*.012f+cw*(i+.5f)
            textCenter(c,v.dayLabel.uppercase(Locale("pt","BR")),cx,r.top+h*.090f,h*.038f,WHITE,true)
            weatherIcon(c,v.weatherCode,cx,r.top+h*.135f,h*.032f)
            textCenter(c,"${v.maxC}° / ${v.minC}°",cx,r.top+h*.185f,h*.039f,WHITE,true)
            textCenter(c,"${v.rainChance}% chuva",cx,r.top+h*.215f,h*.029f,if(v.rainChance>0)BLUE else MUTED,false)
        }
    }

    private fun drawNext(c:Canvas,w:Float,h:Float){
        val r=RectF(w*.025f,h*.575f,w*.600f,h*.810f)
        val next=events.firstOrNull{it.endMillis>now}
        val urgent=next?.let{countdownLabel(it)!=null}==true
        fill.shader=LinearGradient(r.left,r.top,r.right,r.bottom,if(urgent)Color.rgb(9,24,46) else Color.rgb(7,15,27),Color.rgb(3,7,13),Shader.TileMode.CLAMP);c.drawRoundRect(r,h*.020f,h*.020f,fill);fill.shader=null
        stroke.strokeWidth=1.2f;stroke.color=if(urgent)Color.rgb(69,110,203) else Color.rgb(27,42,66);c.drawRoundRect(r,h*.020f,h*.020f,stroke)
        fill.color=if(urgent)BLUE else PURPLE;c.drawRoundRect(RectF(r.left+h*.011f,r.top+h*.020f,r.left+h*.016f,r.bottom-h*.020f),h*.003f,h*.003f,fill)
        text(c,"PRÓXIMO COMPROMISSO",r.left+h*.045f,r.top+h*.068f,h*.028f,if(urgent)BLUE else PURPLE,true)
        if(next==null){text(c,"Sem próximos compromissos",r.left+h*.045f,r.centerY()+h*.020f,h*.039f,MUTED,false);return}
        val tx=r.left+h*.045f;val y=r.top+r.height()*.67f
        text(c,eventTime(next),tx,y,h*.067f,WHITE,false)
        fitText(c,next.title,tx+r.width()*.30f,y,h*.051f,h*.033f,r.right-(tx+r.width()*.30f)-h*.025f,WHITE,false)
        countdownLabel(next)?.let{text(c,it,tx,r.bottom-h*.044f,h*.035f,BLUE,true)}
    }

    private fun drawAgenda(c:Canvas,w:Float,h:Float){
        val left=w*.635f;val right=w*.962f;val top=h*.325f;val bottom=h*.790f
        drawCalendarIcon(c,left,top+h*.020f,h*.021f)
        text(c,"AGENDA DE HOJE",left+h*.052f,top+h*.030f,h*.033f,LIGHT,true)
        val list=events.take(5)
        if(list.isEmpty()){text(c,if(!calPermission)"Permita acesso ao calendário" else calStatus,left,top+h*.120f,h*.031f,MUTED,false);return}
        val start=top+h*.085f;val rowH=(bottom-start)/5f
        line(c,left-h*.005f,start-h*.030f,left-h*.005f,bottom-h*.010f,Color.rgb(35,39,48),2f)
        list.forEachIndexed{i,e->
            val y=start+rowH*i;val active=i==0
            fill.color=if(active)BLUE else Color.rgb(129,136,151);c.drawCircle(left-h*.005f,y+h*.008f,h*.006f,fill)
            text(c,eventTime(e),left+h*.028f,y+h*.016f,h*.039f,if(active)BLUE else WHITE,false)
            fitText(c,e.title,left+w*.085f,y+h*.016f,h*.034f,h*.026f,right-(left+w*.085f),if(active)WHITE else LIGHT,false)
            if(active)countdownLabel(e)?.let{text(c,it,left+w*.085f,y+h*.055f,h*.028f,BLUE,true)}
        }
    }

    private fun drawFooter(c:Canvas,w:Float,h:Float){
        text(c,"↻",w*.045f,h*.910f,h*.032f,DIM,false)
        text(c,"Atualizado ${SimpleDateFormat("HH:mm",Locale("pt","BR")).format(Date(weather?.updatedAtMillis?:now))}",w*.072f,h*.909f,h*.024f,MUTED,false)
        weather?.let{v->
            statPill(c,w*.590f,h*.852f,w*.705f,h*.938f,"${v.todayMaxC}°","Máx hoje",RED,h,true)
            statPill(c,w*.715f,h*.852f,w*.830f,h*.938f,"${v.humidityPercent?:0}%","Umidade",BLUE,h,false)
        }
    }

    private fun statPill(c:Canvas,l:Float,t:Float,r:Float,b:Float,value:String,label:String,color:Int,h:Float,thermo:Boolean){
        val rr=RectF(l,t,r,b);fill.color=Color.argb(210,6,8,12);c.drawRoundRect(rr,h*.043f,h*.043f,fill);stroke.strokeWidth=1f;stroke.color=Color.rgb(26,31,41);c.drawRoundRect(rr,h*.043f,h*.043f,stroke)
        if(thermo)drawThermo(c,l+h*.028f,(t+b)/2,h*.017f,color) else drawDrop(c,l+h*.028f,(t+b)/2,h*.017f,color)
        text(c,value,l+h*.060f,t+h*.040f,h*.036f,WHITE,false);text(c,label,l+h*.060f,b-h*.012f,h*.022f,LIGHT,false)
    }

    private fun drawCamera(c:Canvas,w:Float,h:Float){
        val cx=w*.895f;val cy=h*.885f;val rr=h*.067f
        fill.shader=RadialGradient(cx,cy,rr*1.45f,Color.argb(85,73,104,255),Color.TRANSPARENT,Shader.TileMode.CLAMP);c.drawCircle(cx,cy,rr*1.45f,fill);fill.shader=null
        fill.color=Color.rgb(7,9,14);c.drawCircle(cx,cy,rr,fill);stroke.strokeWidth=2.3f;stroke.color=if(cameraLive)GREEN else PURPLE;c.drawCircle(cx,cy,rr,stroke)
        drawCameraGlyph(c,cx,cy-h*.010f,rr*.48f,WHITE)
        textCenter(c,if(cameraLive)"AO VIVO" else "CÂMERA",cx,cy+rr*.70f,h*.021f,LIGHT,true)
    }

    private fun glass(c:Canvas,r:RectF,h:Float){
        fill.shader=LinearGradient(r.left,r.top,r.right,r.bottom,Color.argb(235,8,12,19),Color.argb(230,4,6,10),Shader.TileMode.CLAMP);c.drawRoundRect(r,h*.020f,h*.020f,fill);fill.shader=null
        stroke.strokeWidth=1.1f;stroke.color=Color.rgb(28,34,44);c.drawRoundRect(r,h*.020f,h*.020f,stroke)
    }

    private fun countdownLabel(e:CalendarEvent):String?{
        if(!showCountdown||e.allDay)return null
        if(now in e.startMillis..e.endMillis)return "AGORA"
        val d=e.startMillis-now;if(d<=0||d>60*60*1000L)return null
        val m=((d+59999)/60000).coerceAtLeast(1);return if(m==1L)"em 1 min" else "em $m min"
    }

    private fun conditionLabel(code:Int)=when(code){0->"Céu limpo";1->"Predominantemente limpo";2->"Parcialmente nublado";3->"Nublado";45,48->"Neblina";in 51..67->"Chuva";in 71..77->"Neve";in 80..82->"Pancadas de chuva";in 95..99->"Trovoadas";else->"Condição atual"}
    private fun aqShort(a:Int)=when{a<=50->"bom";a<=100->"moderado";a<=150->"ruim";a<=200->"muito ruim";else->"crítico"}
    private fun airColor(a:Int)=when{a<=50->GREEN;a<=100->ORANGE;else->RED}
    private fun uvText(v:Double)=when{v<3->"${v.toInt()} BAIXO";v<6->"${v.toInt()} MOD.";v<8->"${v.toInt()} ALTO";else->"${v.toInt()} M.ALTO"}
    private fun uvColor(v:Double)=when{v<3->GREEN;v<6->ORANGE;else->RED}

    private fun text(c:Canvas,s:String,x:Float,y:Float,z:Float,col:Int,b:Boolean=false){val p=if(b)med else reg;p.textSize=z;p.color=col;c.drawText(s,x,y,p)}
    private fun textCenter(c:Canvas,s:String,x:Float,y:Float,z:Float,col:Int,b:Boolean=false){val p=if(b)med else reg;p.textSize=z;p.color=col;c.drawText(s,x-p.measureText(s)/2,y,p)}
    private fun fitText(c:Canvas,s:String,x:Float,y:Float,max:Float,min:Float,maxWidth:Float,col:Int,b:Boolean=false){val p=if(b)med else reg;var z=max;p.textSize=z;while(p.measureText(s)>maxWidth&&z>min){z-=1;p.textSize=z};p.color=col;c.drawText(s,x,y,p)}
    private fun line(c:Canvas,x1:Float,y1:Float,x2:Float,y2:Float,col:Int,width:Float){stroke.color=col;stroke.strokeWidth=width;c.drawLine(x1,y1,x2,y2,stroke)}

    private fun battery(c:Canvas,x:Float,y:Float,s:Float){val bw=s*1.55f;val bh=s*.66f;stroke.strokeWidth=2f;stroke.color=if(battery.charging)GREEN else LIGHT;c.drawRoundRect(RectF(x,y-bh,x+bw,y),bh*.20f,bh*.20f,stroke);fill.color=stroke.color;val f=battery.percent.coerceIn(0,100)/100f;c.drawRoundRect(RectF(x+4,y-bh+4,x+4+(bw-8)*f,y-4),2f,2f,fill);text(c,"${battery.percent}%",x+bw+s*.48f,y,s*1.02f,LIGHT,true)}

    private fun weatherIcon(c:Canvas,code:Int,x:Float,y:Float,r:Float){
        stroke.strokeWidth=(r*.10f).coerceAtLeast(2f);stroke.color=ICON
        if(code==0){stroke.color=ORANGE;c.drawCircle(x,y,r*.45f,stroke);for(i in 0..7){val a=Math.toRadians(i*45.0);c.drawLine(x+cos(a).toFloat()*r*.66f,y+sin(a).toFloat()*r*.66f,x+cos(a).toFloat()*r*.90f,y+sin(a).toFloat()*r*.90f,stroke)};return}
        if(code in 1..2){stroke.color=ORANGE;c.drawCircle(x-r*.42f,y-r*.35f,r*.29f,stroke);stroke.color=ICON}
        val p=Path();p.moveTo(x-r*.78f,y+r*.22f);p.cubicTo(x-r*.82f,y-r*.20f,x-r*.38f,y-r*.32f,x-r*.20f,y-r*.18f);p.cubicTo(x,y-r*.68f,x+r*.56f,y-r*.50f,x+r*.56f,y-r*.10f);p.cubicTo(x+r*.90f,y-r*.08f,x+r*.92f,y+r*.36f,x+r*.56f,y+r*.36f);p.lineTo(x-r*.56f,y+r*.36f);c.drawPath(p,stroke)
        if(code in 51..99){stroke.color=BLUE;for(i in -1..1)c.drawLine(x+i*r*.30f,y+r*.55f,x+i*r*.30f-r*.10f,y+r*.90f,stroke)}
    }

    private fun drawCalendarIcon(c:Canvas,x:Float,y:Float,s:Float){stroke.color=LIGHT;stroke.strokeWidth=2.2f;c.drawRoundRect(RectF(x,y-s*.72f,x+s*1.1f,y+s*.28f),s*.12f,s*.12f,stroke);c.drawLine(x,y-s*.40f,x+s*1.1f,y-s*.40f,stroke)}
    private fun drawCameraGlyph(c:Canvas,x:Float,y:Float,s:Float,col:Int){stroke.color=col;stroke.strokeWidth=(s*.11f).coerceAtLeast(2f);c.drawRoundRect(RectF(x-s*.80f,y-s*.45f,x+s*.80f,y+s*.55f),s*.15f,s*.15f,stroke);c.drawCircle(x,y+s*.04f,s*.32f,stroke);c.drawRoundRect(RectF(x-s*.25f,y-s*.64f,x+s*.25f,y-s*.42f),s*.08f,s*.08f,stroke)}
    private fun drawDrop(c:Canvas,x:Float,y:Float,s:Float,col:Int){stroke.color=col;stroke.strokeWidth=2.2f;val p=Path();p.moveTo(x,y-s);p.cubicTo(x-s*.75f,y-s*.10f,x-s*.55f,y+s*.70f,x,y+s*.80f);p.cubicTo(x+s*.55f,y+s*.70f,x+s*.75f,y-s*.10f,x,y-s);c.drawPath(p,stroke)}
    private fun drawThermo(c:Canvas,x:Float,y:Float,s:Float,col:Int){stroke.color=col;stroke.strokeWidth=2.2f;c.drawCircle(x,y+s*.42f,s*.28f,stroke);c.drawRoundRect(RectF(x-s*.11f,y-s*.75f,x+s*.11f,y+s*.38f),s*.10f,s*.10f,stroke)}

    private fun time()=SimpleDateFormat("HH:mm",Locale("pt","BR")).format(Date(now))
    private fun date()=SimpleDateFormat("EEEE, d 'de' MMMM",Locale("pt","BR")).format(Date(now)).replaceFirstChar{it.uppercase()}
    private fun eventTime(e:CalendarEvent)=if(e.allDay)"Dia todo" else SimpleDateFormat("HH:mm",Locale("pt","BR")).format(Date(e.startMillis))

    override fun onTouchEvent(e:MotionEvent):Boolean{
        when(e.action){
            MotionEvent.ACTION_DOWN->{downAt=System.currentTimeMillis();downX=e.x;downY=e.y;return true}
            MotionEvent.ACTION_UP->{
                val held=System.currentTimeMillis()-downAt;val w=width.toFloat();val h=height.toFloat()
                val cam=((e.x-w*.895f)*(e.x-w*.895f)+(e.y-h*.885f)*(e.y-h*.885f))<(h*.095f)*(h*.095f)
                if(cam){if(held>=700)onCameraSettingsRequested?.invoke()else onCameraRequested?.invoke();performClick();return true}
                if(held>=700&&downX<w*.30f&&downY<h*.30f){onSettingsRequested?.invoke();performClick();return true}
                if(e.x<w*.20f&&e.y>h*.84f){onRefreshRequested?.invoke();performClick();return true}
            }
        };return true
    }
    override fun performClick():Boolean{super.performClick();return true}

    companion object{
        val WHITE=Color.rgb(247,247,250);val LIGHT=Color.rgb(203,204,214);val MUTED=Color.rgb(164,166,179);val DIM=Color.rgb(101,104,117)
        val BLUE=Color.rgb(93,154,255);val PURPLE=Color.rgb(116,109,255);val GREEN=Color.rgb(88,210,126);val ORANGE=Color.rgb(246,181,61);val RED=Color.rgb(240,82,83);val ICON=Color.rgb(229,231,238)
    }
}