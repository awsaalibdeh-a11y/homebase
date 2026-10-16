package app.homebase.family.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import app.homebase.family.Chore
import app.homebase.family.ChoresResp
import app.homebase.family.hb
import kotlinx.coroutines.launch

/** Jobs and points: a parent sets a job, a child ticks it off, a parent approves it for points. Everyone in the family can see all of it. */
@Composable
fun ChoresScreen(nav: Nav) {
    val ctx = LocalContext.current
    val scope = ctx.hb.scope
    val fam by ctx.hb.state.family.collectAsState()
    val parent = ctx.hb.store.isParent
    val myId = ctx.hb.store.myId
    var data by remember { mutableStateOf(ChoresResp()) }
    var reload by remember { mutableIntStateOf(0) }
    var title by remember { mutableStateOf("") }
    var points by remember { mutableStateOf("5") }
    var forId by remember { mutableIntStateOf(0) }
    var repeat by remember { mutableStateOf("") }
    LaunchedEffect(reload) { runCatching { ctx.hb.api.chores() }.onSuccess { data = it } }
    val kids = fam?.members.orEmpty().filter { !it.isParent }
    fun act(block: suspend () -> Unit) = scope.launch { runCatching { block() }.onSuccess { reload++ }.onFailure { toast(ctx, it.message ?: "Could not do that") } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { TextButton({ nav.back() }) { Text("Back") } }
        Heading("Jobs and points", if (parent) "Add a job. Your child ticks it off and you approve it for points." else "Do a job, tick it off, and a parent gives you the points.")
        if (kids.isNotEmpty()) SoftCard {
            Text("Points", style = MaterialTheme.typography.titleMedium)
            kids.forEach { k ->
                val streak = data.streaks[k.id.toString()] ?: 0
                Text("${k.emoji} ${k.name}: ${data.week[k.id.toString()] ?: 0} this week · ${data.points[k.id.toString()] ?: 0} total" + (if (streak > 0) " · 🔥 $streak" else ""), color = Muted)
            }
        }
        data.chores.forEach { c -> ChoreRow(c, parent, myId, kids.firstOrNull { it.id == c.doneBy }?.name, kids.firstOrNull { it.id == c.to }?.name,
            onDone = { act { ctx.hb.api.choreDone(c.id) } }, onApprove = { ok -> act { ctx.hb.api.choreApprove(c.id, ok) } }, onDelete = { act { ctx.hb.api.choreDelete(c.id) } }) }
        if (data.chores.isEmpty()) CenterNote(if (parent) "No jobs yet. Add one below." else "No jobs right now.")
        if (parent) SoftCard {
            Text("Add a job", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(title, { title = it.take(80) }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("e.g. Take out the bins") })
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(points, { points = it.filter(Char::isDigit).take(3) }, Modifier.width(110.dp), singleLine = true, suffix = { Text("pts") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                TextButton({ val i = kids.indexOfFirst { it.id == forId }; forId = if (kids.isEmpty()) 0 else if (i + 1 >= kids.size) 0 else kids[i + 1].id }) { Text("For: " + (kids.firstOrNull { it.id == forId }?.name ?: "any child")) }
            }
            TextButton({ repeat = when (repeat) { "" -> "daily"; "daily" -> "weekly"; else -> "" } }) {
                Text("Repeat: " + when (repeat) { "daily" -> "every day"; "weekly" -> "every week"; else -> "never" })
            }
            BigButton("Add the job", enabled = title.isNotBlank()) { act { ctx.hb.api.addChore(title.trim(), points.toIntOrNull() ?: 0, forId, repeat); title = "" } }
        }
        Gap(8)
    }
}

@Composable
private fun ChoreRow(c: Chore, parent: Boolean, myId: Int, doneName: String?, toName: String?, onDone: () -> Unit, onApprove: (Boolean) -> Unit, onDelete: () -> Unit) {
    val forMe = c.to == 0 || c.to == myId
    val waiting = c.status == "open" && c.opensAt > System.currentTimeMillis() / 1000.0          // a repeating job that has not come back yet (only a parent sees it)
    val again = when (c.repeats) { "daily" -> " · 🔁 every day"; "weekly" -> " · 🔁 every week"; else -> "" }
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(c.emoji, style = MaterialTheme.typography.headlineSmall)
            Column(Modifier.weight(1f)) {
                Text(c.title, style = MaterialTheme.typography.titleMedium, textDecoration = if (c.status == "approved") TextDecoration.LineThrough else null)
                Text(when (c.status) {
                    "done" -> "${doneName ?: "Someone"} finished it" + if (parent) "" else ". Waiting for a parent"
                    "approved" -> "✓ ${doneName ?: ""} · +${c.points} points"
                    else -> (if (waiting) "Back ${planWhen(c.opensAt)}" else if (toName != null) "For $toName" else "Any child") + " · ${c.points} points" + again
                }, color = Muted)
            }
        }
        Row(Modifier.fillMaxWidth()) {
            if (c.status == "open" && !waiting && !parent && forMe) TextButton(onDone) { Text("Done") }
            if (c.status == "done" && parent) { TextButton({ onApprove(false) }) { Text("Redo") }; TextButton({ onApprove(true) }) { Text("Approve") } }
            if (parent && c.status != "approved") TextButton(onDelete) { Text("Delete") }
        }
    }
}
