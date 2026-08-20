package com.deskdock.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.location.Geocoder
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.deskdock.app.data.CalendarRepository
import com.deskdock.app.data.LocationRepository
import com.deskdock.app.data.WeatherRepository
import com.deskdock.app.model.BatteryInfo
import com.deskdock.app.model.CalendarEvent
import com.deskdock.app.model.WeatherSnapshot
import com.deskdock.app.ui.DockView
import java.util.Calendar
import java.util.Locale
import kotlin.math.min

@OptIn(UnstableApi::class)
class MainActivity : Activity() {
    private lateinit var dockView: DockView
    private lateinit var root: FrameLayout
    private lateinit var cameraFrame: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var cameraStatus: TextView
    private lateinit var cameraIdleIcon: View
    private lateinit var cameraContext: TextView
    private lateinit var cameraClose: View
    private lateinit var calendarRepo: CalendarRepository
    private lateinit var locationRepo: LocationRepository
    private val weatherRepo = WeatherRepository()
    private val handler = Handler(Looper.getMainLooper())

    private var player: ExoPlayer? = null
    private var cameraActive = false
    private var cameraUsingTcp = false
    private var dailyForecastVisible = false
    private var contextIndex = 0
    private var latestWeather: WeatherSnapshot? = null
    private var latestEvents: List<CalendarEvent> = emptyList()
    private val prefs by lazy { getSharedPreferences("deskdock", MODE_PRIVATE) }

    private val tick = object : Runnable {
        override fun run() { dockView.setNow(System.currentTimeMillis()); handler.postDelayed(this, 1000) }
    }
    private val shift = object : Runnable {
        override fun run() { dockView.shiftForBurnInProtection(); handler.postDelayed(this, 90_000) }
    }
    private val refresh = object : Runnable {
        override fun run() { refreshAll(); handler.postDelayed(this, 30 * 60_000L) }
    }
    private val forecastSwitch = object : Runnable {
        override fun run() {
            dailyForecastVisible = !dailyForecastVisible
            dockView.setForecastModeDaily(dailyForecastVisible)
            val seconds = if (dailyForecastVisible) prefs.getInt("daily_seconds", 30) else prefs.getInt("hourly_seconds", 60)
            handler.postDelayed(this, seconds.coerceIn(10, 300) * 1000L)
        }
    }
    private val contextSwitch = object : Runnable {
        override fun run() {
            if (!cameraActive && prefs.getBoolean("context_rotation", true)) rotateCameraContext()
            handler.postDelayed(this, 15_000L)
        }
    }
    private val cameraAutoOff = Runnable { if (cameraActive) stopCamera(true) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersive()
        calendarRepo = CalendarRepository(this)
        locationRepo = LocationRepository(this)

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        dockView = DockView(this).apply {
            onRefreshRequested = { refreshAll() }
            onSettingsRequested = { showSettingsDialog() }
        }
        root.addView(dockView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        createCameraOverlay()
        setContentView(root)
        root.post { positionCameraOverlay() }

        applyPreferences()
        requestNeededPermissions()
        refreshAll()
        handler.post(tick)
        handler.post(shift)
        handler.post(refresh)
        handler.postDelayed(forecastSwitch, prefs.getInt("hourly_seconds", 60).coerceIn(10,300)*1000L)
        handler.postDelayed(contextSwitch, 12_000L)
        showCameraIdle()
    }

    override fun onResume() { super.onResume(); enterImmersive(); refreshCalendar() }
    override fun onPause() { if (cameraActive) stopCamera(true); super.onPause() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); releasePlayer(); super.onDestroy() }

