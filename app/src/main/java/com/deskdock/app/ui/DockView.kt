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
        if(v==showDaily && forecastAlpha>=.99f) return
        forecastAnimator?.cancel(); var cancelled=false
        forecastAnimator=ValueAnimator.ofFloat(forecastAlpha,0f).apply{
            duration=1200L;interpolator=AccelerateDecelerateInterpolator()
            addUpdateListener{forecastAlpha=it.animatedValue as Float;invalidate()}
            addListener(object:AnimatorListenerAdapter(){
                override fun onAnimationCancel(animation:Animator){cancelled=true}
                override fun onAnimationEnd(animation:Animator){
                    if(cancelled)return
                    showDaily=v
                    forecastAnimator=ValueAnimator.ofFloat(0f,1f).apply{
                        duration=1600L;interpolator=AccelerateDecelerateInterpolator()
                        addUpdateListener{forecastAlpha=it.animatedValue as Float;invalidate()};start()
                    }
                }
            });start()
        }
    }

    fun shiftForBurnInProtection(){sx=Random.nextInt(-5,6).toFloat();sy=Random.nextInt(-4,5).toFloat();invalidate()}

    override fun onDraw(c:Canvas){
        super.onDraw(c);c.drawColor(Color.BLACK);c.save();c.translate(sx,sy)
        val w=width.toFloat();val h=height.toFloat();if(w<=0||h<=0){c.restore();return}
        drawOuterFrame(c,w,h)
        drawHero(c,w,h)
        val forecastRect=RectF(w*.022f,h*.332f,w*.598f,h*.552f)
        val layer=c.saveLayerAlpha(forecastRect,(forecastAlpha*255f).toInt().coerceIn(0,255))
        if(showDaily) drawDailyStrip(c,forecastRect,h) else drawHourlyStrip(c,forecastRect,h)
        c.restoreToCount(layer)
        drawNextCard(c,w,h)
        drawAgenda(c,w,h)
        drawBottomStatus(c,w,h)
        drawCameraButton(c,w,h)
        c.restore()
    }

    private fun drawOuterFrame(c:Canvas,w:Float,h:Float){
        stroke.strokeWidth=1.5f;stroke.color=Color.rgb(32,34,41)
        c.drawRoundRect(RectF(4f,4f,w-4f,h-4f),h*.018f,h*.018f,stroke)
    }

    private fun drawHero(c:Canvas,w:Float,h:Float){
        val clockX=w*.045f
        text(c,time(),clockX,h*.165f,h*.118f,WHITE,false)
        text(c,date(),clockX,h*.222f,h*.033f,MUTED,false)
        battery(c,clockX,h*.275f,h*.025f)

        val cloudX=w*.355f; val cloudY=h*.145f
        drawHeroCloud(c,cloudX,cloudY,h*.075f,weather?.weatherCode?:3)

        val wx=w*.505f
        weather?.let{v->
            text(c,"${v.temperatureC}°",wx,h*.160f,h*.085f,WHITE,false)
            text(c,"Sensação ${v.feelsLikeC}°",wx,h*.205f,h*.027f,MUTED,false)
            text(c,conditionLabel(v.weatherCode),wx,h*.242f,h*.026f,MUTED,false)

            var chipX=w*.278f
            if(showAir){v.airQualityIndex?.let{chipX=chip(c,chipX,h*.286f,"AR ${aqShort(it).uppercase(Locale("pt","BR"))}",airColor(it),h)}}
            if(showUv){v.uvIndex?.let{chipX=chip(c,chipX,h*.286f,"UV ${uvText(it)}",uvColor(it),h)}}
            if(showSun){
                val hr=Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                val s=if(hr<12) v.sunrise else v.sunset
                if(!s.isNullOrBlank()) chip(c,chipX,h*.286f,if(hr<12)"SOL $s" else "PÔR $s",ORANGE,h)
            }
        } ?: run {
            text(c,if(error)"--" else "…",wx,h*.160f,h*.085f,MUTED,false)
        }
    }

    private fun drawHeroCloud(c:Canvas,x:Float,y:Float,r:Float,code:Int){
        val rainy=code in 51..82
        val sunny=code==0
        if(sunny){
            fill.color=Color.rgb(245,185,55);c.drawCircle(x,y,r*.55f,fill)
            stroke.color=Color.rgb(245,185,55);stroke.strokeWidth=r*.07f
            for(i in 0..7){val a=Math.toRadians(i*45.0);c.drawLine(x+cos(a).toFloat()*r*.72f,y+sin(a).toFloat()*r*.72f,x+cos(a).toFloat()*r*.98f,y+sin(a).toFloat()*r*.98f,stroke)}
            return
        }
        val glow=RadialGradient(x,y,r*1.6f,Color.argb(130,105,125,160),Color.TRANSPARENT,Shader.TileMode.CLAMP)
        fill.shader=glow;c.drawCircle(x,y,r*1.6f,fill);fill.shader=null
        val shades=intArrayOf(Color.rgb(205,214,230),Color.rgb(164,178,201),Color.rgb(122,139,166),Color.rgb(82,99,127))
        val parts=arrayOf(
            floatArrayOf(-.62f,.10f,.52f),floatArrayOf(-.22f,-.25f,.66f),floatArrayOf(.25f,-.08f,.58f),floatArrayOf(.64f,.14f,.44f),floatArrayOf(.08f,.23f,.78f)
        )
        parts.forEachIndexed{i,p->
            val cx=x+p[0]*r;val cy=y+p[1]*r;val rr=p[2]*r
            val g=RadialGradient(cx-rr*.2f,cy-rr*.25f,rr,shades[i%shades.size],Color.rgb(45,55,73),Shader.TileMode.CLAMP)
            fill.shader=g;c.drawCircle(cx,cy,rr,fill);fill.shader=null
        }
        if(rainy){stroke.color=BLUE;stroke.strokeWidth=r*.07f;for(i in -1..1)c.drawLine(x+i*r*.38f,y+r*.72f,x+i*r*.38f-r*.12f,y+r*1.05f,stroke)}
    }

    private fun chip(c:Canvas,x:Float,y:Float,label:String,color:Int,h:Float):Float{
        med.textSize=h*.0215f
        val tw=med.measureText(label);val ph=h*.014f;val r=RectF(x,y-h*.030f,x+tw+ph*2f,y+h*.010f)
        fill.color=Color.argb(120,12,12,17);c.drawRoundRect(r,h*.020f,h*.020f,fill)
        stroke.strokeWidth=1f;stroke.color=Color.argb(100,Color.red(color),Color.green(color),Color.blue(color));c.drawRoundRect(r,h*.020f,h*.020f,stroke)
        fill.color=color;c.drawCircle(x+ph*.70f,y-h*.009f,h*.0055f,fill)
        text(c,label,x+ph*1.35f,y,h*.0215f,color,true)
        return r.right+wGap()
    }
    private fun wGap()=10f

    private fun drawHourlyStrip(c:Canvas,r:RectF,h:Float){
        card(c,r,h*.018f)
        val items=weather?.hourly?.take(5).orEmpty();if(items.isEmpty())return
        val cw=r.width()/5f
        items.forEachIndexed{i,v->
            val cx=r.left+cw*(i+.5f)
            if(i>0)line(c,r.left+cw*i,r.top+r.height()*.12f,r.left+cw*i,r.bottom-r.height()*.10f,Color.rgb(27,29,36),1f)
            textCenter(c,v.hour,cx,r.top+r.height()*.21f,h*.025f,MUTED,false)
            weatherIcon(c,v.weatherCode,cx,r.top+r.height()*.46f,h*.031f)
            textCenter(c,"${v.temperatureC}°",cx,r.top+r.height()*.72f,h*.031f,WHITE,true)
            if(v.rainChance>0)textCenter(c,"${v.rainChance}%",cx,r.top+r.height()*.90f,h*.022f,BLUE,false)
        }
    }

    private fun drawDailyStrip(c:Canvas,r:RectF,h:Float){
        card(c,r,h*.018f)
        val items=weather?.nextDays?.take(3).orEmpty();if(items.isEmpty())return
        val cw=r.width()/3f
        items.forEachIndexed{i,v->
            val cx=r.left+cw*(i+.5f)
            if(i>0)line(c,r.left+cw*i,r.top+r.height()*.12f,r.left+cw*i,r.bottom-r.height()*.10f,Color.rgb(27,29,36),1f)
            textCenter(c,v.dayLabel.uppercase(Locale("pt","BR")),cx,r.top+r.height()*.23f,h*.027f,WHITE,true)
            weatherIcon(c,v.weatherCode,cx,r.top+r.height()*.47f,h*.031f)
            textCenter(c,"${v.maxC}° / ${v.minC}°",cx,r.top+r.height()*.73f,h*.028f,WHITE,true)
            textCenter(c,"${v.rainChance}% chuva",cx,r.top+r.height()*.90f,h*.020f,if(v.rainChance>0)BLUE:MUTED,false)
        }
    }

    private fun drawNextCard(c:Canvas,w:Float,h:Float){
        val r=RectF(w*.022f,h*.575f,w*.598f,h*.815f)
        val gradient=LinearGradient(r.left,r.top,r.right,r.bottom,Color.rgb(8,18,31),Color.rgb(5,10,18),Shader.TileMode.CLAMP)
        fill.shader=gradient;c.drawRoundRect(r,h*.018f,h*.018f,fill);fill.shader=null
        stroke.strokeWidth=1.2f;stroke.color=Color.rgb(28,44,68);c.drawRoundRect(r,h*.018f,h*.018f,stroke)
        fill.color=PURPLE;c.drawRoundRect(RectF(r.left+h*.010f,r.top+h*.020f,r.left+h*.014f,r.bottom-h*.020f),h*.004f,h*.004f,fill)
        text(c,"PRÓXIMO COMPROMISSO",r.left+h*.038f,r.top+h*.065f,h*.020f,PURPLE,true)
        val next=events.firstOrNull{it.endMillis>now}
        if(next==null){text(c,"Sem próximos compromissos",r.left+h*.038f,r.centerY()+h*.012f,h*.034f,MUTED,false);return}
        val tx=r.left+h*.038f
        val y=r.top+r.height()*.66f
        text(c,eventTime(next),tx,y,h*.060f,WHITE,false)
        fitText(c,next.title,tx+r.width()*.29f,y,h*.044f,h*.027f,r.right-(tx+r.width()*.29f)-h*.025f,WHITE,false)
        countdownLabel(next)?.let{text(c,it,tx,r.bottom-h*.050f,h*.026f,BLUE,false)}
    }

    private fun drawAgenda(c:Canvas,w:Float,h:Float){
        val left=w*.625f;val right=w*.965f;val top=h*.285f;val bottom=h*.805f
        drawCalendarIcon(c,left,top+h*.018f,h*.018f)
        text(c,"AGENDA DE HOJE",left+h*.042f,top+h*.026f,h*.026f,MUTED,false)
        val list=events.take(5)
        if(list.isEmpty()){
            text(c,if(!calPermission)"Permita acesso ao calendário" else calStatus,left,top+h*.12f,h*.026f,MUTED,false);return
        }
        val startY=top+h*.090f;val rowH=(bottom-startY)/5f
        list.forEachIndexed{i,e->
            val y=startY+rowH*i
            if(i>0)line(c,left,y-h*.018f,right,y-h*.018f,Color.rgb(25,27,33),1f)
            val active=i==0
            if(active){fill.color=BLUE;c.drawCircle(left-h*.025f,y+h*.004f,h*.0048f,fill)}
            text(c,eventTime(e),left,y+h*.016f,h*.033f,if(active)BLUE:WHITE,false)
            fitText(c,e.title,left+w*.068f,y+h*.016f,h*.027f,h*.021f,right-(left+w*.068f),if(active)WHITE:MUTED,false)
            if(active) countdownLabel(e)?.let{text(c,it,left+w*.068f,y+h*.050f,h*.019f,BLUE,false)}
        }
    }

    private fun drawBottomStatus(c:Canvas,w:Float,h:Float){
        text(c,"↻",w*.045f,h*.905f,h*.028f,DIM,false)
        text(c,"Atualizado ${SimpleDateFormat("HH:mm",Locale("pt","BR")).format(Date(weather?.updatedAtMillis?:now))}",w*.072f,h*.904f,h*.019f,DIM,false)

        val r=RectF(w*.560f,h*.850f,w*.835f,h*.932f)
        fill.color=Color.rgb(5,7,10);c.drawRoundRect(r,h*.045f,h*.045f,fill)
        stroke.strokeWidth=1f;stroke.color=Color.rgb(25,28,36);c.drawRoundRect(r,h*.045f,h*.045f,stroke)
        weather?.let{v->
            val mid=r.centerX();line(c,mid,r.top+h*.017f,mid,r.bottom-h*.017f,Color.rgb(28,30,37),1f)
            drawThermoIcon(c,r.left+h*.027f,r.centerY(),h*.017f,GREEN)
            text(c,"${v.todayMaxC}°",r.left+h*.065f,r.top+h*.040f,h*.028f,WHITE,false)
            text(c,"Máx hoje",r.left+h*.065f,r.bottom-h*.012f,h*.014f,DIM,false)
            drawDropIcon(c,mid+h*.030f,r.centerY(),h*.017f,BLUE)
            text(c,"${v.humidityPercent?:0}%",mid+h*.065f,r.top+h*.040f,h*.028f,WHITE,false)
            text(c,"Umidade",mid+h*.065f,r.bottom-h*.012f,h*.014f,DIM,false)
        }
    }

    private fun drawCameraButton(c:Canvas,w:Float,h:Float){
        val cx=w*.895f;val cy=h*.885f;val rr=h*.060f
        val glow=RadialGradient(cx,cy,rr*1.35f,Color.argb(85,90,105,255),Color.TRANSPARENT,Shader.TileMode.CLAMP)
        fill.shader=glow;c.drawCircle(cx,cy,rr*1.35f,fill);fill.shader=null
        fill.color=Color.rgb(8,9,14);c.drawCircle(cx,cy,rr,fill)
        stroke.strokeWidth=2.2f;stroke.color=PURPLE;c.drawCircle(cx,cy,rr,stroke)
        drawCameraGlyph(c,cx,cy-h*.010f,rr*.48f,WHITE)
        textCenter(c,if(cameraLive)"AO VIVO" else "CÂMERA",cx,cy+rr*.68f,h*.015f,MUTED,true)
    }

    private fun countdownLabel(e:CalendarEvent):String?{
        if(!showCountdown||e.allDay)return null
        if(now in e.startMillis..e.endMillis)return "AGORA"
        val diff=e.startMillis-now
        if(diff<=0||diff>60*60*1000L)return null
        val mins=((diff+59999)/60000).coerceAtLeast(1)
        return "em $mins min"
    }

    private fun conditionLabel(code:Int)=when(code){0->"Céu limpo";1->"Predominantemente limpo";2->"Parcialmente nublado";3->"Nublado";45,48->"Neblina";in 51..67->"Chuva";in 71..77->"Neve";in 80..82->"Pancadas de chuva";in 95..99->"Trovoadas";else->"Condição atual"}
    private fun aqShort(aqi:Int)=when{aqi<=50->"bom";aqi<=100->"moderado";aqi<=150->"ruim";aqi<=200->"muito ruim";else->"crítico"}
    private fun airColor(aqi:Int)=when{aqi<=50->GREEN;aqi<=100->ORANGE;else->RED}
    private fun uvText(v:Double)=when{v<3->"${v.toInt()} (BAIXO)";v<6->"${v.toInt()} (MOD.)";v<8->"${v.toInt()} (ALTO)";else->"${v.toInt()} (M.ALTO)"}
    private fun uvColor(v:Double)=when{v<3->GREEN;v<6->ORANGE;else->RED}

    private fun card(c:Canvas,r:RectF,rad:Float){fill.color=Color.rgb(5,6,9);c.drawRoundRect(r,rad,rad,fill);stroke.strokeWidth=1.1f;stroke.color=Color.rgb(29,31,38);c.drawRoundRect(r,rad,rad,stroke)}
    private fun line(c:Canvas,x1:Float,y1:Float,x2:Float,y2:Float,color:Int,width:Float){stroke.strokeWidth=width;stroke.color=color;c.drawLine(x1,y1,x2,y2,stroke)}
    private fun text(c:Canvas,s:String,x:Float,y:Float,z:Float,col:Int,b:Boolean=false){val q=if(b)med else reg;q.textSize=z;q.color=col;c.drawText(s,x,y,q)}
    private fun textCenter(c:Canvas,s:String,x:Float,y:Float,z:Float,col:Int,b:Boolean=false){val q=if(b)med else reg;q.textSize=z;q.color=col;c.drawText(s,x-q.measureText(s)/2f,y,q)}
    private fun fitText(c:Canvas,s:String,x:Float,y:Float,maxSize:Float,minSize:Float,maxWidth:Float,col:Int,b:Boolean=false){val q=if(b)med else reg;var z=maxSize;q.textSize=z;while(q.measureText(s)>maxWidth&&z>minSize){z-=1f;q.textSize=z};q.color=col;c.drawText(s,x,y,q)}

    private fun battery(c:Canvas,x:Float,y:Float,s:Float){val bw=s*1.55f;val bh=s*.68f;stroke.strokeWidth=1.6f;stroke.color=if(battery.charging)GREEN else MUTED;c.drawRoundRect(RectF(x,y-bh,x+bw,y),bh*.22f,bh*.22f,stroke);fill.color=stroke.color;val f=battery.percent.coerceIn(0,100)/100f;c.drawRoundRect(RectF(x+3,y-bh+3,x+3+(bw-6)*f,y-3),2f,2f,fill);text(c,"${battery.percent}%",x+bw+s*.45f,y,s*.95f,MUTED,false)}

    private fun weatherIcon(c:Canvas,code:Int,x:Float,y:Float,r:Float){
        stroke.strokeWidth=(r*.10f).coerceAtLeast(2f);stroke.strokeCap=Paint.Cap.ROUND;stroke.color=ICON
        if(code==0){c.drawCircle(x,y,r*.46f,stroke);for(i in 0..7){val a=Math.toRadians(i*45.0);c.drawLine(x+cos(a).toFloat()*r*.67f,y+sin(a).toFloat()*r*.67f,x+cos(a).toFloat()*r*.90f,y+sin(a).toFloat()*r*.90f,stroke)};return}
        if(code in 1..3)c.drawCircle(x-r*.45f,y-r*.35f,r*.32f,stroke)
        val q=Path();q.moveTo(x-r*.78f,y+r*.22f);q.cubicTo(x-r*.82f,y-r*.20f,x-r*.38f,y-r*.32f,x-r*.20f,y-r*.18f);q.cubicTo(x,y-r*.68f,x+r*.56f,y-r*.50f,x+r*.56f,y-r*.10f);q.cubicTo(x+r*.90f,y-r*.08f,x+r*.92f,y+r*.36f,x+r*.56f,y+r*.36f);q.lineTo(x-r*.56f,y+r*.36f);c.drawPath(q,stroke)
        if(code in 51..82){stroke.color=BLUE;for(i in -1..1)c.drawLine(x+i*r*.30f,y+r*.55f,x+i*r*.30f-r*.10f,y+r*.90f,stroke)}
    }

    private fun drawCalendarIcon(c:Canvas,x:Float,y:Float,s:Float){stroke.color=MUTED;stroke.strokeWidth=2f;c.drawRoundRect(RectF(x,y-s*.72f,x+s*1.1f,y+s*.28f),s*.12f,s*.12f,stroke);c.drawLine(x,y-s*.40f,x+s*1.1f,y-s*.40f,stroke);c.drawLine(x+s*.25f,y-s*.88f,x+s*.25f,y-s*.57f,stroke);c.drawLine(x+s*.82f,y-s*.88f,x+s*.82f,y-s*.57f,stroke)}
    private fun drawCameraGlyph(c:Canvas,x:Float,y:Float,s:Float,color:Int){stroke.color=color;stroke.strokeWidth=(s*.11f).coerceAtLeast(2f);val body=RectF(x-s*.80f,y-s*.45f,x+s*.80f,y+s*.55f);c.drawRoundRect(body,s*.15f,s*.15f,stroke);c.drawCircle(x,y+s*.04f,s*.32f,stroke);c.drawRoundRect(RectF(x-s*.25f,y-s*.64f,x+s*.25f,y-s*.42f),s*.08f,s*.08f,stroke)}
    private fun drawDropIcon(c:Canvas,x:Float,y:Float,s:Float,color:Int){stroke.color=color;stroke.strokeWidth=2f;val p=Path();p.moveTo(x,y-s);p.cubicTo(x-s*.75f,y-s*.10f,x-s*.55f,y+s*.70f,x,y+s*.80f);p.cubicTo(x+s*.55f,y+s*.70f,x+s*.75f,y-s*.10f,x,y-s);c.drawPath(p,stroke)}
    private fun drawThermoIcon(c:Canvas,x:Float,y:Float,s:Float,color:Int){stroke.color=color;stroke.strokeWidth=2f;c.drawCircle(x,y+s*.42f,s*.28f,stroke);c.drawRoundRect(RectF(x-s*.11f,y-s*.75f,x+s*.11f,y+s*.38f),s*.10f,s*.10f,stroke)}

    private fun time()=SimpleDateFormat("HH:mm",Locale("pt","BR")).format(Date(now))
    private fun date()=SimpleDateFormat("EEEE, d 'de' MMMM",Locale("pt","BR")).format(Date(now)).replaceFirstChar{it.uppercase()}
    private fun eventTime(e:CalendarEvent)=if(e.allDay)"Dia todo" else SimpleDateFormat("HH:mm",Locale("pt","BR")).format(Date(e.startMillis))

    override fun onTouchEvent(e:MotionEvent):Boolean{
        when(e.action){
            MotionEvent.ACTION_DOWN->{downAt=System.currentTimeMillis();downX=e.x;downY=e.y;return true}
            MotionEvent.ACTION_UP->{
                val held=System.currentTimeMillis()-downAt
                val w=width.toFloat();val h=height.toFloat()
                val cameraHit=((e.x-w*.895f)*(e.x-w*.895f)+(e.y-h*.885f)*(e.y-h*.885f)) < (h*.085f)*(h*.085f)
                if(cameraHit){if(held>=700)onCameraSettingsRequested?.invoke()else onCameraRequested?.invoke();performClick();return true}
                if(held>=700 && downX<w*.30f && downY<h*.30f){onSettingsRequested?.invoke();performClick();return true}
                if(e.x<w*.20f&&e.y>h*.84f){onRefreshRequested?.invoke();performClick();return true}
                return true
            }
        }
        return true
    }
    override fun performClick():Boolean{super.performClick();return true}

    companion object{
        val WHITE=Color.rgb(245,245,248);val MUTED=Color.rgb(177,177,188);val DIM=Color.rgb(105,106,118)
        val BLUE=Color.rgb(103,138,255);val PURPLE=Color.rgb(112,105,255);val GREEN=Color.rgb(92,204,126)
        val ORANGE=Color.rgb(245,177,58);val RED=Color.rgb(238,77,79);val ICON=Color.rgb(223,225,232)
    }
}
