package com.deskdock.app.ui

import android.graphics.*
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.sin

object WeatherArt {
    private const val W=1000
    private const val H=560

    private fun isDayNow():Boolean=Calendar.getInstance().get(Calendar.HOUR_OF_DAY) in 6..17

    val clear:Bitmap? get()=scene(if(isDayNow()) Mode.DAY_CLEAR else Mode.NIGHT_CLEAR)
    val partly:Bitmap? get()=scene(if(isDayNow()) Mode.DAY_PARTLY else Mode.NIGHT_PARTLY)
    val cloudy:Bitmap? get()=scene(if(isDayNow()) Mode.DAY_CLOUDY else Mode.NIGHT_CLOUDY)
    val rain:Bitmap? get()=scene(if(isDayNow()) Mode.DAY_RAIN else Mode.NIGHT_RAIN)

    private enum class Mode{DAY_CLEAR,DAY_PARTLY,DAY_CLOUDY,DAY_RAIN,NIGHT_CLEAR,NIGHT_PARTLY,NIGHT_CLOUDY,NIGHT_RAIN}
    private val cache=mutableMapOf<Mode,Bitmap>()

    private fun scene(mode:Mode):Bitmap=cache.getOrPut(mode){
        val b=Bitmap.createBitmap(W,H,Bitmap.Config.ARGB_8888)
        val c=Canvas(b)
        val p=Paint(Paint.ANTI_ALIAS_FLAG)
        val isDay=mode.name.startsWith("DAY")
        val isRain=mode.name.endsWith("RAIN")
        val isCloudy=mode.name.endsWith("CLOUDY")
        val isPartly=mode.name.endsWith("PARTLY")

        val top=if(isDay) Color.rgb(10,33,62) else Color.rgb(2,8,20)
        val mid=if(isDay) Color.rgb(23,58,94) else Color.rgb(6,18,38)
        val bottom=if(isDay) Color.rgb(8,16,27) else Color.rgb(1,4,11)
        p.shader=LinearGradient(0f,0f,0f,H.toFloat(),intArrayOf(top,mid,bottom),floatArrayOf(0f,.55f,1f),Shader.TileMode.CLAMP)
        c.drawRect(0f,0f,W.toFloat(),H.toFloat(),p);p.shader=null

        if(isDay){
            val sx=if(isPartly||isCloudy) 520f else 600f;val sy=165f
            p.shader=RadialGradient(sx,sy,150f,intArrayOf(Color.argb(235,255,196,82),Color.argb(80,255,161,42),Color.TRANSPARENT),floatArrayOf(0f,.35f,1f),Shader.TileMode.CLAMP)
            c.drawCircle(sx,sy,150f,p);p.shader=null
            p.color=Color.rgb(255,225,145);c.drawCircle(sx,sy,28f,p)
        }else{
            val mx=590f;val my=145f
            p.shader=RadialGradient(mx,my,130f,intArrayOf(Color.argb(190,185,210,255),Color.argb(45,105,145,220),Color.TRANSPARENT),floatArrayOf(0f,.35f,1f),Shader.TileMode.CLAMP)
            c.drawCircle(mx,my,130f,p);p.shader=null
            p.color=Color.rgb(224,232,246);c.drawCircle(mx,my,24f,p)
            p.color=Color.argb(175,230,238,255)
            for(i in 0..24){val x=(37*i%W).toFloat();val y=(19*i%210+24).toFloat();c.drawCircle(x,y,if(i%3==0)2.2f else 1.2f,p)}
        }

        if(isPartly||isCloudy||isRain){
            val cloudCount=if(isCloudy||isRain) 12 else 7
            for(i in 0 until cloudCount){
                val baseX=250f+(i%6)*115f-(if(i%2==0)35f else 0f)
                val baseY=150f+(i/6)*85f+(i%3)*14f
                val scale=if(isCloudy||isRain)1.18f else .92f
                cloud(c,p,baseX,baseY,scale,isDay,isRain)
            }
        }

        if(isRain){
            p.strokeWidth=2.2f;p.color=Color.argb(if(isDay)105 else 125,115,174,235)
            for(i in 0..65){val x=(i*41%W).toFloat();val y=(205+i*29%(H-215)).toFloat();c.drawLine(x,y,x-11f,y+31f,p)}
            p.shader=LinearGradient(0f,H*.72f,0f,H.toFloat(),Color.TRANSPARENT,Color.argb(150,15,49,79),Shader.TileMode.CLAMP)
            c.drawRect(0f,H*.72f,W.toFloat(),H.toFloat(),p);p.shader=null
        }

        p.shader=LinearGradient(0f,H*.66f,0f,H.toFloat(),Color.TRANSPARENT,Color.BLACK,Shader.TileMode.CLAMP)
        c.drawRect(0f,H*.60f,W.toFloat(),H.toFloat(),p);p.shader=null
        b
    }

    private fun cloud(c:Canvas,p:Paint,x:Float,y:Float,s:Float,isDay:Boolean,rain:Boolean){
        val light=if(isDay&&!rain) Color.rgb(132,150,176) else Color.rgb(50,67,93)
        val dark=if(isDay&&!rain) Color.rgb(43,58,80) else Color.rgb(19,32,54)
        val parts=arrayOf(floatArrayOf(-72f,20f,58f),floatArrayOf(-30f,-12f,74f),floatArrayOf(25f,0f,67f),floatArrayOf(72f,24f,49f),floatArrayOf(0f,34f,88f))
        for((idx,q) in parts.withIndex()){
            val cx=x+q[0]*s;val cy=y+q[1]*s;val r=q[2]*s
            p.shader=RadialGradient(cx-r*.22f,cy-r*.28f,r,if(idx%2==0)light else Color.rgb((Color.red(light)*.9).toInt(),(Color.green(light)*.9).toInt(),(Color.blue(light)*.9).toInt()),dark,Shader.TileMode.CLAMP)
            c.drawCircle(cx,cy,r,p);p.shader=null
        }
    }
}
