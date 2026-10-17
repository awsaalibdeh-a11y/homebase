package app.homebase.family.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import app.homebase.family.ApiError
import app.homebase.family.LocationClient
import app.homebase.family.Member
import app.homebase.family.Spot
import app.homebase.family.hb
import kotlinx.coroutines.launch

fun toast(ctx: Context, text: String) = Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()

fun lastSpot(ctx: Context): Spot? {
    LocationClient(ctx) {}.lastKnown()?.let { return Spot(it.latitude, it.longitude, it.accuracy.toDouble(), it.time / 1000.0) }
    val s = ctx.hb.store
    return if (s.lastSentAt > 0) Spot(s.lastLat.toDouble(), s.lastLng.toDouble(), 0.0, s.lastSentAt / 1000.0) else null
}

/** Sends a check-in style message. Location is attached only to check-ins and SOS, and only if the phone has one. */
fun sendQuick(ctx: Context, kind: String, body: String, to: Int = 0, withSpot: Boolean = false, done: String = "Sent") {
    ctx.hb.scope.launch {
        val spot = if (withSpot) lastSpot(ctx) else null
        runCatching { ctx.hb.api.sendFull(kind, body, to, spot?.lat, spot?.lng) }
            .onSuccess { r ->
                ctx.hb.state.add(r.message); toast(ctx, done)
                r.support?.let { ctx.hb.state.support.value = it }
                if (kind == "sos" || kind == "checkin") ctx.hb.state.refreshFamily()      // SOS starts live location; "I'm OK" ends it
                if (kind == "checkin") app.homebase.family.CriticalAlarm.stop(ctx)
            }
            .onFailure {
                if (it is ApiError) toast(ctx, it.message ?: "Could not send")
                else {
                    ctx.hb.state.queueSend(kind, body, to, spot?.lat, spot?.lng)
                    val texted = if (kind == "sos") app.homebase.family.OfflineSos.send(ctx, spot?.lat, spot?.lng) else 0
                    toast(ctx, if (texted > 0) "No internet: SOS texted to $texted people with your location. It also goes to the family when you are back online." else "You are offline. Saved: it sends by itself when you have signal.")
                }
            }
    }
}

/** "Running late": the first choice sends where you are, and the server works out how far from Home that is. */
val LATE_CHOICES = listOf("📍 Work it out from where I am" to 0, "About 10 minutes" to 10, "About 20 minutes" to 20, "About 30 minutes" to 30, "About an hour" to 60)

fun lateSend(ctx: Context, minutes: Int) {
    if (minutes == 0) sendQuick(ctx, "late", "", 0, true, "Sent, with how far from home you are")
    else sendQuick(ctx, "late", "Running about $minutes minutes late", 0, false, "Sent")
}

/** "Where are you?" is a request. The other person decides whether to answer. */
fun askWhere(ctx: Context, m: Member) {
    ctx.hb.scope.launch {
        runCatching { ctx.hb.api.ping(m.id) }
            .onSuccess { toast(ctx, "Asked ${m.name}. They can answer or say not now.") }
            .onFailure { toast(ctx, it.message ?: "Could not ask") }
    }
}

/** Starts a video call: needs the microphone and the camera, asked for together. */
@Composable
fun rememberVideoCaller(): (Member) -> Unit {
    val ctx = LocalContext.current
    var waiting by remember { mutableStateOf<Member?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        val w = waiting
        waiting = null
        if (w != null && r[Manifest.permission.RECORD_AUDIO] == true) ctx.hb.calls.start(w, false, r[Manifest.permission.CAMERA] == true)
        else toast(ctx, "Mellow needs the microphone to make calls.")
    }
    return { member ->
        val mic = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val cam = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (mic && cam) ctx.hb.calls.start(member, false, true)
        else { waiting = member; ask.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)) }
    }
}

/** Gives back a function that starts a call, asking for the microphone first if Android has not allowed it yet. */
@Composable
fun rememberCaller(): (Member, Boolean) -> Unit {
    val ctx = LocalContext.current
    var waiting by remember { mutableStateOf<Pair<Member, Boolean>?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val w = waiting
        waiting = null
        if (ok && w != null) ctx.hb.calls.start(w.first, w.second) else if (!ok) toast(ctx, "Mellow needs the microphone to make calls.")
    }
    return { member, urgent ->
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) ctx.hb.calls.start(member, urgent)
        else { waiting = member to urgent; ask.launch(Manifest.permission.RECORD_AUDIO) }
    }
}
