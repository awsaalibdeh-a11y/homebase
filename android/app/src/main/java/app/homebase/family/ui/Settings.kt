package app.homebase.family.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.homebase.family.BuildConfig
import app.homebase.family.HomebaseService
import app.homebase.family.hb
import kotlinx.coroutines.launch

@Composable
fun TransparencyScreen(nav: Nav) {
    val ctx = LocalContext.current
    val fam by ctx.hb.state.family.collectAsState()
    val me = fam?.members?.firstOrNull { it.id == ctx.hb.store.myId }
    var confirm by remember { mutableStateOf(false) }
    val parents = fam?.members.orEmpty().filter { it.isParent && it.id != ctx.hb.store.myId }.joinToString { it.name }.ifBlank { "the other parents" }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { TextButton({ nav.back() }) { Text("Back") } }
        Heading("What my family can see", "This is everything. If it is not on this page, Homebase does not share it.")
        SoftCard(tint = if (me?.share == "on") MintSoft else SunSoft) {
            Text("Location: ${when (me?.share) { "on" -> "shared"; "paused" -> "paused"; else -> "not shared" }}", style = MaterialTheme.typography.titleMedium)
            Text(if (me?.share == "on") "Everyone in the family can see where you are on the map. Last update: ${timeAgo(ctx.hb.store.lastSentAt / 1000.0)}." else "Nobody can see your location right now.", color = Muted)
        }
        SoftCard {
            Text("Also shared with your location", style = MaterialTheme.typography.titleMedium)
            Text("Your battery level, so they know if your phone is about to die. Nothing else from your phone.", color = Muted)
        }
        SoftCard {
            Text("Saved places", style = MaterialTheme.typography.titleMedium)
            Text("When you arrive at or leave a place on the list, everyone gets a message, including you.", color = Muted)
        }
        SoftCard {
            Text("Messages and calls", style = MaterialTheme.typography.titleMedium)
            Text("Only inside Homebase. It never reads your other messages or apps, and a call only starts when someone answers.", color = Muted)
        }
        SoftCard {
            Text("How long it is kept", style = MaterialTheme.typography.titleMedium)
            Text("Location history is deleted after 7 days. $parents can see your last 24 hours as a trail, and you can see it too.", color = Muted)
        }
        SoftButton("See my trail") { nav.go(Screen.History(ctx.hb.store.myId)) }
        SoftButton("Delete my location history now") { confirm = true }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Delete your location history?") },
        text = { Text("Your saved trail is removed and your last position disappears from the map until you share again.") },
        confirmButton = { TextButton({ confirm = false; ctx.hb.scope.launch { runCatching { ctx.hb.api.deleteHistory() }.onSuccess { toast(ctx, "Deleted"); ctx.hb.state.refreshFamily() } } }) { Text("Delete") } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } })
}

@Composable
fun SettingsScreen(nav: Nav) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(ctx.hb.store.myName) }
    var server by remember { mutableStateOf(ctx.hb.store.server) }
    var leave by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { TextButton({ nav.back() }) { Text("Back") } }
        Heading("Settings")
        OutlinedTextField(name, { name = it }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        SoftButton("Save name") { ctx.hb.scope.launch { runCatching { ctx.hb.api.patchMe("name" to name) }.onSuccess { ctx.hb.store.myName = name; ctx.hb.state.refreshFamily(); toast(ctx, "Saved") } } }
        SoftButton("Let Homebase ring when the tablet is idle") { askBatteryExemption(ctx) }
        SoftButton("Check for a newer version") {
            ctx.hb.scope.launch {
                runCatching { ctx.hb.api.config() }.onSuccess { c ->
                    if (c.versionCode > BuildConfig.VERSION_CODE) { toast(ctx, "A new version is ready"); ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(c.apkUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    else toast(ctx, "You have the newest version")
                }.onFailure { toast(ctx, "Could not check") }
            }
        }
        SoftButton("What Homebase will never do") { nav.go(Screen.About) }
        Gap(6)
        SectionLabel("Advanced")
        OutlinedTextField(server, { server = it }, label = { Text("Server address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        SoftButton("Save server address") { ctx.hb.store.server = server; toast(ctx, "Saved. Restart the app to use it.") }
        Gap(6)
        BigButton("Leave the family", color = Rose) { leave = true }
        Text("Homebase ${BuildConfig.VERSION_NAME}", color = Muted, modifier = Modifier.padding(top = 8.dp))
    }
    if (leave) AlertDialog(onDismissRequest = { leave = false }, title = { Text("Leave the family?") },
        text = { Text("Everything you shared is deleted and you stop seeing the family. This cannot be undone.") },
        confirmButton = {
            TextButton({
                leave = false
                ctx.hb.scope.launch {
                    runCatching { ctx.hb.api.leave() }
                    ctx.stopService(Intent(ctx, HomebaseService::class.java))
                    ctx.hb.store.signOut(); ctx.hb.state.family.value = null
                    nav.reset(Screen.Welcome)
                }
            }) { Text("Leave") }
        },
        dismissButton = { TextButton({ leave = false }) { Text("Stay") } })
}
