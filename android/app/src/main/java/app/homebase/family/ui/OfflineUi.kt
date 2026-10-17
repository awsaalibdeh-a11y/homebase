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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.homebase.family.Net
import app.homebase.family.Notifications
import app.homebase.family.hb
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

// Everything on this page stays on this phone. It needs no signal, and it is never sent to the family or the server.

private val CARD_FIELDS = listOf(
    "blood" to "Blood type", "allergies" to "Allergies", "meds" to "Medicines and conditions",
    "doctor" to "Doctor", "doctorPhone" to "Doctor's phone",
    "c1Name" to "Emergency contact 1", "c1Phone" to "Phone", "c2Name" to "Emergency contact 2", "c2Phone" to "Phone",
    "address" to "Home address", "notes" to "Anything else a helper should know",
)

private fun loadCard(ctx: android.content.Context): Map<String, String> =
    runCatching { Net.decodeFromString<Map<String, String>>(ctx.hb.store.text("emergency")) }.getOrDefault(emptyMap())

private fun dial(ctx: android.content.Context, number: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number.trim()))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { toast(ctx, "This device cannot make calls") }
}

/** An emergency card you can read with no signal: medical notes, who to call, one tap to dial. Saved on this phone only. */
@Composable
fun EmergencyScreen(nav: Nav) {
    val ctx = LocalContext.current
    var data by remember { mutableStateOf(loadCard(ctx)) }
    var editing by remember { mutableStateOf(data.values.all { it.isBlank() }) }
    var draft by remember { mutableStateOf(data) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { TextButton({ nav.back() }) { Text("Back") } }
        Heading("Emergency card", "Works with no signal. Saved on this phone only: it is never shared with the family or the server.")
        if (editing) {
            CARD_FIELDS.forEach { (key, label) ->
                OutlinedTextField(draft[key].orEmpty(), { draft = draft + (key to it.take(200)) }, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = key != "notes" && key != "meds")
            }
            BigButton("Save") { ctx.hb.store.saveText("emergency", Net.encodeToString(draft)); data = draft; editing = false; toast(ctx, "Saved on this phone") }
        } else {
            SoftCard(tint = RoseSoft) {
                Text("🆘 In an emergency", style = MaterialTheme.typography.titleMedium)
                Text("112 reaches emergency services on most phones in most countries. Use your local number if you know it.", color = Muted, style = MaterialTheme.typography.bodyMedium)
                BigButton("Dial 112", Modifier.padding(top = 8.dp), color = Rose) { dial(ctx, "112") }
            }
            SoftCard {
                CARD_FIELDS.filter { data[it.first].orEmpty().isNotBlank() }.forEach { (key, label) ->
                    val v = data[key].orEmpty()
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(label, color = Muted, fontSize = 12.sp); Text(v, style = MaterialTheme.typography.bodyLarge) }
                        if (key.endsWith("Phone")) TextButton({ dial(ctx, v) }) { Text("Call") }
                    }
                }
            }
            SoftButton("Edit the card") { draft = data; editing = true }
            val askSms = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok -> toast(ctx, if (ok) "SOS will be texted to your contacts when there is no internet" else "Texting stays off") }
            SoftCard {
                Text("📵 SOS with no internet", style = MaterialTheme.typography.titleMedium)
                Text(if (app.homebase.family.OfflineSos.allowed(ctx)) "On. If an SOS cannot reach the internet, Mellow texts your location to the emergency contacts above and the trusted adults." else "Let Mellow text your location to the emergency contacts above if you send an SOS with no internet. Normal text charges apply.", color = Muted)
                if (!app.homebase.family.OfflineSos.allowed(ctx)) TextButton({ askSms.launch(android.Manifest.permission.SEND_SMS) }) { Text("Allow texting an SOS") }
            }
        }
    }
}

@Serializable
private class PocketNote(val id: Long, val title: String, val body: String)

