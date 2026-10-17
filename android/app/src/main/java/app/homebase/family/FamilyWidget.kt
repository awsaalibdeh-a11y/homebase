package app.homebase.family

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import app.homebase.family.ui.memberLine

/** A home-screen card with everyone in the family and where they are, from the last update the app saw. Tap it to open Mellow. */
class FamilyWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = paint(context, manager, ids)

    companion object {
        fun refresh(ctx: Context) {
            val m = AppWidgetManager.getInstance(ctx)
            val ids = runCatching { m.getAppWidgetIds(ComponentName(ctx, FamilyWidget::class.java)) }.getOrNull() ?: return
            if (ids.isNotEmpty()) paint(ctx, m, ids)
        }

        private fun paint(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
            val fam = runCatching { Net.decodeFromString(FamilyResp.serializer(), ctx.hb.store.familyCache) }.getOrNull()
            val text = fam?.members?.joinToString("\n") { m -> "${m.emoji} ${m.name}" + (if (m.sos) " 🚨 SOS" else "") + "\n   " + memberLine(m).substringBefore(" · off:") }
                ?: "Open Mellow to sign in."
            val open = PendingIntent.getActivity(ctx, 78, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            ids.forEach { id ->
                val v = RemoteViews(ctx.packageName, R.layout.widget_family).apply {
                    setTextViewText(R.id.family_title, fam?.family?.name ?: "Mellow")
                    setTextViewText(R.id.family_text, text)
                    setOnClickPendingIntent(R.id.family_widget, open)
                }
                manager.updateAppWidget(id, v)
            }
        }
    }
}
