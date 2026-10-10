package app.homebase.family.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
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
import app.homebase.family.hb
import kotlinx.coroutines.launch

@Composable
fun ListsScreen(nav: Nav) {
    val ctx = LocalContext.current
    val items by ctx.hb.state.items.collectAsState()
    val fam by ctx.hb.state.family.collectAsState()
    var text by remember { mutableStateOf("") }
    val api = ctx.hb.api
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { TextButton({ nav.back() }) { Text("Back") } }
        Heading("Family list", "Groceries, chores, plans. Anyone can add things and tick them off.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Add something") })
            TextButton({
                if (text.isNotBlank()) ctx.hb.scope.launch { runCatching { api.addItem(text.trim()) }.onSuccess { text = ""; ctx.hb.state.refreshItems() } }
            }) { Text("Add") }
        }
        items.forEach { item ->
            SoftCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(item.done, { ctx.hb.scope.launch { runCatching { api.tick(item.id, !item.done) }; ctx.hb.state.refreshItems() } })
                    Column(Modifier.weight(1f)) {
                        Text(item.text, style = MaterialTheme.typography.bodyLarge, color = if (item.done) Muted else Ink)
                        val who = fam?.members?.firstOrNull { m -> m.id == (if (item.done) item.doneBy else item.by) }?.name
                        if (who != null) Text(if (item.done) "Done by $who" else "Added by $who", style = MaterialTheme.typography.bodyMedium, color = Muted)
                    }
                    if (item.by == ctx.hb.store.myId || ctx.hb.store.isParent) TextButton({ ctx.hb.scope.launch { runCatching { api.dropItem(item.id) }; ctx.hb.state.refreshItems() } }) { Text("✕") }
                }
            }
        }
        if (items.isEmpty()) CenterNote("Nothing on the list.")
    }
}

@Composable
fun InviteScreen(nav: Nav) {
    val ctx = LocalContext.current
    var role by remember { mutableStateOf("child") }
    var code by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { TextButton({ nav.back() }) { Text("Back") } }
        Heading("Invite someone", "They install Homebase, choose Join with a code, and type this in. A child sees exactly what is shared before saying yes.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton({ role = "child"; code = null }) { Text(if (role == "child") "● A child" else "A child") }
            TextButton({ role = "parent"; code = null }) { Text(if (role == "parent") "● A parent" else "A parent") }
        }
        if (code == null) {
            BigButton(if (busy) "Making a code…" else "Make a code", enabled = !busy) {
                busy = true
                ctx.hb.scope.launch {
                    runCatching { ctx.hb.api.invite(role) }.onSuccess { code = it.code }.onFailure { toast(ctx, it.message ?: "Could not make a code") }
                    busy = false
                }
            }
        } else {
            SoftCard(tint = BlueSoft) {
                Text(code!!, style = MaterialTheme.typography.headlineLarge, color = BlueDark)
                Text("Works once, for 24 hours.", color = Muted)
            }
            BigButton("Share the code") {
                val msg = "Join our family on Homebase. Your code is ${code}. Get the app here: ${ctx.hb.store.server}/"
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, msg), "Share the code").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
