package app.homebase.family

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import app.homebase.family.ui.clockOf

/**
 * Grounded apps on a child's tablet or phone. When an app a parent has grounded comes to the front, Homebase sends the child back
 * to the home screen and tells them why (and until when). It only looks at which app is in front, never what is inside it.
 * Needs "Usage access" and "Display over other apps", which the child allows once.
 */
class BlockGuard(private val ctx: Context) {
    private var fg: String? = null
    private var scanned = 0L
    private var toldAt = HashMap<String, Long>()
    private val labels = HashMap<String, String>()

    private fun label(pkg: String): String = labels.getOrPut(pkg) {
        runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString().lowercase() }.getOrDefault("")
    }

    private fun foreground(now: Long): String? {
        val usage = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usage.queryEvents(if (scanned == 0L) now - 15_000 else scanned - 500, now)
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) fg = e.packageName
            else if (e.eventType == UsageEvents.Event.ACTIVITY_PAUSED && fg == e.packageName) fg = null
        }
        scanned = now
        return fg
    }

    /** Call every couple of seconds with the grounded apps that are active right now. */
    fun tick(blocks: List<BlockInfo>) {
        if (blocks.isEmpty() || !ScreenTimeTracker.hasAccess(ctx)) { fg = null; scanned = 0L; return }
        val now = System.currentTimeMillis()
        val pkg = foreground(now) ?: return
        if (pkg == ctx.packageName || pkg.startsWith("com.android.systemui") || pkg.contains("launcher")) return
        val name = label(pkg)
        val hit = blocks.firstOrNull { b -> b.until * 1000 > now && b.key.isNotBlank() && (pkg.lowercase().contains(b.key) || (name.isNotEmpty() && name.contains(b.key))) } ?: return
        if (Settings.canDrawOverlays(ctx) || true) {                                       // sending someone home from a background service needs the overlay permission on newer Android
            ctx.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        val last = toldAt[hit.label] ?: 0L
        if (now - last > 60_000) {
            toldAt[hit.label] = now
            app.homebase.family.ui.Notices.grounded(ctx, hit.label, clockOf(hit.until))
        }
    }
}
