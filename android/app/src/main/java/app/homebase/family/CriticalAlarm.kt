package app.homebase.family

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * A parent's Critical Check-In: the phone plays a loud, looping alarm sound even on silent, until the child taps "I'm OK".
 * It uses the alarm volume (which silent mode does not mute). If the child allowed Do Not Disturb access, it lets the sound through DND too.
 * When it stops, the alarm volume and the Do Not Disturb setting go back exactly as they were.
 */
object CriticalAlarm {
    const val ID = 4
    private var player: MediaPlayer? = null
    private var savedVolume = -1
    private var savedFilter = -1
    @Volatile var ringing = false
        private set

    fun start(ctx: Context, by: String) {
        if (ringing) return
        ringing = true
        val app = ctx.applicationContext
        val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        runCatching {
            savedVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        }
        val nm = app.getSystemService(NotificationManager::class.java)
        runCatching {
            if (nm.isNotificationPolicyAccessGranted) { savedFilter = nm.currentInterruptionFilter; nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL) }
        }
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(app, uri)
                isLooping = true
                prepare()
                start()
            }
        }
        runCatching {
            val v = if (Build.VERSION.SDK_INT >= 31) app.getSystemService(VibratorManager::class.java).defaultVibrator else @Suppress("DEPRECATION") (app.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 900, 500), 0))
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(app, 40, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), flags)
        val ok = PendingIntent.getBroadcast(app, 41, Intent(app, ActionReceiver::class.java).setAction("critical_ok"), flags)
        val n = NotificationCompat.Builder(app, Notifications.CH_SOS).setSmallIcon(R.drawable.ic_stat_home)
            .setContentTitle("🚨 $by needs you to check in").setContentText("Tap I'm OK to stop the sound.")
            .setPriority(NotificationCompat.PRIORITY_MAX).setCategory(NotificationCompat.CATEGORY_ALARM).setOngoing(true)
            .setFullScreenIntent(open, true).setContentIntent(open).addAction(0, "I'm OK", ok).build()
        runCatching { NotificationManagerCompat.from(app).notify(ID, n) }
    }

    /** Stops the sound and puts the phone back as it was. */
    fun stop(ctx: Context) {
        if (!ringing) return
        ringing = false
        val app = ctx.applicationContext
        runCatching { player?.stop(); player?.release() }
        player = null
        val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (savedVolume >= 0) runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0) }
        savedVolume = -1
        val nm = app.getSystemService(NotificationManager::class.java)
        if (savedFilter > 0) runCatching { if (nm.isNotificationPolicyAccessGranted) nm.setInterruptionFilter(savedFilter) }
        savedFilter = -1
        runCatching {
            val v = if (Build.VERSION.SDK_INT >= 31) app.getSystemService(VibratorManager::class.java).defaultVibrator else @Suppress("DEPRECATION") (app.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
            v.cancel()
        }
        NotificationManagerCompat.from(app).cancel(ID)
    }
}