private fun loadNotes(ctx: android.content.Context): List<PocketNote> =
    runCatching { Net.decodeFromString<List<PocketNote>>(ctx.hb.store.text("notes")) }.getOrDefault(emptyList())

/** A break timer and private notes. Both work with no signal and stay on this phone. */
@Composable
fun PocketScreen(nav: Nav) {
    val ctx = LocalContext.current
    val state = ctx.hb.state
    val end by state.timerEnd.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var notes by remember { mutableStateOf(loadNotes(ctx)) }
    var editing by remember { mutableStateOf<PocketNote?>(null) }
    LaunchedEffect(end) { while (end > System.currentTimeMillis()) { now = System.currentTimeMillis(); delay(500) } }
    fun saveNotes(list: List<PocketNote>) { notes = list; ctx.hb.store.saveText("notes", Net.encodeToString(list)) }
    fun startTimer(minutes: Int) {
        val at = System.currentTimeMillis() + minutes * 60_000L
        state.timerEnd.value = at
        ctx.hb.scope.launch {
            delay(minutes * 60_000L)
            if (state.timerEnd.value == at) {
                state.timerEnd.value = 0L
                buzz(ctx)
                Notifications.post(ctx, Notifications.CH_ALERTS, "⏱️ Time is up", "Your $minutes minute timer has finished.")
            }
        }
        toast(ctx, "Timer started")
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { TextButton({ nav.back() }) { Text("Back") } }
        Heading("Pocket", "Little tools that work with no signal. They stay on this phone.")
        SectionLabel("⏱️ Break timer")
        SoftCard {
            val left = ((end - now) / 1000).coerceAtLeast(0)
            if (end > now) {
                Text("%02d:%02d".format(left / 60, left % 60), style = MaterialTheme.typography.headlineLarge)
                Text("Counting down. It buzzes and tells you when it is done.", color = Muted)
                TextButton({ state.timerEnd.value = 0L }) { Text("Stop") }
            } else {
                Text("Pick a time for a break from the screen, homework or cooking.", color = Muted)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10, 15, 30).forEach { m -> Pill("$m min", false) { startTimer(m) } }
                }
            }
        }
        SectionLabel("🔒 Private notes")
        notes.forEach { n ->
            SoftCard(onClick = { editing = n }) {
                Text(n.title.ifBlank { "Note" }, style = MaterialTheme.typography.titleMedium)
                if (n.body.isNotBlank()) Text(n.body, color = Muted, maxLines = 3)
            }
        }
        if (notes.isEmpty()) CenterNote("No notes yet. Only you can see them.")
        SoftButton("＋ New note") { editing = PocketNote(System.currentTimeMillis(), "", "") }
    }
    editing?.let { n ->
        var title by remember(n.id) { mutableStateOf(n.title) }
        var body by remember(n.id) { mutableStateOf(n.body) }
        AlertDialog(
            onDismissRequest = { editing = null },
            containerColor = Surface,
            title = { Text("Note") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(title, { title = it.take(60) }, Modifier.fillMaxWidth(), placeholder = { Text("Title") }, singleLine = true)
                    OutlinedTextField(body, { body = it.take(2000) }, Modifier.fillMaxWidth(), placeholder = { Text("Write here") }, minLines = 4)
                }
            },
            confirmButton = {
                TextButton({
                    if (title.isBlank() && body.isBlank()) { editing = null; return@TextButton }
                    val saved = PocketNote(n.id, title.trim(), body.trim())
                    saveNotes(if (notes.any { it.id == n.id }) notes.map { if (it.id == n.id) saved else it } else listOf(saved) + notes)
                    editing = null
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    if (notes.any { it.id == n.id }) TextButton({ saveNotes(notes.filter { it.id != n.id }); editing = null }) { Text("Delete", color = Rose) }
                    TextButton({ editing = null }) { Text("Cancel") }
                }
            },
        )
    }
}
