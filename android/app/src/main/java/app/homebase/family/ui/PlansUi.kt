package app.homebase.family.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.homebase.family.Plan
import app.homebase.family.Poll
import app.homebase.family.hb
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val PLAN_EMOJI = listOf("📅", "🎂", "⚽", "🍽️", "🎬", "🏥", "✈️", "📚", "🛒", "🎉")

private fun midnight(ms: Long): Long = Calendar.getInstance().apply {
    timeInMillis = ms; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** "Today · 5:30 PM", "Tomorrow · 9:00 AM", "Saturday · 3:00 PM" or "Sat 3 Nov · 3:00 PM". */
fun planWhen(start: Double): String {
    val t = (start * 1000).toLong()
    if (t < System.currentTimeMillis()) return "Happening now"
    val days = Math.round((midnight(t) - midnight(System.currentTimeMillis())) / 86_400_000.0).toInt()
    val clock = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(Date(t))
    return when {
        days == 0 -> "Today · $clock"
        days == 1 -> "Tomorrow · $clock"
        days in 2..6 -> java.text.SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(t)) + " · " + clock
        else -> java.text.SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(t)) + " · " + clock
    }
}

@Composable
fun PlansScreen(nav: Nav) {
    val ctx = LocalContext.current
    val app = ctx.hb
    val plans by app.state.plans.collectAsState()
    val polls by app.state.polls.collectAsState()
    val fam by app.state.family.collectAsState()
    val me = app.store.myId
    var addPlan by remember { mutableStateOf(false) }
    var askPoll by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { app.state.refreshPlans(); app.state.refreshPolls() }
    val nameOf: (Int) -> String = { id -> if (id == me) "You" else fam?.members?.firstOrNull { it.id == id }?.name ?: "Someone" }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { TextButton({ nav.back() }) { Text("Back") } }
        Heading("Plans and votes", "Put things on the family calendar and ask everyone a question.")
        SectionLabel("📅 Coming up")
        plans.forEach { PlanCard(it, nameOf) }
        if (plans.isEmpty()) CenterNote("Nothing planned yet.")
        SoftButton("＋ Add a plan") { addPlan = true }
        SectionLabel("🗳️ Family votes")
        polls.forEach { PollCard(it, nameOf) }
        if (polls.isEmpty()) CenterNote("Ask the family something: pizza or tacos?")
        SoftButton("＋ Ask the family") { askPoll = true }
        Gap(8)
    }
    if (addPlan) AddPlanDialog { addPlan = false }
    if (askPoll) AskPollDialog { askPoll = false }
}

@Composable
fun Pill(text: String, on: Boolean, onClick: () -> Unit) {
    Text(
        text,
        Modifier.clip(RoundedCornerShape(50)).background(if (on) Accent else Surface2).clickable { onClick() }.padding(horizontal = 16.dp, vertical = 8.dp),
        color = if (on) OnAccent else Ink, fontSize = 14.sp,
    )
}

private fun answerPlan(ctx: Context, id: Int, answer: String) {
    ctx.hb.scope.launch {
        runCatching { ctx.hb.api.rsvp(id, answer) }
            .onSuccess { updated -> ctx.hb.state.plans.update { list -> list.map { if (it.id == id) updated else it } } }
            .onFailure { toast(ctx, it.message ?: "Could not save that") }
    }
}

private fun votePoll(ctx: Context, id: Int, option: Int) {
    ctx.hb.scope.launch {
        runCatching { ctx.hb.api.vote(id, option) }
            .onSuccess { updated -> ctx.hb.state.polls.update { list -> list.map { if (it.id == id) updated else it } } }
            .onFailure { toast(ctx, it.message ?: "Could not vote") }
    }
}

@Composable
private fun PlanCard(p: Plan, nameOf: (Int) -> String) {
    val ctx = LocalContext.current
    val app = ctx.hb
    val inText = if (p.going.isEmpty()) "Nobody has said yes yet" else "👍 " + p.going.joinToString(", ") { nameOf(it) }
    val outText = if (p.out.isEmpty()) "" else " · can't: " + p.out.joinToString(", ") { nameOf(it) }
    SoftCard {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(p.emoji, fontSize = 26.sp)
            Column(Modifier.weight(1f)) {
                Text(p.title, style = MaterialTheme.typography.titleMedium)
                Text(planWhen(p.start) + (if (p.note.isNotBlank()) " · " + p.note else ""), color = Muted, style = MaterialTheme.typography.bodyMedium)
            }
            if (p.maker == app.store.myId || app.store.isParent) TextButton({ app.scope.launch { runCatching { app.api.dropPlan(p.id) }; app.state.refreshPlans() } }) { Text("✕") }
        }
        Text(inText + outText, Modifier.padding(top = 6.dp), color = Muted, fontSize = 13.sp)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(if (p.mine == "in") "✓ I'm in" else "I'm in", p.mine == "in") { answerPlan(ctx, p.id, if (p.mine == "in") "" else "in") }
            Pill(if (p.mine == "out") "✓ Can't" else "Can't", p.mine == "out") { answerPlan(ctx, p.id, if (p.mine == "out") "" else "out") }
        }
    }
}

