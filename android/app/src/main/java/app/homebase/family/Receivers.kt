package app.homebase.family

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

/** Brings Homebase back after the tablet restarts, so the family can still reach it. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && context.hb.store.signedIn) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, HomebaseService::class.java)) }
        }
    }
}

/** The buttons on notifications: decline a call, or answer a "where are you?" without opening the app. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.hb
        when (intent.action) {
            "decline_call" -> { app.calls.decline(); NotificationManagerCompat.from(context).cancel(Notifications.ID_CALL) }
            "ping_share", "ping_decline" -> {
                val id = intent.getIntExtra("ping", 0)
                val pending = goAsync()
                app.scope.launch {
                    runCatching {
                        if (intent.action == "ping_share") {
                            val spot = LocationClient(context) {}.lastKnown()
                            if (spot != null) app.api.answerPing(id, true, spot.latitude, spot.longitude) else app.api.answerPing(id, false)
                        } else app.api.answerPing(id, false)
                    }
                    app.state.pingAsk.value = null
                    NotificationManagerCompat.from(context).cancel(intent.getIntExtra("nid", 0))
                    pending.finish()
                }
            }
        }
    }
}
