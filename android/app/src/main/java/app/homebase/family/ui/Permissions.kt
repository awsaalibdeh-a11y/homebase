package app.homebase.family.ui

import android.Manifest
import android.content.Context
import android.content.Intent
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.homebase.family.hb

fun askBatteryExemption(ctx: Context) {
    val pm = ctx.getSystemService(PowerManager::class.java)
    if (!pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
        runCatching { ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
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

@Composable
fun PermissionsScreen(nav: Nav) {
    val ctx = LocalContext.current
    fun finish() { ctx.hb.store.onboarded = true; startHomebase(ctx); nav.reset(if (ctx.hb.store.tourDone) Screen.Main else Screen.Tour) }
    val battery = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> askBatteryExemption(ctx); finish() }
    val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> askBatteryExemption(ctx); finish() }
    val first = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.ACCESS_FINE_LOCATION] == true && Build.VERSION.SDK_INT >= 29) background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        else { askBatteryExemption(ctx); finish() }
    }
    Page {
        Heading("A few permissions", "Homebase only asks for what it needs. You can change any of them later in Android settings.")
        Reason("📍", "Location", "So your family can see you on the map. Choose \"Allow all the time\" so it keeps working when the screen is off. You can pause sharing any time.")
        Reason("🔔", "Notifications", "For messages, check-ins, and the ring of an incoming call.")
        Reason("🎙️", "Microphone", "Only used while you are on a call you answered or made. Never otherwise.")
        Reason("🔋", "Battery", "Tell Android not to put Homebase to sleep, so a call can ring when the device is idle.")
        Gap(16)
        BigButton("Allow") {
            val list = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
            first.launch(list.toTypedArray())
        }
        Gap(4)
        TextButton({ finish() }, Modifier.fillMaxWidth()) { Text("Skip for now") }
    }
}
