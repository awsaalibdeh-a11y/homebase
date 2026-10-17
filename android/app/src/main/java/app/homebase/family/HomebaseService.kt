package app.homebase.family

import android.Manifest
import app.homebase.family.ui.planWhen
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import app.homebase.family.ui.gameText
import app.homebase.family.ui.safeDnsStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The always-on part of Homebase. It holds one quiet connection to the server so a message or a call can reach this phone, and,
 * only if the person has location sharing turned on, sends their position. Its notification is always visible, on purpose.
 */
class HomebaseService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private var watchers: Job? = null
    private var locationClient: LocationClient? = null
    private var lastSent: Location? = null
    private var lastSentAt = 0L
    private var screenJob: Job? = null
    private var guardJob: Job? = null
    private val blockGuard by lazy { BlockGuard(this) }
    private val lockOverlay by lazy {
        app.homebase.family.ui.LockOverlay(this, onAsk = { scope.launch { runCatching { hb.api.askUnlock() } } },
            onSos = { scope.launch { runCatching { hb.api.send("sos", "I need help", 0) } } })
    }
    private val screenTracker by lazy { ScreenTimeTracker(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hb.store.signedIn) { stopSelf(); return START_NOT_STICKY }
        goForeground()
        if (loop?.isActive != true) loop = scope.launch { eventLoop() }
        if (watchers?.isActive != true) watchers = scope.launch { watchState() }
        if (guardJob?.isActive != true) guardJob = scope.launch {
            while (isActive) {
                runCatching {
                    val me = hb.state.family.value?.members?.firstOrNull { it.id == hb.store.myId }
                    blockGuard.tick(if (hb.store.isParent) emptyList() else me?.blocks.orEmpty())
                }
                delay(2500)
            }
        }
        if (screenJob?.isActive != true) screenJob = scope.launch { while (isActive) { runCatching { screenTracker.tick() }; delay(30_000) } }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { lockOverlay.hide() }
        scope.cancel()
        locationClient?.stop()
        super.onDestroy()
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private val appVisible get() = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    private fun sharingNow() = (hb.state.me?.share == "on" || hb.state.me?.sos == true) && granted(Manifest.permission.ACCESS_FINE_LOCATION)
    private var sendEveryMs = 120_000L                                 // set by the server: 2 minutes, 15 on a low battery, 10 seconds during an SOS
    private var lastDnsReport = 0L

    /** The server says how often to send. SOS asks the GPS for a fix every few seconds; the battery saver barely wakes it. */
    private fun applyRate(intervalSec: Int, mode: String) {
        sendEveryMs = intervalSec.coerceIn(5, 3600) * 1000L
        when (mode) {
            "sos" -> locationClient?.setRate(5_000L, 0f)
            "saver" -> locationClient?.setRate(600_000L, 100f)
            else -> locationClient?.setRate(60_000L, 25f)
        }
    }

    private fun goForeground() {
        val call = hb.state.call.value
        val inCall = call?.status == "connecting" || call?.status == "active" || (call != null && !call.incoming)
        val text = when {
            hb.state.me?.share == "on" && !hb.store.isParent -> "Sharing your location with your family"
            hb.state.me?.share == "paused" -> "Location sharing is paused"
            else -> "Connected to your family"
        }
        val n = Notifications.service(this, text)
        if (Build.VERSION.SDK_INT >= 29) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            if (sharingNow()) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            if (inCall && granted(Manifest.permission.RECORD_AUDIO)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            runCatching { startForeground(Notifications.ID_SERVICE, n, type) }.onFailure { startForeground(Notifications.ID_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) }
        } else startForeground(Notifications.ID_SERVICE, n)
    }

    /** Keeps the location updates, the notification text and the call notification in step with what is happening. */
    private suspend fun watchState() {
        val app = hb
        scope.launch {
            app.state.family.collect { fam ->
                lockOverlay.update(if (app.store.isParent) null else fam?.members?.firstOrNull { it.id == app.store.myId }?.lock)
                val meNow = fam?.members?.firstOrNull { it.id == app.store.myId }
                withContext(Dispatchers.Main) {                         // a Critical Check-In waiting on the server rings until "I'm OK"
                    if (meNow?.critical != null) CriticalAlarm.start(this@HomebaseService, meNow.critical.by) else CriticalAlarm.stop(this@HomebaseService)
                    if (meNow?.sos == true) applyRate(10, "sos")
                }
                if (System.currentTimeMillis() - lastDnsReport > 30 * 60_000L) {          // tell the family whether the web filter is on (never which sites)
                    lastDnsReport = System.currentTimeMillis()
                    runCatching { app.api.patchMe("safe_dns" to safeDnsStatus(this@HomebaseService)) }
                }
                withContext(Dispatchers.Main) {
                    if (sharingNow()) {
                        if (locationClient == null) locationClient = LocationClient(this@HomebaseService) { fix -> scope.launch { sendFix(fix) } }
                        locationClient?.start()
                    } else locationClient?.stop()
                    goForeground()
                }
            }
        }
        app.state.call.collect { c ->
            withContext(Dispatchers.Main) {
                if (c == null || c.status != "ringing" || !c.incoming) Notifications.cancelCall(this@HomebaseService)
                if (c != null && (c.status == "connecting" || c.status == "active")) goForeground()
            }
        }
    }

    private suspend fun sendFix(fix: Location) {
        val now = System.currentTimeMillis()
        val moved = lastSent?.distanceTo(fix) ?: 1e9f
        val sos = hb.state.me?.sos == true
        val farEnough = if (sendEveryMs > 300_000L) 1000f else 150f
        if (!sos && now - lastSentAt < sendEveryMs && moved < farEnough) return
        lastSent = fix; lastSentAt = now
        hb.store.lastLat = fix.latitude.toFloat(); hb.store.lastLng = fix.longitude.toFloat(); hb.store.lastSentAt = now
        val battery = (getSystemService(Context.BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        runCatching { hb.api.postLocation(fix.latitude, fix.longitude, fix.accuracy, fix.speed, battery) }
            .onSuccess { r ->
                withContext(Dispatchers.Main) { applyRate(r.interval, r.mode) }
                val queued = hb.store.text("trail")
                if (queued.isNotBlank()) {                                   // a trail recorded with no internet goes up now
                    val pts = kotlinx.serialization.json.JsonArray(queued.lines().filter { it.isNotBlank() }.mapNotNull { l ->
                        val p = l.split(","); if (p.size < 5) null else kotlinx.serialization.json.buildJsonObject {
                            put("lat", kotlinx.serialization.json.JsonPrimitive(p[0].toDoubleOrNull() ?: return@mapNotNull null)); put("lng", kotlinx.serialization.json.JsonPrimitive(p[1].toDoubleOrNull() ?: 0.0))
                            put("acc", kotlinx.serialization.json.JsonPrimitive(p[2].toDoubleOrNull() ?: 0.0)); put("speed", kotlinx.serialization.json.JsonPrimitive(p[3].toDoubleOrNull() ?: 0.0)); put("at", kotlinx.serialization.json.JsonPrimitive(p[4].toDoubleOrNull() ?: 0.0))
                        }
                    })
                    if (runCatching { hb.api.postLocations(pts) }.isSuccess) hb.store.saveText("trail", "")
                }
            }
            .onFailure { e ->
                if (e !is ApiError) {                                        // no internet: keep the point for later (the last 300)
                    val line = "${fix.latitude},${fix.longitude},${fix.accuracy},${fix.speed},${now / 1000.0}"
                    hb.store.saveText("trail", (hb.store.text("trail").lines().filter { it.isNotBlank() } + line).takeLast(300).joinToString("\n"))
                }
            }
    }

    private suspend fun eventLoop() {
        val app = hb
        app.state.refreshFamily()
        app.state.refreshItems()
        app.state.refreshPlans()
        app.state.refreshPolls()
        app.state.refreshRewards()
        app.state.refreshNotes()
        app.state.refreshMeals()
        app.state.refreshGroups()
        var cursor = app.store.cursor
        if (cursor < 0) { cursor = runCatching { app.api.events(-1, 0).cursor }.getOrDefault(0); app.store.cursor = cursor }
        var failures = 0
        while (currentCoroutineContext().isActive) {
            try {
                val r = app.api.events(cursor, 25)
                failures = 0
                cursor = r.cursor
                app.store.cursor = cursor
                r.events.forEach { runCatching { handle(it) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError) {
                if (e.code == 401) { app.store.signOut(); stopSelf(); return }
                delay(5000)
            } catch (e: Throwable) {
                failures++
                delay(minOf(30_000L, 2000L * failures))
            }
        }
    }

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull ?: ""
    private fun JsonObject.int(k: String) = this[k]?.jsonPrimitive?.intOrNull ?: 0
    private fun JsonObject.dbl(k: String) = this[k]?.jsonPrimitive?.doubleOrNull ?: 0.0
    private fun JsonObject.flag(k: String) = this[k]?.jsonPrimitive?.booleanOrNull ?: false

    private suspend fun handle(e: Event) {
        val app = hb
        val p = e.payload
        when (e.type) {
            "message" -> {
                val m = Net.decodeFromJsonElement(Message.serializer(), p)
                app.state.add(m)
                val viewing = appVisible && app.state.openThread.value == app.state.threadOf(m)
                if (m.from != app.store.myId && m.kind != "system" && !viewing) {
                    val name = p.str("name")
                    val (title, body) = when (m.kind) {
                        "sos" -> "🚨 $name needs help" to m.body.ifBlank { "Open Mellow to see where they are." }
                        "checkin" -> "✓ $name checked in" to m.body
                        "late" -> "⏰ $name" to m.body
                        "pickup" -> "🚗 $name needs a pickup" to m.body.ifBlank { "Open Mellow to see where they are." }
                        "heart" -> "❤️ $name" to "is thinking of you"
                        "gif" -> name to "🎞️ sent a GIF"
                        "image" -> name to "📷 sent a photo"
                        "game" -> "🎮 ${p.str("name")}" to gameText(m.body)
                        else -> name to m.body
                    }
                    val alert = m.kind == "sos" || m.kind == "checkin" || m.kind == "late" || m.kind == "pickup"
                    val channel = if (m.kind == "sos") Notifications.CH_SOS else if (alert) Notifications.CH_ALERTS else if (p.flag("quiet")) Notifications.CH_QUIET else Notifications.CH_MESSAGES
                    if (m.kind == "sos") app.state.refreshFamily()
                    Notifications.post(this, channel, title, body, "chat:${app.state.threadOf(m)}", m.kind == "sos")
                }
            }
            "location" -> app.state.updateLocation(p.int("member"), Spot(p.dbl("lat"), p.dbl("lng"), p.dbl("acc"), p.dbl("at")), p.int("battery"))
            "place" -> {
                val verb = if (p.str("kind") == "arrive") "arrived at" else "left"
                Notifications.post(this, Notifications.CH_ALERTS, "${p.str("emoji")} ${p.str("place")}", "${p.str("name")} $verb ${p.str("place")}", "map")
            }
            "battery" -> Notifications.post(this, Notifications.CH_ALERTS, "🔋 ${p.str("name")}'s battery is low", "${p.int("battery")}% left", "map")
            "ping" -> {
                app.state.pingAsk.value = PingAsk(p.int("id"), p.int("from"), p.str("name"), p.str("emoji"))
                Notifications.ping(this, p.str("name"), p.int("id"), p.flag("quiet"))
            }
            "ping_result" -> {
                if (p.str("status") == "shared") {
                    val spot = Spot(p.dbl("lat"), p.dbl("lng"), 0.0, System.currentTimeMillis() / 1000.0)
                    app.state.updateLocation(p.int("member"), spot, -1)
                    app.state.showOnMap.tryEmit(spot)
                    Notifications.post(this, Notifications.CH_ALERTS, "📍 ${p.str("name")} shared where they are", "Tap to see them on the map.", "map")
                } else Notifications.post(this, Notifications.CH_MESSAGES, p.str("name"), "Can't share right now. They said not now.", "map")
            }
            "call" -> {
                val id = p.int("id")
                // We may be catching up after being offline: only ring for a call that is still ringing.
                val live = runCatching { app.api.callState(id).status == "ringing" }.getOrDefault(true)
                if (live) withContext(Dispatchers.Main) {
                    app.calls.onIncoming(id, p.int("from"), p.str("name"), p.str("emoji"), p.str("offer"), p.flag("urgent"))
                    if (app.state.call.value?.id == id) Notifications.incomingCall(this@HomebaseService, p.str("name"), p.flag("urgent"))
                }
            }
            "call_update" -> withContext(Dispatchers.Main) {
                val before = app.state.call.value
                app.calls.onUpdate(p.int("id"), p.str("status"), p["answer"]?.jsonPrimitive?.contentOrNull)
                if (p.str("status") == "missed" && before?.incoming == true && before.id == p.int("id")) Notifications.missedCall(this@HomebaseService, before.peerName)
            }
            "ice" -> (p["candidate"] as? JsonObject)?.let { c -> withContext(Dispatchers.Main) { app.calls.onIce(p.int("id"), c) } }
            "nudge" -> Notifications.post(this, Notifications.CH_MESSAGES, "Time for a break?", p.str("text"), "map")
            "ground_warn" -> { val names = (p["apps"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }?.joinToString(", ") ?: "Some apps"; Notifications.post(this, Notifications.CH_ALERTS, "Finish up", "$names will be grounded in a minute.", "map") }
            "chores" -> Unit                                              // the jobs screen reloads itself when it opens
            "member", "places" -> app.state.refreshFamily()
            "reaction" -> {
                val r = (p["reactions"] as? kotlinx.serialization.json.JsonObject)?.mapValues { (_, v) ->
                    (v as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull?.toIntOrNull() } ?: emptyList()
                } ?: emptyMap()
                app.state.setReactions(p.int("id"), r)
            }
            "list" -> app.state.refreshItems()
            "plan" -> {
                app.state.refreshPlans()
                if (p.flag("new")) Notifications.post(this, Notifications.CH_MESSAGES, "📅 ${p.str("name")} planned something", "${p.str("title")} · ${planWhen(p.dbl("start"))}", "plans")
            }
            "plan_soon" -> Notifications.post(this, Notifications.CH_ALERTS, "${p.str("emoji")} Starting soon", "${p.str("title")} · ${planWhen(p.dbl("start"))}", "plans")
            "birthday" -> {
                app.state.refreshFamily()
                if (p.flag("self")) Notifications.post(this, Notifications.CH_MESSAGES, "🎂 Happy birthday, ${p.str("name")}!", "Your family is thinking of you.")
                else Notifications.post(this, Notifications.CH_MESSAGES, "🎂 It's ${p.str("name")}'s birthday", "Send them some love today.", "chat:${p.int("id")}")
            }
            "notice" -> {
                app.state.refreshFamily()
                if (p.str("text").isNotBlank()) Notifications.post(this, Notifications.CH_ALERTS, "\uD83D\uDCE2 ${p.str("name")}", p.str("text"))
            }
            "risk_alert" -> Notifications.post(this, Notifications.CH_ALERTS, "⚠️ Safety alert for ${p.str("name")}", "A message looked like ${p.str("what")}. Open Mellow to see it.", "safety")
            "zone_request" -> { app.state.refreshFamily(); Notifications.post(this, Notifications.CH_MESSAGES, "📍 ${p.str("name")} asks for a zone", p.str("place"), "places") }
            "zone_ok" -> { app.state.refreshFamily(); Notifications.post(this, Notifications.CH_MESSAGES, if (p.flag("approved")) "📍 ${p.str("place")} is a zone now" else "${p.str("name")} said not now", p.str("place"), "places") }
            "offline" -> Notifications.post(this, Notifications.CH_ALERTS, "📵 ${p.str("name")}'s phone went offline", "Not heard from for ${p.int("minutes")} minutes" + (if (p.int("battery") >= 0) " (battery was ${p.int("battery")}%)" else ""), "map")
            "online" -> Notifications.post(this, Notifications.CH_MESSAGES, "✅ ${p.str("name")}'s phone is back online", "", "map")
            "critical" -> { app.state.refreshFamily(); withContext(Dispatchers.Main) { CriticalAlarm.start(this@HomebaseService, p.str("name")) } }
            "critical_ok" -> Notifications.post(this, Notifications.CH_ALERTS, "✅ ${p.str("name")} is OK", "They answered your Critical Check-In.", "map")
            "ring" -> withContext(Dispatchers.Main) { CriticalAlarm.start(this@HomebaseService, p.str("name"), findPhone = true) }
            "walk", "walk_done", "walk_late", "walk_still", "expect_ok", "expect_late", "speeding", "wallet", "wallet_request", "kudos", "bedtime", "homework_due" -> {
                if (e.type.startsWith("walk")) app.state.refreshFamily()
                val loud = e.type in setOf("walk_late", "walk_still", "expect_late", "speeding")
                Notifications.post(this, if (loud) Notifications.CH_ALERTS else Notifications.CH_MESSAGES, p.str("title"), p.str("text"), if (e.type.startsWith("wallet")) "wallet" else if (e.type == "kudos") "kudos" else "map")
            }
            "meals" -> app.state.refreshMeals()
            "meal_suggest" -> {
                app.state.refreshMeals()
                Notifications.post(this, Notifications.CH_MESSAGES, "💡 ${p.str("name")} suggests a meal", p.str("title"), "meals")
            }
            "meal_ok" -> {
                app.state.refreshMeals()
                Notifications.post(this, Notifications.CH_MESSAGES, if (p.flag("approved")) "✅ ${p.str("name")} said yes to your meal" else "${p.str("name")} said not now", p.str("title"), "meals")
            }
            "group" -> {
                app.state.refreshGroups()
                if (p.flag("approved")) Notifications.post(this, Notifications.CH_MESSAGES, "✅ A parent said yes", "You are in ${p.str("group")}.", "groups")
                if (p.flag("declined")) Notifications.post(this, Notifications.CH_MESSAGES, "A parent said not now", p.str("group"), "groups")
            }
            "group_request" -> {
                app.state.refreshGroups()
                Notifications.post(this, Notifications.CH_ALERTS, "👥 ${p.str("name")} wants to join ${p.str("group")}", "Open Mellow to say yes or not now.", "groups")
            }
            "group_flag" -> {
                app.state.refreshGroups()
                Notifications.post(this, Notifications.CH_ALERTS, "⚠️ ${p.str("name")}'s message was censored", "In ${p.str("group")}. Open Mellow to read it.", "group:${p.int("gid")}")
            }
            "gmessage" -> {
                app.state.refreshGroups()
                if (!(appVisible && app.state.openGroup.value == p.int("gid"))) {
                    Notifications.post(this, Notifications.CH_MESSAGES, "${p.str("name")} · ${p.str("group")}", if (p.str("kind") == "gif") "🎞️ sent a GIF" else p.str("body"), "group:${p.int("gid")}")
                }
            }
            "rewards" -> app.state.refreshRewards()
            "board" -> app.state.refreshNotes()
            "reward" -> {
                app.state.refreshRewards()
                val (title, body) = when (p.str("status")) {
                    "pending" -> "🎁 ${p.str("name")} asked for a reward" to "${p.str("title")} · ${p.int("cost")} points"
                    "approved" -> "🎁 ${p.str("name")} said yes" to (p.str("title") + if (p.int("minutes") > 0) " · +${p.int("minutes")} min screen time today" else "")
                    else -> "${p.str("name")} said not now" to p.str("title")
                }
                Notifications.post(this, Notifications.CH_MESSAGES, title, body, "rewards")
            }
            "poll" -> {
                app.state.refreshPolls()
                if (p.flag("new")) Notifications.post(this, Notifications.CH_MESSAGES, "🗳️ ${p.str("name")} asks the family", p.str("question"), "plans")
            }
        }
    }
}
