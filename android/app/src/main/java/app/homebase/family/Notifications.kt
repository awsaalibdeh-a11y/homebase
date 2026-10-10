package app.homebase.family

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notifications {
    const val CH_SERVICE = "service"
    const val CH_MESSAGES = "messages"
    const val CH_QUIET = "quiet"
    const val CH_ALERTS = "alerts"
    const val CH_CALLS = "calls"
    const val ID_SERVICE = 1
    const val ID_CALL = 2
    private var next = 100

    fun createChannels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Homebase is connected", NotificationManager.IMPORTANCE_LOW).apply { description = "Shows that Homebase is running, so you always know." ; setShowBadge(false) })
        nm.createNotificationChannel(NotificationChannel(CH_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH).apply { setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), attrs); enableVibration(true) })
        nm.createNotificationChannel(NotificationChannel(CH_QUIET, "Messages in quiet hours", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null) })
        nm.createNotificationChannel(NotificationChannel(CH_ALERTS, "Check-ins, places and SOS", NotificationManager.IMPORTANCE_HIGH).apply { setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), attrs); enableVibration(true) })
        nm.createNotificationChannel(NotificationChannel(CH_CALLS, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null); enableVibration(false); lockscreenVisibility = Notification.VISIBILITY_PUBLIC })
    }

    private fun open(c: Context, extra: String? = null): PendingIntent =
        PendingIntent.getActivity(c, next++, Intent(c, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP; extra?.let { putExtra("open", it) } }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun service(c: Context, text: String): Notification =
        NotificationCompat.Builder(c, CH_SERVICE).setSmallIcon(R.drawable.ic_stat_home).setContentTitle("Homebase").setContentText(text)
            .setOngoing(true).setSilent(true).setCategory(NotificationCompat.CATEGORY_SERVICE).setContentIntent(open(c)).build()

    fun post(c: Context, channel: String, title: String, text: String, open: String? = null, urgent: Boolean = false) {
        if (!NotificationManagerCompat.from(c).areNotificationsEnabled()) return
        val n = NotificationCompat.Builder(c, channel).setSmallIcon(R.drawable.ic_stat_home).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true).setContentIntent(open(c, open))
            .setPriority(if (channel == CH_QUIET) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (urgent) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_MESSAGE).build()
        runCatching { NotificationManagerCompat.from(c).notify(next++, n) }
    }

    fun incomingCall(c: Context, name: String, urgent: Boolean) {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val show = PendingIntent.getActivity(c, 1, Intent(c, CallActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP) }, flags)
        val answer = PendingIntent.getActivity(c, 2, Intent(c, CallActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP); putExtra("accept", true) }, flags)
        val decline = PendingIntent.getBroadcast(c, 3, Intent(c, ActionReceiver::class.java).setAction("decline_call"), flags)
        val who = androidx.core.app.Person.Builder().setName(name).setImportant(true).build()
        val n = NotificationCompat.Builder(c, CH_CALLS).setSmallIcon(R.drawable.ic_stat_home)
            .setContentTitle(if (urgent) "Urgent call from $name" else name).setContentText("Homebase call")
            .setCategory(NotificationCompat.CATEGORY_CALL).setPriority(NotificationCompat.PRIORITY_MAX).setOngoing(true)
            .setFullScreenIntent(show, true).setContentIntent(show).setTimeoutAfter(46_000)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(who, decline, answer)).build()
        runCatching { NotificationManagerCompat.from(c).notify(ID_CALL, n) }
    }

    fun missedCall(c: Context, name: String) = post(c, CH_ALERTS, "Missed call", "$name tried to call you", "chat")

    fun ping(c: Context, name: String, pingId: Int, quiet: Boolean) {
        if (!NotificationManagerCompat.from(c).areNotificationsEnabled()) return
        val nid = next++
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        fun act(action: String, code: Int) = PendingIntent.getBroadcast(c, code + nid, Intent(c, ActionReceiver::class.java).setAction(action).putExtra("ping", pingId).putExtra("nid", nid), flags)
        val n = NotificationCompat.Builder(c, if (quiet) CH_QUIET else CH_ALERTS).setSmallIcon(R.drawable.ic_stat_home)
            .setContentTitle("$name asked where you are").setContentText("You choose: share where you are right now, or say not now.")
            .setAutoCancel(true).setContentIntent(open(c)).setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "Share", act("ping_share", 10)).addAction(0, "Not now", act("ping_decline", 20)).build()
        runCatching { NotificationManagerCompat.from(c).notify(nid, n) }
    }

    fun cancelCall(c: Context) = NotificationManagerCompat.from(c).cancel(ID_CALL)
}
