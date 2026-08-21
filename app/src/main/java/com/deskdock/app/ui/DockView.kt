package com.deskdock.app.ui

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import com.deskdock.app.model.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*
import kotlin.random.Random

class DockView(context: Context): View(context) {
    var onRefreshRequested:(()->Unit)?=null
    var onSettingsRequested:(()->Unit)?=null
    var onCameraRequested:(()->Unit)?=null
    var onCameraSettingsRequested:(()->Unit)?=null

    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    private val line=Paint(Paint.ANTI_ALIAS_FLAG)
    private var now=System.currentTimeMillis()
    private var weather:WeatherSnapshot?=null
    private var battery=BatteryInfo(0,false)
    private var events:List<CalendarEvent> = emptyList()
    private var permission=true
    private var sx=0f
    private var sy=0f
    private var downAt=0L
    private var downX=0f
    private var downY=0f

    fun setNow(v:Long){now=v;invalidate()}
    fun setWeather(v:WeatherSnapshot){weather=v;invalidate()}
    fun setWeatherLoading(v:Boolean){}
    fun setWeatherError(){invalidate()}
    fun setBattery(v:BatteryInfo){battery=v;invalidate()}
    fun setEvents(v:List<CalendarEvent>){events=v;invalidate()}
    fun setLocationLabel(v:String){}
    fun setCalendarPermission(v:Boolean){permission=v;invalidate()}
    fun setCalendarStatus(v:String){}
    fun setCameraLive(v:Boolean){}
    fun setInfoOptions(uv:Boolean,air:Boolean,sun:Boolean,countdown:Boolean){}
    fun setForecastModeDaily(v:Boolean){}
    fun shiftForBurnInProtection(){sx=Random.nextInt(-3,4).toFloat();sy=Random.nextInt(-2,3).toFloat();invalidate()}

    private fun txt(c:Canvas,s:String,x:Float,y:Float,size:Float,color:Int=Color.WHITE,bold:Boolean=false,center:Boolean=false){
        p.style=Paint.Style.FILL
        p.color=color
        p.textSize=size
        p.typeface=Typeface.create("sans-serif",if(bold)Typeface.BOLD else Typeface.NORMAL)
        p.textAlign=if(center)Paint.Align.CENTER else Paint.Align.LEFT
        c.drawText(s,x,y,p)
    }
    private fun rule(c:Canvas,x1:Float,y1:Float,x2:Float,y2:Float,w:Float){
        line.color=Color.rgb(0,54,82);line.strokeWidth=w;line.style=Paint.Style.STROKE
        c.drawLine(x1,y1,x2,y2,line)
    }

    override fun onDraw(c:Canvas){
        super.onDraw(c)
        c.drawColor(Color.BLACK)
        c.save()
        c.translate(sx,sy)
        val w=width.toFloat();val h=height.toFloat()
        if(w<1||h<1){c.restore();return}
        // A referência enviada tem a mesma proporção do Poco F1 em paisagem.
        // Base visual: 1215 x 585 -> escala uniforme para 2246 x 1080.
        val X=w/1215f
        val S=h/585f
        rule(c,287*X,32*S,287*X,568*S,3*S)
        rule(c,955*X,32*S,955*X,568*S,3*S)
        drawLeft(c,X,S)
        drawCenter(c,X,S)
        drawRight(c,X,S)
        c.restore()
    }

    private fun drawLeft(c:Canvas,X:Float,S:Float){
        txt(c,SimpleDateFormat("HH:mm",Locale("pt","BR")).format(Date(now)),43*X,92*S,74*S,Color.WHITE,true)
        txt(c,SimpleDateFormat("EEEE, d 'de' MMMM",Locale("pt","BR")).format(Date(now)).replaceFirstChar{it.uppercase()},20*X,132*S,22*S,GRAY,true)
        weather?.let{v->
            drawWeatherIcon(c,82*X,212*S,39*S,v.weatherCode)
            txt(c,"${v.temperatureC}°",155*X,227*S,48*S)
            txt(c,"Sens. ${v.feelsLikeC}°",95*X,287*S,19*S)
            txt(c,condition(v.weatherCode),86*X,313*S,19*S)
            txt(c,"${v.todayMinC}°",118*X,370*S,24*S,BLUE,true)
            txt(c,"Mín. hoje",96*X,397*S,18*S)
            txt(c,"${v.todayMaxC}°",118*X,451*S,24*S,Color.RED,true)
            txt(c,"Máx. hoje",95*X,479*S,18*S)
            txt(c,"${v.humidityPercent?:0}%",113*X,534*S,24*S,Color.rgb(126,194,235),true)
            txt(c,"Umidade",96*X,562*S,18*S)
        }
    }

