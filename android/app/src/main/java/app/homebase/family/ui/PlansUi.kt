package app.homebase.family.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.homebase.family.Member
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

/** The same page, opened from a notification with a Back button on top. */
@Composable
fun PlansScreen(nav: Nav) = WithBack(nav) { PlanTab(nav) }

@Composable
fun PlanTab(nav: Nav) {
    val ctx = LocalContext.current
    val app = ctx.hb
    val plans by app.state.plans.collectAsState()
    val polls by app.state.polls.collectAsState()
    val fam by app.state.family.collectAsState()
    val me = app.store.myId
    var addPlan by remember { mutableStateOf(false) }
    var askPoll by remember { mutableStateOf(false) }
    var addPlanDay by remember { mutableStateOf<Long?>(null) }
    var addMeal by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { app.state.refreshPlans(); app.state.refreshPolls(); app.state.refreshMeals() }
    val nameOf: (Int) -> String = { id -> if (id == me) "You" else fam?.members?.firstOrNull { it.id == id }?.name ?: "Someone" }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Heading("Plan", "The calendar, what we are eating, and family votes.")
        CalendarCard(nav, nameOf, fam?.members.orEmpty(), plans) { ms -> addPlanDay = ms; addPlan = true }
        SectionLabel("📅 Coming up")
        plans.forEach { PlanCard(it, nameOf) }
        if (plans.isEmpty()) CenterNote("Nothing planned yet.")
        SoftButton("＋ Add a plan") { addPlanDay = null; addPlan = true }
        MealsSection(nav, nameOf) { addMeal = true }
        SectionLabel("🗳️ Family votes")
        polls.forEach { PollCard(it, nameOf) }
        if (polls.isEmpty()) CenterNote("Ask the family something: pizza or tacos?")
        SoftButton("＋ Ask the family") { askPoll = true }
        Gap(8)
    }
    if (addPlan) AddPlanDialog(addPlanDay) { addPlan = false; addPlanDay = null }
    if (addMeal) AddMealDialog(null) { addMeal = false }
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
fun PlanCard(p: Plan, nameOf: (Int) -> String) {
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
        if (p.via.isNotBlank()) Text("From ${slotName(p.via)}", color = Muted, fontSize = 12.sp)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(if (p.mine == "in") "✓ I'm in" else "I'm in", p.mine == "in") { answerPlan(ctx, p.id, if (p.mine == "in") "" else "in") }
            Pill(if (p.mine == "out") "✓ Can't" else "Can't", p.mine == "out") { answerPlan(ctx, p.id, if (p.mine == "out") "" else "out") }
            if (p.lat != null && p.lng != null) Pill("📍 Directions", false) { openDirections(ctx, p.lat, p.lng, p.title) }
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
fun AddPlanDialog(startMs: Long?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var title by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var emoji by remember { mutableStateOf("📅") }
    var whenMs by remember { mutableLongStateOf(startMs ?: (midnight(System.currentTimeMillis()) + 86_400_000L + 18 * 3_600_000L)) }   // the day picked on the calendar, or tomorrow, 6 pm
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
fun AskPollDialog(onDismiss: () -> Unit) {
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

/** A month at a glance: dots for plans, a cake for birthdays. Tap a day to see it, answer "I'm in", or add a plan on it. */
@Composable
private fun CalendarCard(nav: Nav, nameOf: (Int) -> String, members: List<Member>, refresh: Any, onAdd: (Long) -> Unit) {
    val ctx = LocalContext.current
    val zone = java.time.ZoneId.systemDefault()
    var month by remember { mutableStateOf(java.time.YearMonth.now()) }
    var sel by remember { mutableIntStateOf(java.time.LocalDate.now().dayOfMonth) }
    var monthPlans by remember { mutableStateOf<List<Plan>>(emptyList()) }
    var monthMeals by remember { mutableStateOf<List<app.homebase.family.Meal>>(emptyList()) }
    LaunchedEffect(month, refresh) {
        val from = month.atDay(1).atStartOfDay(zone).toEpochSecond()
        val to = month.plusMonths(1).atDay(1).atStartOfDay(zone).toEpochSecond()
        runCatching { ctx.hb.api.plansBetween(from, to) }.onSuccess { monthPlans = it }
        runCatching { ctx.hb.api.meals(month.atDay(1).toString(), month.atEndOfMonth().toString()) }.onSuccess { monthMeals = it; ctx.hb.state.noteMeals(it) }
    }
    fun answerHere(id: Int, answer: String) {
        ctx.hb.scope.launch {
            runCatching { ctx.hb.api.rsvp(id, answer) }
                .onSuccess { updated ->
                    monthPlans = monthPlans.map { if (it.id == id) updated else it }
                    ctx.hb.state.plans.update { list -> list.map { if (it.id == id) updated else it } }
                }
                .onFailure { toast(ctx, it.message ?: "Could not save that") }
        }
    }
    val days = month.lengthOfMonth()
    val day = minOf(sel, days)
    val lead = month.atDay(1).dayOfWeek.value - 1                                         // weeks start on Monday
    val today = java.time.LocalDate.now()
    val byDay = monthPlans.groupBy { java.time.Instant.ofEpochSecond(it.start.toLong()).atZone(zone).dayOfMonth }
    val birthdays = members.mapNotNull { m ->
        val parts = m.birthday?.split("-")
        val bm = parts?.getOrNull(0)?.toIntOrNull()
        val bd = parts?.getOrNull(1)?.toIntOrNull()
        if (bm == month.monthValue && bd != null) m to minOf(bd, days) else null
    }
    val monthName = month.month.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault())
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton({ month = month.minusMonths(1); sel = 1 }) { Text("‹") }
            Text("$monthName ${month.year}", Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
            TextButton({ month = month.plusMonths(1); sel = 1 }) { Text("›") }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, color = Muted, fontSize = 11.sp) }
        }
        for (w in 0 until (lead + days + 6) / 7) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (c in 0 until 7) {
                    val d = w * 7 + c - lead + 1
                    if (d < 1 || d > days) {
                        Box(Modifier.weight(1f).height(46.dp))
                    } else {
                        val n = byDay[d]?.size ?: 0
                        val cake = birthdays.any { it.second == d }
                        val chosen = d == day
                        val ring = if (month.atDay(d) == today && !chosen) Modifier.border(1.dp, Accent, RoundedCornerShape(12.dp)) else Modifier
                        Column(
                            Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(12.dp)).background(if (chosen) Accent else Surface2).then(ring).clickable { sel = d },
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                        ) {
                            Text("$d", color = if (chosen) OnAccent else Ink, fontSize = 14.sp)
                            Text((if (cake) "\uD83C\uDF82" else "") + (if (monthMeals.any { it.day == month.atDay(d).toString() }) "\uD83C\uDF7D" else "") + "\u25CF".repeat(minOf(n, 3)), color = if (chosen) OnAccent else Accent, fontSize = 8.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
        Text("$day $monthName", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
        monthMeals.filter { it.day == month.atDay(day).toString() }.sortedBy { MEAL_SLOTS.indexOf(it.slot) }.forEach { ml ->
            Text("${slotIcon(ml.slot)} ${ml.title}", Modifier.padding(top = 8.dp).clickable { nav.go(Screen.Meal(ml.id)) }, style = MaterialTheme.typography.titleMedium)
        }
        byDay[day].orEmpty().forEach { p ->
            Column(Modifier.padding(top = 8.dp)) {
                Text("${p.emoji} ${p.title}", style = MaterialTheme.typography.titleMedium)
                Text(java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(Date((p.start * 1000).toLong())) + (if (p.note.isNotBlank()) " · " + p.note else ""), color = Muted, fontSize = 13.sp)
                Text(if (p.going.isEmpty()) "Nobody has said yes yet" else "\uD83D\uDC4D " + p.going.joinToString(", ") { nameOf(it) } + (if (p.out.isEmpty()) "" else " · can't: " + p.out.joinToString(", ") { nameOf(it) }), color = Muted, fontSize = 13.sp)
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("in" to "I'm in", "out" to "Can't").forEach { (answer, label) ->
                        val on = p.mine == answer
                        Pill(if (on) "\u2713 $label" else label, on) { answerHere(p.id, if (on) "" else answer) }
                    }
                    if (p.lat != null && p.lng != null) Pill("📍 Directions", false) { openDirections(ctx, p.lat, p.lng, p.title) }
                }
            }
        }
        birthdays.filter { it.second == day }.forEach { Text("\uD83C\uDF82 ${it.first.name}'s birthday", Modifier.padding(top = 8.dp)) }
        if (byDay[day].isNullOrEmpty() && birthdays.none { it.second == day } && monthMeals.none { it.day == month.atDay(day).toString() }) Text("Nothing on this day.", Modifier.padding(top = 4.dp), color = Muted)
        SoftButton("\uFF0B Plan on this day", Modifier.padding(top = 8.dp)) { onAdd(month.atDay(day).atTime(18, 0).atZone(zone).toInstant().toEpochMilli()) }
    }
}