@Composable
private fun PollCard(p: Poll, nameOf: (Int) -> String) {
    val ctx = LocalContext.current
    val app = ctx.hb
    val total = p.votes.sumOf { it.size }
    val canMod = p.maker == app.store.myId || app.store.isParent
    SoftCard {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(p.question, style = MaterialTheme.typography.titleMedium)
                Text("${nameOf(p.maker)} asked · $total ${if (total == 1) "vote" else "votes"}${if (p.closed) " · closed" else ""}", color = Muted, fontSize = 13.sp)
            }
            if (canMod) TextButton({ app.scope.launch { runCatching { app.api.dropPoll(p.id) }; app.state.refreshPolls() } }) { Text("✕") }
        }
        p.options.forEachIndexed { i, label ->
            val ids = p.votes.getOrNull(i).orEmpty()
            val share = if (total > 0) ids.size.toFloat() / total else 0f
            val border = if (p.mine == i) Modifier.border(1.5.dp, Accent, RoundedCornerShape(12.dp)) else Modifier
            Row(
                Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface2)
                    .drawBehind { drawRect(Accent.copy(alpha = 0.28f), size = Size(size.width * share, size.height)) }
                    .then(border)
                    .clickable(enabled = !p.closed) { votePoll(ctx, p.id, i) }
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(label)
                    if (ids.isNotEmpty()) Text(ids.joinToString(", ") { nameOf(it) }, color = Muted, fontSize = 12.sp)
                }
                if (ids.isNotEmpty()) Text("${ids.size}", color = Muted, fontSize = 13.sp)
            }
        }
        if (canMod && !p.closed) TextButton({ app.scope.launch { runCatching { app.api.closePoll(p.id) }; app.state.refreshPolls() } }) { Text("Close the vote") }
    }
}

@Composable
private fun AddPlanDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var title by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var emoji by remember { mutableStateOf("📅") }
    var whenMs by remember { mutableLongStateOf(midnight(System.currentTimeMillis()) + 86_400_000L + 18 * 3_600_000L) }   // tomorrow, 6 pm
    val cal = Calendar.getInstance().apply { timeInMillis = whenMs }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text("Add a plan") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Everyone in the family sees it and can say if they are coming.", color = Muted, fontSize = 13.sp)
                OutlinedTextField(title, { title = it.take(60) }, Modifier.fillMaxWidth(), placeholder = { Text("What is it?") }, singleLine = true)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PLAN_EMOJI.forEach { e -> Text(e, Modifier.clip(RoundedCornerShape(12.dp)).background(if (e == emoji) AccentSoft else Surface2).clickable { emoji = e }.padding(8.dp), fontSize = 22.sp) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SoftButton(java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(Date(whenMs)), Modifier.weight(1f)) {
                        DatePickerDialog(ctx, { _, y, m, d -> whenMs = Calendar.getInstance().apply { timeInMillis = whenMs; set(y, m, d) }.timeInMillis },
                            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
                    }
                    SoftButton(java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(Date(whenMs)), Modifier.weight(1f)) {
                        TimePickerDialog(ctx, { _, h, mi -> whenMs = Calendar.getInstance().apply { timeInMillis = whenMs; set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, mi); set(Calendar.SECOND, 0) }.timeInMillis },
                            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), android.text.format.DateFormat.is24HourFormat(ctx)).show()
                    }
                }
                OutlinedTextField(note, { note = it.take(120) }, Modifier.fillMaxWidth(), placeholder = { Text("A note (optional)") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton({
                if (title.isBlank()) toast(ctx, "Give the plan a name first")
                else ctx.hb.scope.launch {
                    runCatching { ctx.hb.api.addPlan(title.trim(), emoji, whenMs / 1000.0, note.trim()) }
                        .onSuccess { ctx.hb.state.refreshPlans(); toast(ctx, "Plan added"); onDismiss() }
                        .onFailure { toast(ctx, it.message ?: "Could not add it") }
                }
            }) { Text("Add it") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AskPollDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var question by remember { mutableStateOf("") }
    val answers = remember { mutableStateListOf("", "", "", "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text("Ask the family") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Everyone gets one vote. Close it when you have an answer.", color = Muted, fontSize = 13.sp)
                OutlinedTextField(question, { question = it.take(100) }, Modifier.fillMaxWidth(), placeholder = { Text("For example: What's for dinner?") }, singleLine = true)
                answers.forEachIndexed { i, v ->
                    OutlinedTextField(v, { answers[i] = it.take(40) }, Modifier.fillMaxWidth(), placeholder = { Text("Answer ${i + 1}${if (i > 1) " (optional)" else ""}") }, singleLine = true)
                }
            }
        },
        confirmButton = {
            TextButton({
                val picked = answers.map { it.trim() }.filter { it.isNotEmpty() }
                if (question.isBlank()) toast(ctx, "Write the question first")
                else if (picked.size < 2) toast(ctx, "Give at least two answers")
                else ctx.hb.scope.launch {
                    runCatching { ctx.hb.api.askPoll(question.trim(), picked) }
                        .onSuccess { ctx.hb.state.refreshPolls(); toast(ctx, "Asked"); onDismiss() }
                        .onFailure { toast(ctx, it.message ?: "Could not ask") }
                }
            }) { Text("Ask") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
