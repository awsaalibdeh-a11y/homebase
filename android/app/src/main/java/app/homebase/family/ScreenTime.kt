package app.homebase.family

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * What counts as screen time. Only time spent actively PLAYING or WATCHING: the screen is on and unlocked, the app in front is a game,
 * a video app, a social feed (or a browser that is playing something), and the child touched it a moment ago or something is playing.
 * A phone left on a table, a locked screen, and study or work apps are never counted. Pure logic, so it is easy to test.
 */
object ScreenTimeLogic {
    const val ACTIVE_WINDOW_SEC = 150L                 // "a moment ago": two and a half minutes since the last touch

    private val PLAY_PREFIXES = listOf("com.roblox", "com.mojang", "com.supercell", "com.activision", "com.king.", "com.epicgames", "com.innersloth", "com.ea.",
        "com.nianticlabs", "com.miHoYo", "com.garena", "com.tencent.ig", "com.pubg", "com.ubisoft", "com.gameloft", "com.rovio", "com.zynga", "com.playrix",
        "com.kiloo", "com.imangi", "com.halfbrick", "com.outfit7", "com.nintendo", "com.netease", "com.mobilelegends", "com.moonactive", "com.scopely")
    private val WATCH = setOf("com.google.android.youtube", "com.google.android.apps.youtube.kids", "com.netflix.mediaclient", "tv.twitch.android.app",
        "com.disney.disneyplus", "com.amazon.avod.thirdpartyclient", "com.hulu.plus", "com.hbo.hbonow", "com.wbd.stream", "com.crunchyroll.crunchyroid",
        "com.google.android.videos", "com.vimeo.android.videoapp", "org.videolan.vlc", "com.mxtech.videoplayer.ad", "com.apple.android.tv")
    private val SOCIAL = setOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.instagram.android", "com.snapchat.android", "com.facebook.katana",
        "com.facebook.lite", "com.twitter.android", "com.reddit.frontpage", "com.pinterest", "com.instagram.barcelona", "com.tumblr")
    private val BROWSERS = setOf("com.android.chrome", "org.mozilla.firefox", "com.sec.android.app.sbrowser", "com.microsoft.emmx", "com.opera.browser", "com.brave.browser",
        "com.duckduckgo.mobile.android", "com.google.android.apps.chrome")

    /** play | watch | social | browser, or null for anything that is not entertainment (study, work, messages, maps, the launcher...). */
    fun classify(pkg: String, systemCategory: Int): String? = when {
        WATCH.contains(pkg) -> "watch"
        SOCIAL.contains(pkg) -> "social"
        BROWSERS.contains(pkg) -> "browser"
        PLAY_PREFIXES.any { pkg.startsWith(it) } -> "play"
        systemCategory == 0 -> "play"                                    // ApplicationInfo.CATEGORY_GAME
        systemCategory == 2 -> "watch"                                   // CATEGORY_VIDEO
        systemCategory == 4 -> "social"                                  // CATEGORY_SOCIAL
        else -> null
    }

    /** The label to record this moment under (play, watch or social), or null when this moment is not screen time. */
    fun counted(cat: String?, idleSec: Long, mediaPlaying: Boolean): String? = when (cat) {
        "play" -> if (idleSec <= ACTIVE_WINDOW_SEC) "play" else null                       // a game left running with nobody touching it is AFK
        "watch" -> if (mediaPlaying || idleSec <= ACTIVE_WINDOW_SEC) "watch" else null     // a video can be watched without touching the screen
        "social" -> if (idleSec <= ACTIVE_WINDOW_SEC) "social" else null                   // a feed only moves when someone scrolls it
        "browser" -> if (mediaPlaying) "watch" else null                                   // browsing is mostly homework; only a playing video counts
        else -> null
    }
}

/** Counts the seconds, remembers them for today, and sends the running totals to the family. Runs only for a child who turned it on. */
class ScreenTimeTracker(private val ctx: Context) {
    private var fg: String? = null
    private var lastScan = 0L
    private var lastInteract = 0L
    private var lastTick = 0L
    private var dirty = false
    private var lastUpload = 0L
    private val info = HashMap<String, Pair<String, Int>>()

