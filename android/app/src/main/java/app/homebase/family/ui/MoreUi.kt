package app.homebase.family.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.homebase.family.BoardItem
import app.homebase.family.hb
import kotlinx.coroutines.launch

/** What the points from jobs can buy. A child asks, a parent says yes or not now, and the points are held until then. */
@Composable
fun RewardsScreen(nav: Nav) {
    val ctx = LocalContext.current
    val app = ctx.hb
    val info by app.state.rewards.collectAsState()
    val fam by app.state.family.collectAsState()
    val me = app.store.myId
    val parent = app.store.isParent
    var adding by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { app.state.refreshRewards() }
    val myPoints = info.balance[me.toString()] ?: 0
    val nameOf: (Int) -> String = { id -> fam?.members?.firstOrNull { it.id == id }?.name ?: "Someone" }
    val act: (suspend () -> Unit) -> Unit = { block ->
        app.scope.launch {
            runCatching { block() }.onFailure { toast(ctx, it.message ?: "Something went wrong") }
            app.state.refreshRewards()
        }
    }
    val pending = info.requests.filter { it.status == "pending" }
    val past = info.requests.filter { it.status != "pending" }.take(4)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { TextButton({ nav.back() }) { Text("Back") } }
        Heading("Rewards", if (parent) "What the points from jobs can buy. Children ask, you decide." else "Spend the points you earn from jobs.")
        SoftCard(tint = AccentSoft) {
            if (parent) {
                Text("Points to spend", style = MaterialTheme.typography.titleMedium)
                val kids = fam?.members.orEmpty().filter { !it.isParent }
                if (kids.isEmpty()) Text("No children yet.", color = Muted)
                kids.forEach { k -> Text("${k.emoji} ${k.name} · ${info.balance[k.id.toString()] ?: 0} points", Modifier.padding(top = 4.dp)) }
            } else {
                Text("⭐ You have $myPoints points", style = MaterialTheme.typography.titleMedium)
                Text("Points come from jobs a parent approves.", color = Muted)
            }
        }
        if (pending.isNotEmpty()) {
            SectionLabel(if (parent) "Waiting for you" else "Your requests")
            pending.forEach { r ->
                SoftCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(r.emoji, fontSize = 26.sp)
                        Column(Modifier.weight(1f)) {
                            Text(r.title, style = MaterialTheme.typography.titleMedium)
                            Text(if (parent) "${nameOf(r.member)} · ${r.cost} points" else "Waiting for a parent · ${r.cost} points held", color = Muted, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (parent) {
                            Pill("Yes", true) { act { app.api.decide(r.id, true) } }
                            Pill("Not now", false) { act { app.api.decide(r.id, false) } }
                        } else {
                            Pill("Take it back", false) { act { app.api.cancelRedemption(r.id) } }
                        }
                    }
                }
            }
        }
        SectionLabel("Rewards")
        info.rewards.forEach { r ->
            SoftCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(r.emoji, fontSize = 26.sp)
                    Column(Modifier.weight(1f)) {
                        Text(r.title, style = MaterialTheme.typography.titleMedium)
                        Text("${r.cost} points", color = Muted, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (parent) {
                        TextButton({ act { app.api.dropReward(r.id) } }) { Text("✕") }
                    } else {
                        val can = myPoints >= r.cost
                        Pill(if (can) "Ask for it" else "Need ${r.cost - myPoints}", can) {
                            if (can) act { app.api.redeem(r.id); toast(ctx, "Asked. A parent will answer.") } else toast(ctx, "You need ${r.cost - myPoints} more points")
                        }
                    }
                }
            }
        }
        if (info.rewards.isEmpty()) CenterNote(if (parent) "Add something points can buy: a film pick, extra game time, an ice cream." else "No rewards yet. Ask a parent to add some.")
        if (parent) SoftButton("＋ Add a reward") { adding = true }
        if (past.isNotEmpty()) {
            SectionLabel("Recently")
            past.forEach { Text("${it.emoji} ${it.title} · ${if (it.status == "approved") "✓ Yes" else "Not now"} · ${nameOf(it.member)}", color = Muted, style = MaterialTheme.typography.bodyMedium) }
        }
        Gap(8)
    }
    if (adding) AddRewardDialog { adding = false }
}