    private fun drawCenter(c:Canvas,X:Float,S:Float){
        val v=weather?:return
        txt(c,"Próximas Horas",620*X,51*S,25*S,Color.WHITE,true,true)
        v.hourly.take(5).forEachIndexed{i,a->
            val cx=(365+i*132)*X
            drawWeatherIcon(c,cx,88*S,21*S,a.weatherCode)
            txt(c,a.hour,cx+25*X,98*S,22*S,Color.WHITE,true)
            txt(c,"${a.temperatureC}°",cx+28*X,130*S,22*S)
        }
        rule(c,341*X,153*S,899*X,153*S,3*S)

        txt(c,"Próximos Dias",620*X,188*S,25*S,Color.WHITE,true,true)
        v.nextDays.take(3).forEachIndexed{i,d->
            val cx=(405+i*190)*X
            drawWeatherIcon(c,cx-45*X,239*S,20*S,d.weatherCode)
            txt(c,d.dayLabel.uppercase(Locale("pt","BR")),cx,232*S,24*S,Color.WHITE,true)
            txt(c,"${d.maxC}° / ${d.minC}°",cx-23*X,267*S,22*S)
            txt(c,"${d.rainChance}% chuva",cx-15*X,287*S,16*S,BLUE)
        }
        rule(c,341*X,307*S,899*X,307*S,3*S)

        txt(c,"Próximo Compromisso",620*X,350*S,25*S,Color.WHITE,true,true)
        drawEvents(c,X,S)
    }

    private fun drawEvents(c:Canvas,X:Float,S:Float){
        val list=events.filter{it.endMillis>now}.take(5)
        if(list.isEmpty()){
            txt(c,if(permission)"Sem mais compromissos hoje" else "Permita acesso ao calendário",317*X,401*S,22*S,GRAY)
            return
        }
        list.forEachIndexed{i,e->
            val y=(397+i*30)*S
            val active=i==0
            val count=if(active)countdown(e) else null
            if(active){
                p.color=Color.rgb(7,45,107);c.drawRect(306*X,369*S,932*X,435*S,p)
                p.color=Color.rgb(58,190,255);c.drawRect(306*X,369*S,311*X,435*S,p)
            }
            txt(c,eventTime(e),317*X,y,21*S,Color.WHITE,true)
            txt(c,e.title,412*X,y,21*S,Color.WHITE)
            if(count!=null)txt(c,count,317*X,y+27*S,18*S,Color.rgb(117,202,255))
        }
    }

    private fun drawRight(c:Canvas,X:Float,S:Float){
        val r=RectF(975*X,32*S,1190*X,293*S)
        p.color=Color.rgb(5,43,113)
        c.drawRoundRect(r,38*S,38*S,p)
        drawCamera(c,1082*X,111*S,42*S)
        txt(c,"Mostrar",1082*X,205*S,25*S,Color.WHITE,true,true)
        txt(c,"Câmera Rua",1082*X,237*S,25*S,Color.WHITE,true,true)
        weather?.let{v->
            val aq=v.airQualityIndex?:0
            val ac=if(aq>100)Color.RED else Color.rgb(70,205,105)
            p.color=ac;c.drawCircle(1033*X,344*S,11*S,p)
            txt(c,if(aq>100)"Ar ruim" else "Ar bom",1055*X,353*S,24*S,ac,true)
            txt(c,"Nascer do Sol",1082*X,410*S,24*S,Color.YELLOW,true,true)
            txt(c,v.sunrise?:"--:--",1082*X,438*S,19*S,Color.WHITE,true,true)
            txt(c,"Pôr do Sol",1082*X,492*S,24*S,Color.rgb(53,154,221),true,true)
            txt(c,v.sunset?:"--:--",1082*X,520*S,19*S,Color.WHITE,true,true)
        }
    }

