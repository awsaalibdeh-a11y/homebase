package app.homebase.family.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.homebase.family.ScreenTimeTracker
import app.homebase.family.hb
import kotlinx.coroutines.delay

fun askBatteryExemption(ctx: Context) {
    val pm = ctx.getSystemService(PowerManager::class.java)
    if (!pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
        runCatching { ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

private fun granted(ctx: Context, permission: String) = ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

/** What this install still needs before everything works. An empty list means it is all set up. */
fun missingSetup(ctx: Context): List<String> {
    val out = mutableListOf<String>()
    if (!granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)) out += "location"
    else if (Build.VERSION.SDK_INT >= 29 && !granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)) out += "location all the time"
    if (Build.VERSION.SDK_INT >= 33 && !granted(ctx, Manifest.permission.POST_NOTIFICATIONS)) out += "notifications"
    if (!granted(ctx, Manifest.permission.RECORD_AUDIO)) out += "microphone"
    if (!ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)) out += "battery"
    if (!ctx.hb.store.isParent && !ScreenTimeTracker.hasAccess(ctx)) out += "screen time"
    return out
}

@Composable
private fun Reason(emoji: String, title: String, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Text(emoji, fontSize = 26.sp, modifier = Modifier.padding(end = 14.dp))
        androidx.compose.foundation.layout.Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = Muted)
        }
    }
}

/** A fresh install sets itself up: this page explains each step, then starts asking by itself. */
@Composable
fun PermissionsScreen(nav: Nav) {
    val ctx = LocalContext.current
    fun finish() { ctx.hb.store.onboarded = true; startHomebase(ctx); nav.reset(if (ctx.hb.store.tourDone) Screen.Main else Screen.Tour) }
    val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> askBatteryExemption(ctx); finish() }
    val first = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.ACCESS_FINE_LOCATION] == true && Build.VERSION.SDK_INT >= 29) background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        else { askBatteryExemption(ctx); finish() }
    }
    var started by remember { mutableStateOf(false) }
    var skipped by remember { mutableStateOf(false) }
    fun begin() {
        if (started || skipped) return
        started = true
        val list = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
        first.launch(list.toTypedArray())
    }
    LaunchedEffect(Unit) { delay(2000); begin() }                                   // nobody has to hunt for a button: setup starts by itself
    Page {
        Heading("Setting everything up", "Mellow asks for what it needs, one step after another. You can change any of it later in Android settings.")
        Reason("📍", "Location", "So your family can see you on the map. Choose \"Allow all the time\" so it keeps working when the screen is off. You can pause sharing any time.")
        Reason("🔔", "Notifications", "For messages, check-ins, and the ring of an incoming call.")
        Reason("🎙️", "Microphone", "Only used while you are on a call you answered or made. Never otherwise.")
        Reason("🔋", "Battery", "Tell Android not to put Mellow to sleep, so a call can ring when the device is idle.")
        if (!ctx.hb.store.isParent) Reason("⏱️", "Screen time", "Mellow counts only time spent playing or watching, so your family can see it. You see exactly what they see.")
        Gap(16)
        BigButton(if (started) "Setting up…" else "Set everything up", enabled = !started) { begin() }
        Gap(4)
        TextButton({ skipped = true; finish() }, Modifier.fillMaxWidth()) { Text("Skip for now") }
    }
}

/** Shown on the main screens until everything is allowed. One tap does the next step, and it keeps going each time you come back. */
@Composable
fun SetupBanner() {
    val ctx = LocalContext.current
    var missing by remember { mutableStateOf(missingSetup(ctx)) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val watcher = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) missing = missingSetup(ctx) }
        owner.lifecycle.addObserver(watcher)
        onDispose { owner.lifecycle.removeObserver(watcher) }
    }
    val asked = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { missing = missingSetup(ctx) }
    val allTheTime = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { missing = missingSetup(ctx) }
    if (missing.isEmpty()) return
    fun next() {
        val ask = mutableListOf<String>()
        if ("location" in missing) { ask += Manifest.permission.ACCESS_FINE_LOCATION; ask += Manifest.permission.ACCESS_COARSE_LOCATION }
        if ("microphone" in missing) ask += Manifest.permission.RECORD_AUDIO
        if ("notifications" in missing && Build.VERSION.SDK_INT >= 33) ask += Manifest.permission.POST_NOTIFICATIONS
        when {
            ask.isNotEmpty() -> asked.launch(ask.toTypedArray())
            "location all the time" in missing && Build.VERSION.SDK_INT >= 29 -> allTheTime.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            "battery" in missing -> askBatteryExemption(ctx)
            "screen time" in missing -> ScreenTimeTracker.openAccessSettings(ctx)
        }
    }
    SoftCard(tint = SunSoft) {
        Text("📲 Finish setting up Mellow", style = MaterialTheme.typography.titleMedium)
        Text("Still to allow: " + missing.joinToString(", "), color = Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
        SoftButton("Continue", Modifier.padding(top = 10.dp)) { next() }
    }
}