    companion object {
        fun hasAccess(ctx: Context): Boolean {
            val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
            else @Suppress("DEPRECATION") ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
            return mode == AppOpsManager.MODE_ALLOWED
        }

        fun openAccessSettings(ctx: Context) {
            ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        fun today(): String = java.time.LocalDate.now().toString()

        /** Today's saved totals, biggest first. */
        fun readToday(store: Store): List<ScreenApp> {
            if (store.screenDay != today()) return emptyList()
            val o = runCatching { Net.parseToJsonElement(store.screenData).jsonObject }.getOrNull() ?: return emptyList()
            return o.entries.map { (pkg, v) ->
                val a = v.jsonObject
                ScreenApp(pkg, a["l"]?.jsonPrimitive?.contentOrNull ?: pkg, a["c"]?.jsonPrimitive?.contentOrNull ?: "play", a["s"]?.jsonPrimitive?.intOrNull ?: 0)
            }.sortedByDescending { it.sec }
        }
    }

    suspend fun tick() {
        val store = ctx.hb.store
        val now = System.currentTimeMillis()
        if (store.isParent || !store.trackScreen || !hasAccess(ctx)) { lastTick = 0; return }
        if (store.screenDay.isNotEmpty() && store.screenDay != today() && dirty) upload(store)         // yesterday's last minutes are not lost at midnight
        rollDay(store)
        scan(now)
        val elapsed = if (lastTick == 0L) 0L else minOf((now - lastTick) / 1000, 45L)
        lastTick = now
        val power = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val keys = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val pkg = fg
        if (elapsed > 0 && pkg != null && pkg != ctx.packageName && power.isInteractive && !keys.isKeyguardLocked) {
            val (label, sys) = lookup(pkg)
            val media = (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).isMusicActive
            val cat = ScreenTimeLogic.counted(ScreenTimeLogic.classify(pkg, sys), (now - lastInteract) / 1000, media)
            if (cat != null) add(store, pkg, label, cat, elapsed.toInt())
        }
        if (dirty && now - lastUpload > 120_000) upload(store)
    }

    private fun lookup(pkg: String): Pair<String, Int> = info.getOrPut(pkg) {
        val pm = ctx.packageManager
        runCatching {
            val ai = pm.getApplicationInfo(pkg, 0)
            val cat = if (Build.VERSION.SDK_INT >= 26) ai.category else ApplicationInfo.CATEGORY_UNDEFINED
            val gameFlag = @Suppress("DEPRECATION") (ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0
            pm.getApplicationLabel(ai).toString() to (if (gameFlag) 0 else cat)
        }.getOrDefault(pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() } to -1)
    }

    /** Reads what the phone says happened since the last look: which app is in front, and when someone last touched it. */
    private fun scan(now: Long) {
        val usage = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val from = if (lastScan == 0L) now - 20 * 60_000 else lastScan - 1000
        val events = usage.queryEvents(from, now)
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> { fg = e.packageName; lastInteract = maxOf(lastInteract, e.timeStamp) }
                UsageEvents.Event.ACTIVITY_PAUSED -> if (fg == e.packageName) fg = null
                7 -> lastInteract = maxOf(lastInteract, e.timeStamp)                            // USER_INTERACTION: a touch, a key, a swipe
            }
        }
        lastScan = now
    }

    private fun rollDay(store: Store) {
        if (store.screenDay != today()) { store.screenDay = today(); store.screenData = "{}"; dirty = false }
    }

    private fun add(store: Store, pkg: String, label: String, cat: String, sec: Int) {
        val o = runCatching { Net.parseToJsonElement(store.screenData).jsonObject }.getOrDefault(JsonObject(emptyMap()))
        val before = o[pkg]?.jsonObject?.get("s")?.jsonPrimitive?.intOrNull ?: 0
        val next = o.toMutableMap()
        next[pkg] = buildJsonObject { put("l", label); put("c", cat); put("s", before + sec) }
        store.screenData = JsonObject(next).toString()
        dirty = true
    }

    private suspend fun upload(store: Store) {
        val apps: JsonArray = buildJsonArray { readToday(store).forEach { a -> add(buildJsonObject { put("pkg", a.pkg); put("label", a.label); put("cat", a.cat); put("sec", a.sec) }) } }
        runCatching { ctx.hb.api.postScreenTime(store.screenDay, apps) }.onSuccess { dirty = false; lastUpload = System.currentTimeMillis() }
    }

    /** Send what we have right now (when the child opens the screen or turns tracking off). */
    suspend fun flush() { val s = ctx.hb.store; if (!s.isParent && s.screenDay == today()) upload(s) }
}

/** "1 h 05 min", "35 min", "under a minute". */
fun durationText(sec: Int): String {
    val m = sec / 60
    return when {
        m >= 60 -> "${m / 60} h ${"%02d".format(m % 60)} min"
        m >= 1 -> "$m min"
        sec > 0 -> "under a minute"
        else -> "0 min"
    }
}
