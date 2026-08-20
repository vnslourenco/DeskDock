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
import com.deskdock.app.model.WeatherSnapshot
import com.deskdock.app.ui.DockView
import java.util.Locale
import kotlin.math.min

@OptIn(UnstableApi::class)
class MainActivity : Activity() {
    private lateinit var dockView: DockView
    private lateinit var root: FrameLayout
    private lateinit var cameraFrame: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var cameraStatus: TextView
    private lateinit var cameraClose: View
    private lateinit var calendarRepo: CalendarRepository
    private lateinit var locationRepo: LocationRepository
    private val weatherRepo = WeatherRepository()
    private val handler = Handler(Looper.getMainLooper())

    private var player: ExoPlayer? = null
    private var cameraActive = false
    private var cameraUsingTcp = false
    private var dailyForecastVisible = false
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
            onCameraRequested = { if(cameraActive) stopCamera(true) else openCameraSession() }
            onCameraSettingsRequested = { showCameraConfigDialog() }
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
        handler.postDelayed(forecastSwitch, prefs.getInt("hourly_seconds",60).coerceIn(10,300)*1000L)
        showCameraIdle()
    }

    override fun onResume(){super.onResume();enterImmersive();refreshCalendar()}
    override fun onPause(){if(cameraActive)stopCamera(true);super.onPause()}
    override fun onDestroy(){handler.removeCallbacksAndMessages(null);releasePlayer();super.onDestroy()}

    private fun createCameraOverlay(){
        cameraFrame=FrameLayout(this).apply{
            visibility=View.GONE
            elevation=24f
            background=GradientDrawable().apply{setColor(Color.rgb(3,4,7));cornerRadius=24f;setStroke(2,Color.rgb(72,82,140))}
            clipToOutline=true
        }
        playerView=PlayerView(this).apply{
            useController=false
            resizeMode=AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(Color.BLACK)
        }
        cameraStatus=TextView(this).apply{
            setTextColor(Color.rgb(232,232,238));textSize=18f;gravity=Gravity.CENTER;setPadding(20,20,20,20)
        }
        cameraClose=object:View(this){
            private val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE;strokeCap=Paint.Cap.ROUND}
            override fun onDraw(canvas:Canvas){super.onDraw(canvas);val d=min(width,height).toFloat();p.strokeWidth=(d*.075f).coerceAtLeast(2.5f);val i=d*.32f;canvas.drawLine(i,i,width-i,height-i,p);canvas.drawLine(width-i,i,i,height-i,p)}
        }.apply{
            background=GradientDrawable().apply{setColor(Color.argb(220,12,12,16));shape=GradientDrawable.OVAL;setStroke(1,Color.rgb(90,90,105))}
            setOnClickListener{stopCamera(true)}
        }
        cameraFrame.addView(playerView,FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,FrameLayout.LayoutParams.MATCH_PARENT))
        cameraFrame.addView(cameraStatus,FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,FrameLayout.LayoutParams.MATCH_PARENT))
        cameraFrame.addView(cameraClose,FrameLayout.LayoutParams(54,54,Gravity.TOP or Gravity.END).apply{topMargin=12;rightMargin=12})
        root.addView(cameraFrame)
    }

    private fun positionCameraOverlay(){
        val w=root.width.toFloat();val h=root.height.toFloat();if(w<=0||h<=0)return
        val width=w*.56f;val height=width*9f/16f
        cameraFrame.layoutParams=FrameLayout.LayoutParams(width.toInt(),height.toInt()).apply{
            leftMargin=(w*.22f).toInt();topMargin=(h*.30f).toInt()
        }
    }

    private fun showCameraIdle(){
        cameraActive=false;cameraUsingTcp=false;dockView.setCameraLive(false);cameraFrame.visibility=View.GONE;cameraStatus.visibility=View.VISIBLE;cameraStatus.text="";playerView.visibility=View.INVISIBLE
    }

    private fun showCameraConfigDialog(){
        val input=EditText(this).apply{
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setText(prefs.getString("camera_rtsp_url","rtsp://admin:@192.168.0.138:554/cam/realmonitor?channel=1&subtype=1"))
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Câmera RTSP")
            .setMessage("Cole a URL RTSP completa. Ela fica salva somente neste aparelho.")
            .setView(input)
            .setPositiveButton("Salvar"){_,_->stopCamera(false);prefs.edit().putString("camera_rtsp_url",input.text.toString().trim()).apply();showCameraIdle()}
            .setNegativeButton("Cancelar",null)
            .show()
    }

    private fun showSettingsDialog(){
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(42,12,42,0)}
        fun check(label:String,key:String,def:Boolean):CheckBox=CheckBox(this).apply{text=label;isChecked=prefs.getBoolean(key,def);box.addView(this)}
        val uv=check("Mostrar índice UV","show_uv",true)
        val air=check("Mostrar qualidade do ar","show_air",true)
        val sun=check("Mostrar nascer/pôr do sol","show_sun",true)
        val countdown=check("Contagem regressiva nos 60 min finais","show_countdown",true)
        fun numeric(label:String,key:String,def:Int):EditText{
            box.addView(TextView(this).apply{text=label;setPadding(0,10,0,2)})
            return EditText(this).apply{inputType=InputType.TYPE_CLASS_NUMBER;setText(prefs.getInt(key,def).toString());setSingleLine(true);box.addView(this)}
        }
        val cam=numeric("Tempo da câmera (segundos)","camera_seconds",30)
        val hrs=numeric("Próximas horas (segundos)","hourly_seconds",60)
        val days=numeric("Próximos dias (segundos)","daily_seconds",30)
        AlertDialog.Builder(this).setTitle("DeskDock · Configurações").setView(box).setPositiveButton("Salvar"){_,_->
            prefs.edit()
                .putBoolean("show_uv",uv.isChecked).putBoolean("show_air",air.isChecked).putBoolean("show_sun",sun.isChecked).putBoolean("show_countdown",countdown.isChecked)
                .putInt("camera_seconds",cam.text.toString().toIntOrNull()?.coerceIn(10,300)?:30)
                .putInt("hourly_seconds",hrs.text.toString().toIntOrNull()?.coerceIn(10,300)?:60)
                .putInt("daily_seconds",days.text.toString().toIntOrNull()?.coerceIn(10,300)?:30).apply()
            applyPreferences();handler.removeCallbacks(forecastSwitch);handler.postDelayed(forecastSwitch,prefs.getInt("hourly_seconds",60)*1000L)
        }.setNegativeButton("Cancelar",null).show()
    }

    private fun applyPreferences(){dockView.setInfoOptions(prefs.getBoolean("show_uv",true),prefs.getBoolean("show_air",true),prefs.getBoolean("show_sun",true),prefs.getBoolean("show_countdown",true))}

    private fun openCameraSession(){
        val url=prefs.getString("camera_rtsp_url",null)?.trim().orEmpty()
        if(url.isBlank()){showCameraConfigDialog();return}
        cameraActive=true;cameraUsingTcp=false;dockView.setCameraLive(false)
        cameraFrame.visibility=View.VISIBLE;cameraFrame.alpha=0f;cameraFrame.animate().alpha(1f).setDuration(250).start()
        handler.removeCallbacks(cameraAutoOff);handler.postDelayed(cameraAutoOff,prefs.getInt("camera_seconds",30).coerceIn(10,300)*1000L)
        startCameraTransport(false)
    }

    private fun startCameraTransport(forceTcp:Boolean){
        val url=prefs.getString("camera_rtsp_url",null)?.trim().orEmpty();if(url.isBlank()||!cameraActive)return
        releasePlayer();cameraUsingTcp=forceTcp;cameraFrame.visibility=View.VISIBLE;cameraStatus.visibility=View.VISIBLE;cameraStatus.text=if(forceTcp)"Conectando câmera · TCP…"else"Conectando câmera…";playerView.visibility=View.VISIBLE
        val exo=ExoPlayer.Builder(this).build().also{p->
            player=p;playerView.player=p;p.volume=0f;p.repeatMode=Player.REPEAT_MODE_ONE
            p.addListener(object:Player.Listener{
                override fun onPlaybackStateChanged(playbackState:Int){if(playbackState==Player.STATE_READY&&cameraActive){cameraStatus.visibility=View.GONE;dockView.setCameraLive(true)}}
                override fun onPlayerError(error:PlaybackException){
                    if(!cameraActive)return;dockView.setCameraLive(false)
                    if(!cameraUsingTcp){cameraStatus.visibility=View.VISIBLE;cameraStatus.text="Tentando modo TCP…";handler.postDelayed({if(cameraActive)startCameraTransport(true)},600L)}
                    else{val detail=error.cause?.message?.lineSequence()?.firstOrNull()?.take(52)?:error.errorCodeName;handler.removeCallbacks(cameraAutoOff);releasePlayer();cameraActive=false;cameraUsingTcp=false;cameraStatus.visibility=View.VISIBLE;cameraStatus.text="Câmera indisponível\n$detail\n\nToque no botão para tentar novamente";playerView.visibility=View.INVISIBLE;dockView.setCameraLive(false)}
                }
            })
        }
        runCatching{
            val item=MediaItem.fromUri(url);val factory=RtspMediaSource.Factory().setTimeoutMs(10_000);if(forceTcp)factory.setForceUseRtpTcp(true)
            exo.setMediaSource(factory.createMediaSource(item));exo.prepare();exo.playWhenReady=true
        }.onFailure{
            handler.removeCallbacks(cameraAutoOff);releasePlayer();cameraActive=false;dockView.setCameraLive(false);cameraStatus.visibility=View.VISIBLE;cameraStatus.text="URL RTSP inválida\n${it.message.orEmpty().take(52)}";playerView.visibility=View.INVISIBLE
        }
    }

    private fun stopCamera(showIdle:Boolean){
        handler.removeCallbacks(cameraAutoOff);cameraActive=false;cameraUsingTcp=false;dockView.setCameraLive(false);releasePlayer()
        if(showIdle){cameraFrame.animate().alpha(0f).setDuration(180).withEndAction{showCameraIdle()}.start()}else showCameraIdle()
    }
    private fun releasePlayer(){playerView.player=null;player?.stop();player?.release();player=null}

    private fun requestNeededPermissions(){
        val p=mutableListOf<String>()
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)p+=Manifest.permission.ACCESS_FINE_LOCATION
        if(checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED)p+=Manifest.permission.ACCESS_COARSE_LOCATION
        if(checkSelfPermission(Manifest.permission.READ_CALENDAR)!=PackageManager.PERMISSION_GRANTED)p+=Manifest.permission.READ_CALENDAR
        if(p.isNotEmpty())requestPermissions(p.toTypedArray(),42)
    }
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray){super.onRequestPermissionsResult(requestCode,permissions,grantResults);if(requestCode==42)refreshAll()}
    private fun refreshAll(){refreshBattery();refreshCalendar();refreshWeather()}
    private fun refreshBattery(){val i=registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))?:return;val level=i.getIntExtra(BatteryManager.EXTRA_LEVEL,0);val scale=i.getIntExtra(BatteryManager.EXTRA_SCALE,100);val plugged=i.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)!=0;dockView.setBattery(BatteryInfo((level*100f/scale).toInt(),plugged))}

    private fun refreshCalendar(){
        val allowed=checkSelfPermission(Manifest.permission.READ_CALENDAR)==PackageManager.PERMISSION_GRANTED;dockView.setCalendarPermission(allowed)
        if(!allowed){dockView.setCalendarStatus("Permita acesso ao calendário");return dockView.setEvents(emptyList())}
        dockView.setCalendarStatus("Atualizando agenda…")
        Thread{
            val state=runCatching{calendarRepo.loadState(8)}.getOrNull()
            runOnUiThread{
                if(state==null){dockView.setEvents(emptyList());dockView.setCalendarStatus("Não foi possível ler a agenda")}
                else{dockView.setEvents(state.events);val source=state.calendarNames.take(2).joinToString(" · ");dockView.setCalendarStatus(when{state.visibleCalendars==0->"Nenhum calendário visível no Android";state.events.isEmpty()&&source.isNotBlank()->"Sem mais compromissos hoje · $source";state.events.isEmpty()->"Sem mais compromissos hoje";else->"${state.visibleCalendars} calendário(s) · $source"})}
            }
        }.start()
    }

    private fun refreshWeather(){
        dockView.setWeatherLoading(true)
        locationRepo.getCurrent{c->
            if(c==null){dockView.setLocationLabel("São Paulo · fallback");weatherRepo.fetch(-23.5505,-46.6333){applyWeatherResult(it)};return@getCurrent}
            val precision=if(c.accuracyMeters>0)" · ±${c.accuracyMeters.toInt()} m"else""
            dockView.setLocationLabel("Local atual$precision");resolveLocationLabel(c.latitude,c.longitude,precision);weatherRepo.fetch(c.latitude,c.longitude){applyWeatherResult(it)}
        }
    }
    private fun applyWeatherResult(result:Result<WeatherSnapshot>){dockView.setWeatherLoading(false);result.onSuccess{dockView.setWeather(it)};result.onFailure{dockView.setWeatherError()}}
    private fun resolveLocationLabel(latitude:Double,longitude:Double,precision:String){Thread{val label=runCatching{@Suppress("DEPRECATION") val address=Geocoder(this,Locale("pt","BR")).getFromLocation(latitude,longitude,1)?.firstOrNull();address?.subLocality?:address?.locality?:address?.subAdminArea?:"Local atual"}.getOrDefault("Local atual");runOnUiThread{dockView.setLocationLabel("$label$precision")}}.start()}
    private fun enterImmersive(){@Suppress("DEPRECATION") window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE}
}