    private fun createCameraOverlay() {
        cameraFrame = FrameLayout(this).apply {
            background = GradientDrawable().apply { setColor(Color.BLACK); cornerRadius = 18f }
            clipToOutline = true
            setOnLongClickListener { showCameraConfigDialog(); true }
        }
        playerView = PlayerView(this).apply { useController=false;resizeMode=AspectRatioFrameLayout.RESIZE_MODE_FIT;setShutterBackgroundColor(Color.BLACK);visibility=View.INVISIBLE }
        cameraStatus = TextView(this).apply {
            setTextColor(Color.rgb(232,232,238));textSize=18f;gravity=Gravity.CENTER;setPadding(18,18,18,18);background=idleCameraBackground()
            setOnClickListener { if(!cameraActive) openCameraSession() }
            setOnLongClickListener { showCameraConfigDialog(); true }
        }
        cameraIdleIcon = object:View(this){
            private val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.rgb(228,231,238);style=Paint.Style.STROKE;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
            override fun onDraw(canvas:Canvas){super.onDraw(canvas);val d=min(width,height).toFloat();val cx=width/2f;val cy=height/2f-d*.08f;p.strokeWidth=(d*.050f).coerceAtLeast(2.5f);val bodyW=d*.50f;val bodyH=d*.34f;val body=RectF(cx-bodyW/2f,cy-bodyH/2f+d*.035f,cx+bodyW/2f,cy+bodyH/2f+d*.035f);canvas.drawRoundRect(body,d*.065f,d*.065f,p);val bumpW=d*.18f;val bumpH=d*.085f;val bump=RectF(cx-bumpW/2f,body.top-bumpH*.70f,cx+bumpW/2f,body.top+bumpH*.45f);canvas.drawRoundRect(bump,d*.035f,d*.035f,p);canvas.drawCircle(cx,body.centerY(),d*.095f,p)}
        }.apply{contentDescription="Abrir câmera";setOnClickListener{if(!cameraActive)openCameraSession()};setOnLongClickListener{showCameraConfigDialog();true}}
        cameraContext = TextView(this).apply {
            setTextColor(Color.rgb(160,165,178));textSize=15f;gravity=Gravity.CENTER;includeFontPadding=false;alpha=.95f
            setPadding(12,0,12,8);setOnClickListener{if(!cameraActive)openCameraSession()}
        }
        cameraClose = object:View(this){
            private val xPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE;strokeCap=Paint.Cap.ROUND}
            override fun onDraw(canvas:Canvas){super.onDraw(canvas);val d=min(width,height).toFloat();xPaint.strokeWidth=(d*.075f).coerceAtLeast(2.5f);val inset=d*.32f;canvas.drawLine(inset,inset,width-inset,height-inset,xPaint);canvas.drawLine(width-inset,inset,inset,height-inset,xPaint)}
        }.apply{background=GradientDrawable().apply{setColor(Color.argb(210,15,15,18));shape=GradientDrawable.OVAL;setStroke(1,Color.rgb(75,75,82))};visibility=View.GONE;setOnClickListener{stopCamera(true)}}

