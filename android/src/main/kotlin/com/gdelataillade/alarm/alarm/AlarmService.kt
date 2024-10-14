package com.gdelataillade.alarm.alarm

import com.gdelataillade.alarm.services.AudioService
import com.gdelataillade.alarm.services.AlarmStorage
import com.gdelataillade.alarm.services.VibrationService
import com.gdelataillade.alarm.services.VolumeService
import com.gdelataillade.alarm.models.NotificationSettings

import android.app.Service
import android.content.BroadcastReceiver
import android.app.PendingIntent
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Intent
import android.content.IntentFilter
import android.content.Context
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.media.AudioManager
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import android.os.Build
import android.os.Handler
import io.flutter.Log
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.embedding.engine.FlutterEngine
import org.json.JSONObject
import java.util.Objects

class AlarmService : Service() {
    private var audioService: AudioService? = null
    private var vibrationService: VibrationService? = null
    private var volumeService: VolumeService? = null
    private var showSystemUI: Boolean = true
    var settingsContentObserver: SettingsContentObserver? = null
    var currentAlarmId: Int = -1;

    private val receiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if(action == TelephonyManager.ACTION_PHONE_STATE_CHANGED || action == Intent.ACTION_SCREEN_ON || action == Intent.ACTION_SCREEN_OFF) {
                if(currentAlarmId != -1 ){
                    stopAlarm(currentAlarmId);
                }
            }
        }
    }
    

    companion object {
        @JvmStatic
        var ringingAlarmIds: List<Int> = listOf()
    }

    inner class SettingsContentObserver internal constructor(
        var context: Context,
        handler: Handler?
    ) :
        ContentObserver(handler) {
        var previousVolume: Int;
        var previousVolumRingTone: Int;
        var currentAlarmId: Int = -1;

        init {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            previousVolume =
                Objects.requireNonNull(audio).getStreamVolume(AudioManager.STREAM_MUSIC)

            previousVolumRingTone =
                Objects.requireNonNull(audio).getStreamVolume(AudioManager.STREAM_RING)
        }

        fun setPlayingAlarmId(id:Int){
            currentAlarmId = id;
        }


        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            var currentVolume =
                Objects.requireNonNull(audio).getStreamVolume(AudioManager.STREAM_MUSIC)

            var delta = previousVolume - currentVolume
            if (delta > 0) {

                previousVolume = currentVolume
                // print("Volume Decrease")
                if(this.currentAlarmId != -1){
                    stopAlarm(this.currentAlarmId);
                }
            } else if (delta < 0) {
                // print("Volume Increased")
                previousVolume = currentVolume
                if(this.currentAlarmId != -1){
                    stopAlarm(this.currentAlarmId);
                }
            }
            else{
                 currentVolume =
                    Objects.requireNonNull(audio).getStreamVolume(AudioManager.STREAM_RING)

                 delta = previousVolumRingTone - currentVolume
                if (delta > 0) {

                    previousVolumRingTone = currentVolume
                    // print("Volume Decrease")
                    if(this.currentAlarmId != -1){
                        stopAlarm(this.currentAlarmId);
                    }
                } else if (delta < 0) {
                    // print("Volume Increased")
                    previousVolumRingTone = currentVolume
                    if(this.currentAlarmId != -1){
                        stopAlarm(this.currentAlarmId);
                    }
                }
            }
        }
    }
    

    override fun onCreate() {
        super.onCreate()

        audioService = AudioService(this)
        vibrationService = VibrationService(this)
        volumeService = VolumeService(this)

        settingsContentObserver = SettingsContentObserver(this, Handler(Looper.getMainLooper()))

        val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);

        registerReceiver(receiver, filter)

        applicationContext.contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI,
            true,
            settingsContentObserver!!
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val id = intent.getIntExtra("id", 0)
         currentAlarmId = id;
        val action = intent.getStringExtra(AlarmReceiver.EXTRA_ALARM_ACTION)

        if (ringingAlarmIds.isNotEmpty()) {
            Log.d("AlarmService", "An alarm is already ringing. Ignoring new alarm with id: $id")
            unsaveAlarm(id)
            return START_NOT_STICKY
        }

        if (action == "STOP_ALARM" && id != 0) {
            unsaveAlarm(id)
            return START_NOT_STICKY
        }

        val assetAudioPath = intent.getStringExtra("assetAudioPath") ?: return START_NOT_STICKY // Fallback if null
        val loopAudio = intent.getBooleanExtra("loopAudio", true)
        val vibrate = intent.getBooleanExtra("vibrate", true)
        val volume = intent.getDoubleExtra("volume", -1.0)
        val fadeDuration = intent.getDoubleExtra("fadeDuration", 0.0)
        val fullScreenIntent = intent.getBooleanExtra("fullScreenIntent", true)

        val notificationSettingsJson = intent.getStringExtra("notificationSettings")
        val notificationSettings = if (notificationSettingsJson != null) {
            val jsonObject = JSONObject(notificationSettingsJson)
            val map: MutableMap<String, Any> = mutableMapOf()
            jsonObject.keys().forEach { key ->
                map[key] = jsonObject.get(key)
            }
            NotificationSettings.fromJson(map)
        } else {
            val notificationTitle = intent.getStringExtra("notificationTitle") ?: "Title"
            val notificationBody = intent.getStringExtra("notificationBody") ?: "Body"
            NotificationSettings(notificationTitle, notificationBody)
        }

        // Handling notification
        val notificationHandler = NotificationHandler(this)
        val appIntent = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)
        val pendingIntent = PendingIntent.getActivity(this, id, appIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = notificationHandler.buildNotification(notificationSettings, fullScreenIntent, pendingIntent, id)

        // Starting foreground service safely
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(id, notification)
            }
        } catch (e: ForegroundServiceStartNotAllowedException) {
            Log.e("AlarmService", "Foreground service start not allowed", e)
            return START_NOT_STICKY // Return if cannot start foreground service
        } catch (e: SecurityException) {
            Log.e("AlarmService", "Security exception in starting foreground service", e)
            return START_NOT_STICKY // Return on security exception
        }

        AlarmPlugin.eventSink?.success(mapOf(
            "id" to id,
            "method" to "ring"
        ))

        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        if (am.ringerMode != AudioManager.RINGER_MODE_SILENT) {
            if (volume >= 0.0 && volume <= 1.0) {
            volumeService?.setVolume(volume, showSystemUI)
        }

        volumeService?.requestAudioFocus()

        audioService?.setOnAudioCompleteListener {
            if (!loopAudio!!) {
                vibrationService?.stopVibrating()
                volumeService?.restorePreviousVolume(showSystemUI)
                volumeService?.abandonAudioFocus()
            }
        }

        settingsContentObserver?.setPlayingAlarmId(id);
        audioService?.playAudio(id, assetAudioPath!!, loopAudio!!, fadeDuration!!)

        ringingAlarmIds = audioService?.getPlayingMediaPlayersIds()!!

        if (vibrate!!) {
            vibrationService?.startVibrating(longArrayOf(0, 500, 500), 1)
        }

        // Wake up the device
        val wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "app:AlarmWakelockTag")
        wakeLock.acquire(5 * 60 * 1000L) // 5 minutes

        return START_STICKY
        }
         else{
            Handler(Looper.getMainLooper()).postDelayed({
                stopSelf();
            }, 3000)

            return START_NOT_STICKY;
        }
    }

    fun unsaveAlarm(id: Int) {
        AlarmStorage(this).unsaveAlarm(id)
        AlarmPlugin.eventSink?.success(mapOf(
            "id" to id,
            "method" to "stop"
        ))
        stopAlarm(id)
    }

    fun stopAlarm(id: Int) {
         currentAlarmId = -1;
        try {
            val playingIds = audioService?.getPlayingMediaPlayersIds() ?: listOf()
            ringingAlarmIds = playingIds

            // Safely call methods on 'volumeService' and 'audioService'
            volumeService?.restorePreviousVolume(showSystemUI)
            volumeService?.abandonAudioFocus()

            audioService?.stopAudio(id)

            // Check if media player is empty safely
            if (audioService?.isMediaPlayerEmpty() == true) {
                vibrationService?.stopVibrating()
                stopSelf()
            }

            stopForeground(true)
        } catch (e: IllegalStateException) {
            Log.e("AlarmService", "Illegal State: ${e.message}", e)
        } catch (e: Exception) {
            Log.e("AlarmService", "Error in stopping alarm: ${e.message}", e)
        }
    }

    override fun onDestroy() {
        ringingAlarmIds = listOf()

        audioService?.cleanUp()
        vibrationService?.stopVibrating()
        volumeService?.restorePreviousVolume(showSystemUI)
        volumeService?.abandonAudioFocus()

        stopForeground(true)

        // Call the superclass method
        super.onDestroy()
         this.settingsContentObserver?.let {
            applicationContext.contentResolver.unregisterContentObserver(
                it
            )
        };
        unregisterReceiver(receiver)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
