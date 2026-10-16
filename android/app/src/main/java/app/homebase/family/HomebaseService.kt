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
    private fun sharingNow() = hb.state.me?.share == "on" && granted(Manifest.permission.ACCESS_FINE_LOCATION)

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
        if (now - lastSentAt < 20_000 && moved < 15f) return
        lastSent = fix; lastSentAt = now
        hb.store.lastLat = fix.latitude.toFloat(); hb.store.lastLng = fix.longitude.toFloat(); hb.store.lastSentAt = now
        val battery = (getSystemService(Context.BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        runCatching { hb.api.postLocation(fix.latitude, fix.longitude, fix.accuracy, fix.speed, battery) }
    }

    private suspend fun eventLoop() {
        val app = hb
        app.state.refreshFamily()
        app.state.refreshItems()
        app.state.refreshPlans()
        app.state.refreshPolls()
        app.state.refreshRewards()
        app.state.refreshNotes()
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
                        "sos" -> "🚨 $name needs help" to m.body.ifBlank { "Open Homebase to see where they are." }
                        "checkin" -> "✓ $name checked in" to m.body
                        "late" -> "⏰ $name" to m.body
                        "pickup" -> "🚗 $name needs a pickup" to m.body.ifBlank { "Open Homebase to see where they are." }
                        "heart" -> "❤️ $name" to "is thinking of you"
                        else -> name to m.body
                    }
                    val alert = m.kind == "sos" || m.kind == "checkin" || m.kind == "late" || m.kind == "pickup"
                    val channel = if (alert) Notifications.CH_ALERTS else if (p.flag("quiet")) Notifications.CH_QUIET else Notifications.CH_MESSAGES
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
            "rewards" -> app.state.refreshRewards()
            "board" -> app.state.refreshNotes()
            "reward" -> {
                app.state.refreshRewards()
                val (title, body) = when (p.str("status")) {
                    "pending" -> "🎁 ${p.str("name")} asked for a reward" to "${p.str("title")} · ${p.int("cost")} points"
                    "approved" -> "🎁 ${p.str("name")} said yes" to p.str("title")
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
