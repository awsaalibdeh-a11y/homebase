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
        runCatching { ctx.hb.api.send(kind, body, to, spot?.lat, spot?.lng) }
            .onSuccess { ctx.hb.state.add(it); toast(ctx, done) }
            .onFailure { toast(ctx, it.message ?: "Could not send") }
    }
}

/** "Where are you?" is a request. The other person decides whether to answer. */
fun askWhere(ctx: Context, m: Member) {
    ctx.hb.scope.launch {
        runCatching { ctx.hb.api.ping(m.id) }
            .onSuccess { toast(ctx, "Asked ${m.name}. They can answer or say not now.") }
            .onFailure { toast(ctx, it.message ?: "Could not ask") }
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
        if (ok && w != null) ctx.hb.calls.start(w.first, w.second) else if (!ok) toast(ctx, "Homebase needs the microphone to make calls.")
    }
    return { member, urgent ->
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) ctx.hb.calls.start(member, urgent)
        else { waiting = member to urgent; ask.launch(Manifest.permission.RECORD_AUDIO) }
    }
}