        cameraFrame.addView(playerView,FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,FrameLayout.LayoutParams.MATCH_PARENT))
        cameraFrame.addView(cameraStatus,FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,FrameLayout.LayoutParams.MATCH_PARENT).apply{setMargins(8,8,8,8)})
        cameraFrame.addView(cameraIdleIcon,FrameLayout.LayoutParams(126,126,Gravity.CENTER))
        cameraFrame.addView(cameraContext,FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,56,Gravity.BOTTOM))
        cameraFrame.addView(cameraClose,FrameLayout.LayoutParams(54,54,Gravity.TOP or Gravity.END).apply{topMargin=10;rightMargin=10})
        root.addView(cameraFrame)
    }

    private fun idleCameraBackground()=GradientDrawable().apply{setColor(Color.rgb(10,17,29));cornerRadius=24f;setStroke(2,Color.rgb(38,58,84))}

    private fun positionCameraOverlay(){
        val w=root.width.toFloat();val h=root.height.toFloat();if(w<=0||h<=0)return
        val cardLeft=w*.012f;val cardTop=h*.325f;val cardWidth=w*.390f;val cardBottom=h*.965f;val cardHeight=cardBottom-cardTop
        val left=cardLeft+cardWidth*.035f;val right=cardLeft+cardWidth-cardWidth*.035f;val videoWidth=right-left;val videoHeight=videoWidth*9f/16f
        val availableTop=cardTop+cardHeight*.18f;val availableBottom=cardBottom-cardHeight*.055f;val availableHeight=availableBottom-availableTop;val finalHeight=minOf(videoHeight,availableHeight);val top=availableTop+(availableHeight-finalHeight)/2f
        cameraFrame.layoutParams=FrameLayout.LayoutParams(videoWidth.toInt(),finalHeight.toInt()).apply{leftMargin=left.toInt();topMargin=top.toInt()}
    }

    private fun showCameraIdle(){
        cameraActive=false;cameraUsingTcp=false;dockView.setCameraLive(false);playerView.visibility=View.INVISIBLE;cameraClose.visibility=View.GONE;cameraStatus.visibility=View.VISIBLE;cameraStatus.background=idleCameraBackground();cameraStatus.text="";cameraIdleIcon.visibility=View.VISIBLE;cameraContext.visibility=if(prefs.getBoolean("context_rotation",true))View.VISIBLE else View.GONE;updateCameraContextImmediate()
        cameraIdleIcon.contentDescription=if(!prefs.getString("camera_rtsp_url",null).isNullOrBlank())"Abrir câmera" else "Configurar câmera"
    }

    private fun buildContextItems():List<String>{
        val items=mutableListOf<String>()
        val next=latestEvents.firstOrNull{!it.allDay && it.startMillis>System.currentTimeMillis()}
        next?.let{val mins=(it.startMillis-System.currentTimeMillis())/60000;if(mins in 0..120)items+="Próximo compromisso · ${it.title.take(24)} · em ${mins.coerceAtLeast(1)} min"}
        latestWeather?.let{w->
            if(prefs.getBoolean("show_air",true))w.airQualityIndex?.let{items+="Qualidade do ar · ${aqLabel(it)} · AQI $it"}
            if(prefs.getBoolean("show_sun",true)){val hr=Calendar.getInstance().get(Calendar.HOUR_OF_DAY);if(hr<12)w.sunrise?.let{items+="Nascer do sol · $it"}else w.sunset?.let{items+="Pôr do sol · $it"}}
        }
        return items
    }
    private fun updateCameraContextImmediate(){val items=buildContextItems();cameraContext.text=if(items.isEmpty())"Toque para abrir"else items[contextIndex.mod(items.size)]}
    private fun rotateCameraContext(){val items=buildContextItems();if(items.isEmpty()){cameraContext.text="Toque para abrir";return};contextIndex=(contextIndex+1)%items.size;cameraContext.animate().alpha(0f).setDuration(500).withEndAction{cameraContext.text=items[contextIndex];cameraContext.animate().alpha(.95f).setDuration(800).start()}.start()}
    private fun aqLabel(aqi:Int)=when{aqi<=50->"boa";aqi<=100->"moderada";aqi<=150->"ruim";aqi<=200->"muito ruim";else->"crítica"}

    private fun showCameraConfigDialog(){
        val input=EditText(this).apply{inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI;setSingleLine(true);setText(prefs.getString("camera_rtsp_url","rtsp://admin:@192.168.0.138:554/cam/realmonitor?channel=1&subtype=1"));setSelection(text.length)}
        AlertDialog.Builder(this).setTitle("Câmera RTSP").setMessage("Cole a URL RTSP completa. Ela fica salva somente neste aparelho.").setView(input).setPositiveButton("Salvar"){_,_->stopCamera(false);prefs.edit().putString("camera_rtsp_url",input.text.toString().trim()).apply();showCameraIdle()}.setNegativeButton("Cancelar",null).show()
    }

    private fun showSettingsDialog(){
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(42,12,42,0)}
        fun check(label:String,key:String,def:Boolean):CheckBox=CheckBox(this).apply{text=label;isChecked=prefs.getBoolean(key,def);box.addView(this)}
        val uv=check("Mostrar índice UV","show_uv",true);val air=check("Mostrar qualidade do ar","show_air",true);val sun=check("Mostrar nascer/pôr do sol","show_sun",true);val countdown=check("Contagem para próximo compromisso","show_countdown",true);val contextual=check("Conteúdo contextual na câmera","context_rotation",true)
        fun numeric(label:String,key:String,def:Int):EditText{box.addView(TextView(this).apply{text=label;setPadding(0,10,0,2)});return EditText(this).apply{inputType=InputType.TYPE_CLASS_NUMBER;setText(prefs.getInt(key,def).toString());setSingleLine(true);box.addView(this)}}
        val cam=numeric("Tempo da câmera (segundos)","camera_seconds",30);val hrs=numeric("Próximas horas (segundos)","hourly_seconds",60);val days=numeric("Próximos dias (segundos)","daily_seconds",30)
        AlertDialog.Builder(this).setTitle("DeskDock · Configurações").setView(box).setPositiveButton("Salvar"){_,_->
            prefs.edit().putBoolean("show_uv",uv.isChecked).putBoolean("show_air",air.isChecked).putBoolean("show_sun",sun.isChecked).putBoolean("show_countdown",countdown.isChecked).putBoolean("context_rotation",contextual.isChecked)
                .putInt("camera_seconds",cam.text.toString().toIntOrNull()?.coerceIn(10,300)?:30).putInt("hourly_seconds",hrs.text.toString().toIntOrNull()?.coerceIn(10,300)?:60).putInt("daily_seconds",days.text.toString().toIntOrNull()?.coerceIn(10,300)?:30).apply()
            applyPreferences();showCameraIdle();handler.removeCallbacks(forecastSwitch);handler.postDelayed(forecastSwitch,prefs.getInt("hourly_seconds",60)*1000L)
        }.setNegativeButton("Cancelar",null).show()
    }

    private fun applyPreferences(){dockView.setInfoOptions(prefs.getBoolean("show_uv",true),prefs.getBoolean("show_air",true),prefs.getBoolean("show_sun",true),prefs.getBoolean("show_countdown",true))}

    private fun openCameraSession(){
        val url=prefs.getString("camera_rtsp_url",null)?.trim().orEmpty();if(url.isBlank()){showCameraConfigDialog();return}
        cameraActive=true;cameraUsingTcp=false;dockView.setCameraLive(false);cameraIdleIcon.visibility=View.GONE;cameraContext.visibility=View.GONE;cameraClose.visibility=View.VISIBLE;handler.removeCallbacks(cameraAutoOff);handler.postDelayed(cameraAutoOff,prefs.getInt("camera_seconds",30).coerceIn(10,300)*1000L);startCameraTransport(false)
    }

    private fun startCameraTransport(forceTcp:Boolean){
        val url=prefs.getString("camera_rtsp_url",null)?.trim().orEmpty();if(url.isBlank()||!cameraActive)return
        releasePlayer();cameraUsingTcp=forceTcp;cameraIdleIcon.visibility=View.GONE;cameraContext.visibility=View.GONE;cameraStatus.textSize=18f;cameraStatus.visibility=View.VISIBLE;cameraStatus.background=null;cameraStatus.text=if(forceTcp)"Conectando câmera · TCP…"else"Conectando câmera…";cameraClose.visibility=View.VISIBLE;playerView.visibility=View.VISIBLE
        val exo=ExoPlayer.Builder(this).build().also{p->player=p;playerView.player=p;p.volume=0f;p.repeatMode=Player.REPEAT_MODE_ONE;p.addListener(object:Player.Listener{
            override fun onPlaybackStateChanged(playbackState:Int){if(playbackState==Player.STATE_READY&&cameraActive){cameraStatus.visibility=View.GONE;dockView.setCameraLive(true)}}
            override fun onPlayerError(error:PlaybackException){if(!cameraActive)return;dockView.setCameraLive(false);if(!cameraUsingTcp){cameraStatus.visibility=View.VISIBLE;cameraStatus.text="Tentando modo TCP…";handler.postDelayed({if(cameraActive)startCameraTransport(true)},600L)}else{val detail=error.cause?.message?.lineSequence()?.firstOrNull()?.take(52)?:error.errorCodeName;handler.removeCallbacks(cameraAutoOff);releasePlayer();cameraActive=false;cameraUsingTcp=false;playerView.visibility=View.INVISIBLE;cameraClose.visibility=View.GONE;cameraStatus.visibility=View.VISIBLE;cameraStatus.background=idleCameraBackground();cameraStatus.text="Câmera indisponível\n$detail\n\nTOQUE PARA TENTAR NOVAMENTE"}}
        })}
        runCatching{val item=MediaItem.fromUri(url);val factory=RtspMediaSource.Factory().setTimeoutMs(10_000);if(forceTcp)factory.setForceUseRtpTcp(true);exo.setMediaSource(factory.createMediaSource(item));exo.prepare();exo.playWhenReady=true}.onFailure{handler.removeCallbacks(cameraAutoOff);releasePlayer();cameraActive=false;dockView.setCameraLive(false);playerView.visibility=View.INVISIBLE;cameraClose.visibility=View.GONE;cameraStatus.visibility=View.VISIBLE;cameraStatus.background=idleCameraBackground();cameraStatus.text="URL RTSP inválida\n${it.message.orEmpty().take(52)}\n\nTOQUE PARA TENTAR NOVAMENTE"}
    }

    private fun stopCamera(showIdle:Boolean){handler.removeCallbacks(cameraAutoOff);cameraActive=false;cameraUsingTcp=false;dockView.setCameraLive(false);releasePlayer();if(showIdle)showCameraIdle()}
    private fun releasePlayer(){playerView.player=null;player?.stop();player?.release();player=null}

    private fun requestNeededPermissions(){val p=mutableListOf<String>();if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)p+=Manifest.permission.ACCESS_FINE_LOCATION;if(checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED)p+=Manifest.permission.ACCESS_COARSE_LOCATION;if(checkSelfPermission(Manifest.permission.READ_CALENDAR)!=PackageManager.PERMISSION_GRANTED)p+=Manifest.permission.READ_CALENDAR;if(p.isNotEmpty())requestPermissions(p.toTypedArray(),42)}
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray){super.onRequestPermissionsResult(requestCode,permissions,grantResults);if(requestCode==42)refreshAll()}
    private fun refreshAll(){refreshBattery();refreshCalendar();refreshWeather()}
    private fun refreshBattery(){val i=registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))?:return;val level=i.getIntExtra(BatteryManager.EXTRA_LEVEL,0);val scale=i.getIntExtra(BatteryManager.EXTRA_SCALE,100);val plugged=i.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)!=0;dockView.setBattery(BatteryInfo((level*100f/scale).toInt(),plugged))}

    private fun refreshCalendar(){
        val allowed=checkSelfPermission(Manifest.permission.READ_CALENDAR)==PackageManager.PERMISSION_GRANTED;dockView.setCalendarPermission(allowed);if(!allowed){dockView.setCalendarStatus("Permita acesso ao calendário");latestEvents=emptyList();return dockView.setEvents(emptyList())}
        dockView.setCalendarStatus("Atualizando agenda…");Thread{val state=runCatching{calendarRepo.loadState(5)}.getOrNull();runOnUiThread{if(state==null){latestEvents=emptyList();dockView.setEvents(emptyList());dockView.setCalendarStatus("Não foi possível ler a agenda")}else{latestEvents=state.events;dockView.setEvents(state.events);val source=state.calendarNames.take(2).joinToString(" · ");dockView.setCalendarStatus(when{state.visibleCalendars==0->"Nenhum calendário visível no Android";state.events.isEmpty()&&source.isNotBlank()->"Sem mais compromissos hoje · $source";state.events.isEmpty()->"Sem mais compromissos hoje";else->"${state.visibleCalendars} calendário(s) · $source"});if(!cameraActive)updateCameraContextImmediate()}}}.start()
    }

    private fun refreshWeather(){dockView.setWeatherLoading(true);locationRepo.getCurrent{c->if(c==null){dockView.setLocationLabel("São Paulo · fallback");weatherRepo.fetch(-23.5505,-46.6333){applyWeatherResult(it)};return@getCurrent};val precision=if(c.accuracyMeters>0)" · ±${c.accuracyMeters.toInt()} m"else"";dockView.setLocationLabel("Local atual$precision");resolveLocationLabel(c.latitude,c.longitude,precision);weatherRepo.fetch(c.latitude,c.longitude){applyWeatherResult(it)}}}
    private fun applyWeatherResult(result:Result<WeatherSnapshot>){dockView.setWeatherLoading(false);result.onSuccess{latestWeather=it;dockView.setWeather(it);if(!cameraActive)updateCameraContextImmediate()};result.onFailure{dockView.setWeatherError()}}
    private fun resolveLocationLabel(latitude:Double,longitude:Double,precision:String){Thread{val label=runCatching{@Suppress("DEPRECATION") val address=Geocoder(this,Locale("pt","BR")).getFromLocation(latitude,longitude,1)?.firstOrNull();address?.subLocality?:address?.locality?:address?.subAdminArea?:"Local atual"}.getOrDefault("Local atual");runOnUiThread{dockView.setLocationLabel("$label$precision")}}.start()}
    private fun enterImmersive(){@Suppress("DEPRECATION") window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE}
}