@Composable
private fun AddRewardDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var title by remember { mutableStateOf("") }
    var cost by remember { mutableStateOf("20") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text("Add a reward") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Something points can buy. Children ask, you say yes or not now.", color = Muted, fontSize = 13.sp)
                OutlinedTextField(title, { title = it.take(60) }, Modifier.fillMaxWidth(), placeholder = { Text("For example: Pick the film") }, singleLine = true)
                OutlinedTextField(
                    cost, { cost = it.filter { c -> c.isDigit() }.take(4) }, Modifier.fillMaxWidth(), label = { Text("Points it costs") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            TextButton({
                val n = cost.toIntOrNull() ?: 0
                if (title.isBlank()) toast(ctx, "Write what the reward is")
                else if (n !in 1..1000) toast(ctx, "Pick between 1 and 1000 points")
                else ctx.hb.scope.launch {
                    runCatching { ctx.hb.api.addReward(title.trim(), n) }
                        .onSuccess { ctx.hb.state.refreshRewards(); onDismiss() }
                        .onFailure { toast(ctx, it.message ?: "Could not add it") }
                }
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

private val BOARD_EMOJI = listOf("📌", "📶", "🩺", "💊", "☎️", "🏠", "🔑", "🚗")

/** Notes everyone in the family can read (a parent writes them): the Wi-Fi, the doctor, allergies, who to call. */
@Composable
fun BoardScreen(nav: Nav) {
    val ctx = LocalContext.current
    val app = ctx.hb
    val notes by app.state.notes.collectAsState()
    val parent = app.store.isParent
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<BoardItem?>(null) }
    LaunchedEffect(Unit) { app.state.refreshNotes() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { TextButton({ nav.back() }) { Text("Back") } }
        Heading("Family board", "What everyone should be able to find: the Wi-Fi, the doctor, allergies, who to call.")
        notes.forEach { n ->
            SoftCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(n.emoji, fontSize = 26.sp)
                    Text(n.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    if (parent) TextButton({ editing = n }) { Text("Edit") }
                }
                if (n.body.isNotBlank()) Text(n.body, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (notes.isEmpty()) CenterNote(if (parent) "Nothing here yet. Add the Wi-Fi, the doctor, who to call." else "Nothing here yet.")
        if (parent) {
            SoftButton("＋ Add a note") { adding = true }
            Text("Everyone in the family can read these. Keep bank details and PINs out.", color = Muted, fontSize = 13.sp)
        }
        Gap(8)
    }
    if (adding) NoteDialog(null) { adding = false }
    editing?.let { n -> NoteDialog(n) { editing = null } }
}

@Composable
private fun NoteDialog(note: BoardItem?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var title by remember { mutableStateOf(note?.title ?: "") }
    var emoji by remember { mutableStateOf(note?.emoji ?: "📌") }
    var body by remember { mutableStateOf(note?.body ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text(if (note == null) "Add a note" else "Edit the note") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Everyone in the family can read it.", color = Muted, fontSize = 13.sp)
                OutlinedTextField(title, { title = it.take(40) }, Modifier.fillMaxWidth(), placeholder = { Text("For example: Wi-Fi") }, singleLine = true)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BOARD_EMOJI.forEach { e -> Text(e, Modifier.clip(RoundedCornerShape(12.dp)).background(if (e == emoji) AccentSoft else Surface2).clickable { emoji = e }.padding(8.dp), fontSize = 22.sp) }
                }
                OutlinedTextField(body, { body = it.take(600) }, Modifier.fillMaxWidth().heightIn(min = 120.dp), placeholder = { Text("Name: ...\nPassword: ...") }, maxLines = 8)
            }
        },
        confirmButton = {
            TextButton({
                if (title.isBlank()) toast(ctx, "Give the note a title")
                else ctx.hb.scope.launch {
                    runCatching { ctx.hb.api.saveNote(note?.id, title.trim(), emoji, body.trim()) }
                        .onSuccess { ctx.hb.state.refreshNotes(); toast(ctx, "Saved"); onDismiss() }
                        .onFailure { toast(ctx, it.message ?: "Could not save") }
                }
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (note != null) TextButton({
                    ctx.hb.scope.launch { runCatching { ctx.hb.api.dropNote(note.id) }.onSuccess { ctx.hb.state.refreshNotes(); onDismiss() }.onFailure { toast(ctx, it.message ?: "Could not delete") } }
                }) { Text("Delete") }
                TextButton(onDismiss) { Text("Cancel") }
            }
        },
    )
}
