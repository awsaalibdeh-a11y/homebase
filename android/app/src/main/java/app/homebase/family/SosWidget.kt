package app.homebase.family

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** A home-screen SOS button. It opens Mellow on a 3-second countdown (so a pocket tap can be cancelled), then sends the SOS. */
class SosWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val open = Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP; putExtra("open", "sos") }
        val pi = PendingIntent.getActivity(context, 77, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        ids.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.widget_sos).apply { setOnClickPendingIntent(R.id.sos_widget, pi) }
            manager.updateAppWidget(id, views)
        }
    }
}