    private fun drawCamera(c:Canvas,x:Float,y:Float,r:Float){
        line.color=Color.WHITE;line.strokeWidth=4f;line.style=Paint.Style.STROKE
        val rr=RectF(x-r,y-r*.55f,x+r,y+r*.55f)
        c.drawRoundRect(rr,8f,8f,line);c.drawCircle(x,y,r*.38f,line);c.drawRect(x-r*.32f,y-r*.72f,x+r*.15f,y-r*.55f,line)
        line.style=Paint.Style.FILL
    }
    private fun drawWeatherIcon(c:Canvas,x:Float,y:Float,r:Float,code:Int){
        if(code==0){sun(c,x,y,r);return}
        if(code>=51){cloud(c,x,y,r,Color.rgb(35,190,238));line.color=Color.YELLOW;line.strokeWidth=2.5f;for(i in -1..1)c.drawLine(x+i*r*.45f,y+r*.65f,x+i*r*.45f-r*.13f,y+r*1.05f,line);return}
        sun(c,x-r*.42f,y-r*.25f,r*.55f);cloud(c,x+r*.15f,y+r*.18f,r*.82f,Color.rgb(40,188,235))
    }
    private fun sun(c:Canvas,x:Float,y:Float,r:Float){
        p.color=Color.YELLOW;c.drawCircle(x,y,r*.55f,p);line.color=Color.YELLOW;line.strokeWidth=4f
        for(i in 0..7){val a=i*Math.PI/4;c.drawLine(x+cos(a).toFloat()*r*.72f,y+sin(a).toFloat()*r*.72f,x+cos(a).toFloat()*r,y+sin(a).toFloat()*r,line)}
    }
    private fun cloud(c:Canvas,x:Float,y:Float,r:Float,col:Int){
        p.color=col;c.drawCircle(x-r*.28f,y,r*.35f,p);c.drawCircle(x+r*.05f,y-r*.18f,r*.48f,p);c.drawCircle(x+r*.42f,y,r*.32f,p);c.drawRoundRect(RectF(x-r*.55f,y,x+r*.7f,y+r*.35f),r*.15f,r*.15f,p)
    }
    private fun condition(code:Int)=when{code==0->"Ensolarado";code in 1..3->"Nublado";code>=51->"Chuva";else->"Nublado"}
    private fun eventTime(e:CalendarEvent)=if(e.allDay)"Dia todo" else SimpleDateFormat("HH:mm",Locale("pt","BR")).format(Date(e.startMillis))
    private fun countdown(e:CalendarEvent):String?{
        if(e.startMillis<=now)return "AGORA"
        val m=((e.startMillis-now+59999)/60000).toInt()
        return if(m in 1..60)"Inicia em $m minutos" else null
    }

    override fun onTouchEvent(e:MotionEvent):Boolean{
        when(e.action){
            MotionEvent.ACTION_DOWN->{downAt=System.currentTimeMillis();downX=e.x;downY=e.y;return true}
            MotionEvent.ACTION_UP->{
                val held=System.currentTimeMillis()-downAt
                val X=width/1215f;val S=height/585f
                if(e.x>955*X&&e.y<310*S){
                    if(held>=700)onCameraSettingsRequested?.invoke() else onCameraRequested?.invoke()
                    performClick();return true
                }
                if(held>=700&&downX<300*X&&downY<180*S){onSettingsRequested?.invoke();performClick();return true}
            }
        }
        return true
    }
    override fun performClick():Boolean{super.performClick();return true}

    companion object{
        val GRAY=Color.rgb(150,150,155)
        val BLUE=Color.rgb(86,173,235)
    }
}
