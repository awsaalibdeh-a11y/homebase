package app.homebase.family.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.homebase.family.AlbumPhoto
import app.homebase.family.Badge
import app.homebase.family.Countdown
import app.homebase.family.Goal
import app.homebase.family.Homework
import app.homebase.family.KudosResp
import app.homebase.family.Member
import app.homebase.family.MoodsResp
import app.homebase.family.TimelineResp
import app.homebase.family.Trusted
import app.homebase.family.WalletInfo
import app.homebase.family.WeatherResp
import app.homebase.family.hb
import coil.compose.AsyncImage
import java.text.NumberFormat
import java.time.LocalDate
import kotlinx.coroutines.launch

fun money(cents: Int): String = NumberFormat.getCurrencyInstance().format(cents / 100.0)

private fun go(ctx: Context, done: String, block: suspend () -> Unit, after: suspend () -> Unit = {}) {
    ctx.hb.scope.launch {
        runCatching { block() }.onSuccess { if (done.isNotBlank()) toast(ctx, done); after() }.onFailure { toast(ctx, it.message ?: "Could not do that") }
    }
}

@Composable
private fun BackRow(nav: Nav) { Row(verticalAlignment = Alignment.CenterVertically) { TextButton({ nav.back() }) { Text("Back") } } }

@Composable
private fun Page(nav: Nav, title: String, sub: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BackRow(nav)
        Heading(title, sub)
        content()
        Gap(8)
    }
}

/** A small dialog that asks for an amount of money and a note. */
@Composable
fun AmountDialog(title: String, okLabel: String, askSign: Boolean = false, onDismiss: () -> Unit, onOk: (Double, String) -> Unit) {
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var spend by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Surface, title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (askSign) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("I want to spend", spend) { spend = true }
                    Pill("I'm asking for money", !spend) { spend = false }
                }
                OutlinedTextField(amount, { amount = it.filter { c -> c.isDigit() || c == '.' || (c == '-' && !askSign) }.take(8) }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Amount, like 5 or 2.50") })
                OutlinedTextField(note, { note = it.take(80) }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("What for? (optional)") })
            }
        },
        confirmButton = {
            TextButton({
                val v = amount.toDoubleOrNull()
                if (v == null || v == 0.0) return@TextButton
                onOk(if (askSign && spend) -kotlin.math.abs(v) else v, note.trim()); onDismiss()
            }) { Text(okLabel) }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

// ================================================================ pocket money and goals
@Composable
fun WalletScreen(nav: Nav) {
    val ctx = LocalContext.current
    val parent = ctx.hb.store.isParent
    var wallets by remember { mutableStateOf<List<WalletInfo>?>(null) }
    var round by remember { mutableIntStateOf(0) }
    var addFor by remember { mutableStateOf<WalletInfo?>(null) }
    var asking by remember { mutableStateOf(false) }
    var newGoal by remember { mutableStateOf(false) }
    var saveTo by remember { mutableStateOf<Goal?>(null) }
    LaunchedEffect(round) { wallets = runCatching { ctx.hb.api.wallet() }.getOrNull() ?: emptyList() }
    val refresh: suspend () -> Unit = { round++ }
    Page(nav, "Pocket money", if (parent) "Add money, answer requests, and see what everyone is saving for." else "Your money, and what you are saving for.") {
        val list = wallets
        if (list == null) CenterNote("Loading…") else if (list.isEmpty()) CenterNote("No children yet.")
        list.orEmpty().forEach { w ->
            SoftCard {
                Text(if (parent) w.name else "My wallet", style = MaterialTheme.typography.titleMedium)
                Text(money(w.balance), style = MaterialTheme.typography.headlineLarge)
                if (parent) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Pill("＋ Add or take off", true) { addFor = w } }
                w.entries.filter { it.status == "pending" }.forEach { e ->
                    Text("${if (e.cents < 0) "Wants to spend" else "Asks for"} ${money(kotlin.math.abs(e.cents))}" + (if (e.note.isNotBlank()) " · ${e.note}" else ""), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall)
                    if (parent) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("Yes", true) { go(ctx, "Done", { ctx.hb.api.walletAnswer(e.id, true) }, refresh) }
                        Pill("Not now", false) { go(ctx, "Answered", { ctx.hb.api.walletAnswer(e.id, false) }, refresh) }
                    } else Text("Waiting for a parent", color = Muted, fontSize = 12.sp)
                }
                if (w.goals.isNotEmpty()) Text("Saving for", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.titleSmall)
                w.goals.forEach { g ->
                    Text("${g.emoji} ${g.title} · ${money(g.saved)} of ${money(g.target)}" + if (g.saved >= g.target) " ✓" else "", Modifier.padding(top = 6.dp))
                    LinearProgressIndicator(progress = { (g.saved.toFloat() / g.target.coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(8.dp).clip(RoundedCornerShape(4.dp)))
                    if (!parent) Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("Put money in", false) { saveTo = g }
                        Pill("Remove", false) { go(ctx, "Removed, and the money is back in your wallet", { ctx.hb.api.dropGoal(g.id) }, refresh) }
                    }
                }
                w.entries.filter { it.status == "done" }.take(6).forEach { e ->
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Text(e.note.ifBlank { "Money" }, Modifier.weight(1f), color = Muted, fontSize = 13.sp)
                        Text((if (e.cents > 0) "+" else "") + money(e.cents), fontSize = 13.sp)
                    }
                }
            }
        }
        if (!parent) TwoUp({ SoftButton("Ask a parent") { asking = true } }, { SoftButton("＋ Savings goal") { newGoal = true } })
    }
    addFor?.let { w -> AmountDialog("Money for ${w.name}", "Save", onDismiss = { addFor = null }) { v, note -> go(ctx, "Saved", { ctx.hb.api.walletAdd(w.member, v, note) }, refresh) } }
    if (asking) AmountDialog("Ask a parent", "Ask", askSign = true, onDismiss = { asking = false }) { v, note -> go(ctx, "Asked. A parent will answer.", { ctx.hb.api.walletRequest(v, note) }, refresh) }
    saveTo?.let { g -> AmountDialog("Save for ${g.title}", "Save", onDismiss = { saveTo = null }) { v, _ -> go(ctx, "Saved", { ctx.hb.api.saveToGoal(g.id, v) }, refresh) } }
    if (newGoal) {
        var title by remember { mutableStateOf("") }
        var target by remember { mutableStateOf("") }
        var emoji by remember { mutableStateOf("🎯") }
        AlertDialog(onDismissRequest = { newGoal = false }, containerColor = Surface, title = { Text("New savings goal") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(title, { title = it.take(60) }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("What for? A game, shoes…") })
                    OutlinedTextField(target, { target = it.filter { c -> c.isDigit() || c == '.' }.take(8) }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("How much does it cost?") })
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("🎯", "🎮", "👟", "📱", "🚲", "🎧", "📚", "🎁").forEach { e -> Pill(e, emoji == e) { emoji = e } }
                    }
                }
            },
            confirmButton = { TextButton({ val t = target.toDoubleOrNull(); if (title.isNotBlank() && t != null && t > 0) { go(ctx, "Goal made", { ctx.hb.api.addGoal(title.trim(), emoji, t) }, refresh); newGoal = false } }) { Text("Make it") } },
            dismissButton = { TextButton({ newGoal = false }) { Text("Cancel") } })
    }
}

// ================================================================ mood check-ins
private val FACES = listOf(1 to "😢", 2 to "😕", 3 to "😐", 4 to "🙂", 5 to "😄")

@Composable
fun MoodScreen(nav: Nav, member: Int) {
    val ctx = LocalContext.current
    val mine = member == 0 || member == ctx.hb.store.myId
    var data by remember { mutableStateOf<MoodsResp?>(null) }
    var round by remember { mutableIntStateOf(0) }
    var pick by remember { mutableIntStateOf(0) }
    var note by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf(false) }
    LaunchedEffect(round) { data = runCatching { ctx.hb.api.moods(if (mine) null else member) }.getOrNull() }
    Page(nav, if (mine) "How are you feeling?" else "${data?.name ?: ""}'s moods", if (mine) "One tap a day. Keep it private if you like: parents then only see that you checked in." else "Days they kept private show only that they checked in.") {
        if (mine) SoftCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                FACES.forEach { (v, f) -> Text(f, Modifier.clip(RoundedCornerShape(16.dp)).background(if (pick == v) AccentSoft else Surface).clickable { pick = v }.padding(8.dp), fontSize = 34.sp) }
            }
            OutlinedTextField(note, { note = it.take(200) }, Modifier.fillMaxWidth().padding(top = 8.dp), placeholder = { Text("Anything you want to say? (optional)") })
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(secret, { secret = it }); Text("Keep it private") }
            BigButton("Save", enabled = pick > 0) { go(ctx, "Thanks for sharing 💛", { ctx.hb.api.postMood(pick, note.trim(), secret) }) { round++; pick = 0; note = "" } }
        }
        SectionLabel("The last two weeks")
        data?.days.orEmpty().forEach { d ->
            SoftCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (d.mood == 0) "🔒" else FACES.firstOrNull { it.first == d.mood }?.second ?: "🙂", fontSize = 30.sp)
                    Column(Modifier.weight(1f)) {
                        Text(dayLabel(d.day), style = MaterialTheme.typography.titleSmall)
                        Text(if (d.mood == 0) "Checked in, kept private" else d.note.ifBlank { if (d.isPrivate) "Private" else "" }, color = Muted, fontSize = 13.sp)
                    }
                }
            }
        }
        if (data?.days.isNullOrEmpty()) CenterNote("Nothing yet.")
    }
}

// ================================================================ safe walk, expected arrival, speed, bedtime
@Composable
fun WalkDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val fam by ctx.hb.state.family.collectAsState()
    val places = fam?.places.orEmpty().filter { !it.pending }
    var place by remember { mutableIntStateOf(places.firstOrNull { it.name == "Home" }?.id ?: places.firstOrNull()?.id ?: 0) }
    var minutes by remember { mutableIntStateOf(20) }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Surface, title = { Text("🚶 Safe Walk") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Your family sees you live until you get there. They are told if you stop moving for 10 minutes or take much longer.", color = Muted, fontSize = 13.sp)
                if (places.isEmpty()) Text("Add a zone first (like Home), so Mellow knows where you are going.")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { places.forEach { p -> Pill("${p.emoji} ${p.name}", place == p.id) { place = p.id } } }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(10, 20, 30, 45, 60).forEach { m -> Pill("$m min", minutes == m) { minutes = m } } }
            }
        },
        confirmButton = { TextButton({ if (place != 0) { go(ctx, "Safe Walk started. Your family can follow it.", { ctx.hb.api.startWalk(place, minutes) }) { ctx.hb.state.refreshFamily() }; onDismiss() } }, enabled = place != 0) { Text("Start") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

@Composable
fun ExpectDialog(m: Member, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val fam by ctx.hb.state.family.collectAsState()
    val places = fam?.places.orEmpty().filter { !it.pending }
    var place by remember { mutableIntStateOf(places.firstOrNull()?.id ?: 0) }
    var minutes by remember { mutableIntStateOf(30) }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Surface, title = { Text("Tell me when ${m.name} gets there") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("You get a message when they arrive, or if they have not arrived in time. ${m.name} is told you asked.", color = Muted, fontSize = 13.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { places.forEach { p -> Pill("${p.emoji} ${p.name}", place == p.id) { place = p.id } } }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(15, 30, 60, 90, 120).forEach { x -> Pill("within $x min", minutes == x) { minutes = x } } }
            }
        },
        confirmButton = { TextButton({ if (place != 0) { go(ctx, "You will be told", { ctx.hb.api.expect(m.id, place, minutes) }); onDismiss() } }, enabled = place != 0) { Text("Tell me") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

@Composable
fun SpeedDialog(m: Member, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    PickDialog("Speed alert for ${m.name} (they will see it)", listOf("Off" to 0, "30 km/h" to 30, "50 km/h" to 50, "70 km/h" to 70, "90 km/h" to 90, "110 km/h" to 110),
        { v -> go(ctx, if (v == 0) "Speed alert off" else "You will be told above $v km/h", { ctx.hb.api.speedLimit(m.id, v) }) { ctx.hb.state.refreshFamily() } }, onDismiss)
}

fun pickBedtime(ctx: Context, memberId: Int) {
    TimePickerDialog(ctx, { _, h, mi ->
        val from = h * 60 + mi
        TimePickerDialog(ctx, { _, h2, m2 -> go(ctx, "Bedtime reminder saved", { ctx.hb.api.setBedtime(memberId, from, h2 * 60 + m2) }) { ctx.hb.state.refreshFamily() } }, 7, 0, false).apply { setTitle("Wake-up time") }.show()
    }, 21, 0, false).apply { setTitle("Wind-down time") }.show()
}

// ================================================================ weather and countdowns on Home
@Composable
fun WeatherCard() {
    val ctx = LocalContext.current
    var w by remember { mutableStateOf<WeatherResp?>(null) }
    LaunchedEffect(Unit) { w = runCatching { ctx.hb.api.weather() }.getOrNull() }
    val x = w ?: return
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(x.icon, fontSize = 40.sp)
            Column(Modifier.weight(1f)) {
                Text("${x.temp}° · ${x.words}", style = MaterialTheme.typography.titleLarge)
                Text("At ${x.where} · wind ${x.wind} km/h", color = Muted, fontSize = 13.sp)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            x.days.take(3).forEach { d ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(dayLabel(d.day).split(" ").first(), fontSize = 12.sp, color = Muted)
                    Text(d.icon, fontSize = 22.sp)
                    Text("${d.max}° / ${d.min}°", fontSize = 12.sp)
                    if (d.rain > 0) Text("💧 ${d.rain}%", fontSize = 11.sp, color = Muted)
                }
            }
        }
    }
}

@Composable
fun CountdownsCard() {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf<List<Countdown>>(emptyList()) }
    var round by remember { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    LaunchedEffect(round) { list = runCatching { ctx.hb.api.countdowns() }.getOrNull() ?: emptyList() }
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("⏳ Counting down", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton({ adding = true }) { Text("＋ Add") }
        }
        if (list.isEmpty()) Text("A holiday, a birthday, the last day of school…", color = Muted, fontSize = 13.sp)
        list.take(4).forEach { c ->
            val days = kotlin.math.ceil((c.at - System.currentTimeMillis() / 1000.0) / 86400.0).toInt()
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${c.emoji} ${c.title}", Modifier.weight(1f))
                Text(if (days <= 0) "Today!" else if (days == 1) "Tomorrow" else "$days days", style = MaterialTheme.typography.titleMedium)
                if (c.by == ctx.hb.store.myId || ctx.hb.store.isParent) TextButton({ go(ctx, "", { ctx.hb.api.dropCountdown(c.id) }) { round++ } }) { Text("✕") }
            }
        }
    }
    if (adding) {
        var title by remember { mutableStateOf("") }
        var emoji by remember { mutableStateOf("🏖️") }
        var date by remember { mutableStateOf(LocalDate.now().plusDays(7)) }
        AlertDialog(onDismissRequest = { adding = false }, containerColor = Surface, title = { Text("Count down to…") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(title, { title = it.take(60) }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Holiday, sleepover, match…") })
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("🏖️", "🎂", "🎄", "✈️", "⚽", "🎉", "🏫", "⏳").forEach { e -> Pill(e, emoji == e) { emoji = e } } }
                    SoftButton(dayLabel(date.toString())) { DatePickerDialog(ctx, { _, y, m, d -> date = LocalDate.of(y, m + 1, d) }, date.year, date.monthValue - 1, date.dayOfMonth).show() }
                }
            },
            confirmButton = {
                TextButton({
                    if (title.isNotBlank()) {
                        val at = date.atTime(9, 0).atZone(java.time.ZoneId.systemDefault()).toEpochSecond().toDouble()
                        go(ctx, "Added", { ctx.hb.api.addCountdown(title.trim(), emoji, at) }) { round++ }; adding = false
                    }
                }) { Text("Add") }
            },
            dismissButton = { TextButton({ adding = false }) { Text("Cancel") } })
    }
}

/** On Home during a Safe Walk: where to, and a way to finish it. */
@Composable
fun WalkBanner() {
    val ctx = LocalContext.current
    val fam by ctx.hb.state.family.collectAsState()
    val me = fam?.members?.firstOrNull { it.id == ctx.hb.store.myId } ?: return
    val w = me.walk ?: return
    SoftCard(tint = MintSoft) {
        Text("🚶 Safe Walk to ${w.emoji} ${w.place}", style = MaterialTheme.typography.titleMedium)
        Text("Your family can see you live. It ends by itself when you arrive.", color = Muted)
        SoftButton("End the walk", Modifier.padding(top = 8.dp)) { go(ctx, "Walk ended", { ctx.hb.api.stopWalk() }) { ctx.hb.state.refreshFamily() } }
    }
}

// ================================================================ timeline
@Composable
fun TimelineScreen(nav: Nav, member: Int) {
    val ctx = LocalContext.current
    var data by remember { mutableStateOf<TimelineResp?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(member) { runCatching { ctx.hb.api.timeline(member) }.onSuccess { data = it }.onFailure { failed = it.message } }
    Page(nav, "${data?.name ?: ""}'s day", "Where they arrived and left in the last 24 hours (zones only).") {
        failed?.let { CenterNote(it) }
        data?.items.orEmpty().forEach { v ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(clockOf(v.at), Modifier.size(width = 76.dp, height = 24.dp), color = Muted, fontSize = 13.sp)
                Box(Modifier.size(12.dp).clip(RoundedCornerShape(6.dp)).background(if (v.kind == "arrive") Ink else Muted))
                Text("${if (v.kind == "arrive") "Arrived at" else "Left"} ${v.emoji} ${v.place}")
            }
        }
        if (data != null && data?.items.isNullOrEmpty()) CenterNote("No arrivals or departures today.")
    }
}

// ================================================================ share my trip
@Composable
fun TripDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var link by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Surface, title = { Text("🔗 Share my trip") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Make a link that shows where you are, live, to someone outside the family (a friend's parent, a driver). It stops working by itself. Your family is told you made one.", color = Muted, fontSize = 13.sp)
                if (link == null) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1, 2, 4, 8).forEach { h -> Pill("$h h", false) { ctx.hb.scope.launch { runCatching { ctx.hb.api.makeTrip(h) }.onSuccess { link = it }.onFailure { toast(ctx, it.message ?: "Could not make it") } } } }
                } else {
                    Text(link!!, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("Share the link", true) { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "See where I am, live: $link"), "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        Pill("Stop sharing", false) { go(ctx, "The link stopped working", { ctx.hb.api.stopTrip() }); link = null }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Done") } })
}

// ================================================================ kudos and badges
@Composable
fun KudosScreen(nav: Nav) {
    val ctx = LocalContext.current
    val fam by ctx.hb.state.family.collectAsState()
    var data by remember { mutableStateOf<KudosResp?>(null) }
    var badges by remember { mutableStateOf<List<Badge>>(emptyList()) }
    var round by remember { mutableIntStateOf(0) }
    var target by remember { mutableIntStateOf(0) }
    var emoji by remember { mutableStateOf("⭐") }
    var reason by remember { mutableStateOf("") }
    LaunchedEffect(round) { data = runCatching { ctx.hb.api.kudos() }.getOrNull(); badges = runCatching { ctx.hb.api.badges() }.getOrNull()?.badges ?: emptyList() }
    Page(nav, "Kudos and badges", "Say thanks, and see what you have earned.") {
        data?.star?.let { s -> SoftCard(tint = SunSoft) { Text("⭐ Star of the week", style = MaterialTheme.typography.titleMedium); Text(s.name, style = MaterialTheme.typography.headlineMedium); Text("${s.count} kudos this week", color = Muted) } }
        SoftCard {
            Text("Give kudos", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                fam?.members.orEmpty().filter { it.id != ctx.hb.store.myId }.forEach { m -> Pill("${m.emoji} ${m.name}", target == m.id) { target = m.id } }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("⭐", "🏆", "💛", "🙌", "🌈", "🔥").forEach { e -> Pill(e, emoji == e) { emoji = e } } }
            OutlinedTextField(reason, { reason = it.take(120) }, Modifier.fillMaxWidth().padding(top = 6.dp), singleLine = true, placeholder = { Text("What for? Helped with dinner…") })
            BigButton("Send kudos", Modifier.padding(top = 8.dp), enabled = target != 0) { go(ctx, "Sent ⭐", { ctx.hb.api.giveKudos(target, emoji, reason.trim()) }) { reason = ""; round++ } }
        }
        SectionLabel("My badges")
        badges.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { b ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(if (b.earned) SunSoft else Surface2).padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (b.earned) b.emoji else "🔒", fontSize = 30.sp)
                        Text(b.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                        Text(if (b.earned) "Earned" else "${b.have}/${b.goal} · ${b.how}", fontSize = 11.sp, color = Muted, maxLines = 2)
                    }
                }
                repeat(3 - row.size) { Box(Modifier.weight(1f)) }
            }
        }
        SectionLabel("Recent kudos")
        data?.recent.orEmpty().forEach { k -> Text("${k.emoji} ${k.from} → ${k.to}" + (if (k.reason.isNotBlank()) ": ${k.reason}" else ""), color = Ink) }
    }
}

// ================================================================ album
@Composable
fun AlbumScreen(nav: Nav) {
    val ctx = LocalContext.current
    var photos by remember { mutableStateOf<List<AlbumPhoto>?>(null) }
    var round by remember { mutableIntStateOf(0) }
    var big by remember { mutableStateOf<AlbumPhoto?>(null) }
    LaunchedEffect(round) { photos = runCatching { ctx.hb.api.album() }.getOrNull() ?: emptyList() }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) ctx.hb.scope.launch {
            val b = readBitmap(ctx, uri)
            if (b == null) toast(ctx, "That picture could not be read")
            else runCatching { ctx.hb.api.addToAlbum(ctx.hb.api.uploadImage(jpegDataUrl(fit(b, 1600), 82)), "") }.onSuccess { toast(ctx, "Added to the album"); round++ }.onFailure { toast(ctx, it.message ?: "Could not add it") }
        }
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BackRow(nav)
        Heading("Family album", "Photos sent in the family chat land here by themselves.")
        TwoUp({ SoftButton("📷 Take a photo") { nav.go(Screen.Camera("album")) } }, { SoftButton("🖼️ Add from gallery") { gallery.launch("image/*") } })
        val list = photos
        if (list == null) CenterNote("Loading…") else if (list.isEmpty()) CenterNote("No photos yet.")
        else LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(list, key = { it.id }) { p ->
                AsyncImage(pictureUrl(ctx, p.url), p.caption, Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).clickable { big = p }, contentScale = ContentScale.Crop)
            }
        }
    }
    big?.let { p ->
        Dialog({ big = null }) {
            Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Surface).padding(8.dp)) {
                AsyncImage(pictureUrl(ctx, p.url), p.caption, Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                Text((if (p.caption.isNotBlank()) p.caption + " · " else "") + "by ${p.name} · ${timeAgo(p.at)}", Modifier.padding(8.dp), color = Muted, fontSize = 13.sp)
                if (p.by == ctx.hb.store.myId || ctx.hb.store.isParent) TextButton({ go(ctx, "Removed", { ctx.hb.api.dropPhoto(p.id) }) { round++ }; big = null }) { Text("Remove from album", color = Rose) }
            }
        }
    }
}

// ================================================================ homework
@Composable
fun HomeworkScreen(nav: Nav) {
    val ctx = LocalContext.current
    val parent = ctx.hb.store.isParent
    val fam by ctx.hb.state.family.collectAsState()
    var items by remember { mutableStateOf<List<Homework>?>(null) }
    var round by remember { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    LaunchedEffect(round) { items = runCatching { ctx.hb.api.homework() }.getOrNull() ?: emptyList() }
    Page(nav, "Homework", if (parent) "What your children have to do, and when it is due." else "Add it, tick it off. You get a reminder the day before.") {
        val list = items.orEmpty()
        if (items == null) CenterNote("Loading…") else if (list.isEmpty()) CenterNote("No homework. Nice.")
        list.groupBy { it.name }.forEach { (name, hw) ->
            if (parent) SectionLabel(name)
            hw.forEach { h ->
                SoftCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(h.done, { go(ctx, "", { ctx.hb.api.tickHomework(h.id, it) }) { round++ } })
                        Column(Modifier.weight(1f)) {
                            Text(h.title, style = MaterialTheme.typography.titleMedium, color = if (h.done) Muted else Ink)
                            Text(listOf(h.subject, "due " + dayLabel(h.due)).filter { it.isNotBlank() }.joinToString(" · "), color = Muted, fontSize = 13.sp)
                        }
                        TextButton({ go(ctx, "", { ctx.hb.api.dropHomework(h.id) }) { round++ } }) { Text("✕") }
                    }
                }
            }
        }
        SoftButton("＋ Add homework") { adding = true }
    }
    if (adding) {
        val kids = fam?.members.orEmpty().filter { !it.isParent }
        var title by remember { mutableStateOf("") }
        var subject by remember { mutableStateOf("") }
        var due by remember { mutableStateOf(LocalDate.now().plusDays(1)) }
        var who by remember { mutableIntStateOf(kids.firstOrNull()?.id ?: 0) }
        AlertDialog(onDismissRequest = { adding = false }, containerColor = Surface, title = { Text("Add homework") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (parent) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { kids.forEach { k -> Pill(k.name, who == k.id) { who = k.id } } }
                    OutlinedTextField(title, { title = it.take(120) }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("What is it?") })
                    OutlinedTextField(subject, { subject = it.take(30) }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Subject (optional)") })
                    SoftButton("Due " + dayLabel(due.toString())) { DatePickerDialog(ctx, { _, y, m, d -> due = LocalDate.of(y, m + 1, d) }, due.year, due.monthValue - 1, due.dayOfMonth).show() }
                }
            },
            confirmButton = { TextButton({ if (title.isNotBlank()) { go(ctx, "Added", { ctx.hb.api.addHomework(title.trim(), subject.trim(), due.toString(), if (parent) who else null) }) { round++ }; adding = false } }) { Text("Add") } },
            dismissButton = { TextButton({ adding = false }) { Text("Cancel") } })
    }
}

// ================================================================ trusted adults
@Composable
fun TrustedScreen(nav: Nav) {
    val ctx = LocalContext.current
    val parent = ctx.hb.store.isParent
    var people by remember { mutableStateOf<List<Trusted>>(emptyList()) }
    var round by remember { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    LaunchedEffect(round) {
        runCatching { ctx.hb.api.trusted() }.onSuccess { people = it; ctx.hb.store.saveText("trusted", it.joinToString("\n") { t -> "${t.name}|${t.phone}" }) }
            .onFailure { people = ctx.hb.store.text("trusted").lines().filter { it.contains("|") }.mapIndexed { i, l -> Trusted(-i - 1, l.substringBefore("|"), l.substringAfter("|")) } }
    }
    Page(nav, "Trusted adults", "Grown-ups to call if you cannot reach a parent. They also get your SOS by text when there is no internet.") {
        people.forEach { t ->
            SoftCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(t.name, style = MaterialTheme.typography.titleMedium); Text(listOf(t.relation, t.phone).filter { it.isNotBlank() }.joinToString(" · "), color = Muted) }
                    TextButton({ runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(t.phone))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text("Call") }
                    if (parent && t.id > 0) TextButton({ go(ctx, "Removed", { ctx.hb.api.dropTrusted(t.id) }) { round++ } }) { Text("✕") }
                }
            }
        }
        if (people.isEmpty()) CenterNote(if (parent) "Add a grandparent, an aunt, a neighbour…" else "A parent can add people here.")
        if (parent) SoftButton("＋ Add someone") { adding = true }
    }
    if (adding) {
        var name by remember { mutableStateOf("") }
        var phone by remember { mutableStateOf("") }
        var relation by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { adding = false }, containerColor = Surface, title = { Text("Add a trusted adult") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it.take(40) }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Name") })
                    OutlinedTextField(phone, { phone = it.take(30) }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Phone number") })
                    OutlinedTextField(relation, { relation = it.take(30) }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Who are they? (Grandma, neighbour)") })
                }
            },
            confirmButton = { TextButton({ go(ctx, "Added", { ctx.hb.api.addTrusted(name.trim(), phone.trim(), relation.trim()) }) { round++ }; adding = false }) { Text("Add") } },
            dismissButton = { TextButton({ adding = false }) { Text("Cancel") } })
    }
}


/** Asked once: your birthday, with the year. Your family sees your age and gets a birthday reminder. */
@Composable
fun AgeDialog(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var date by remember { mutableStateOf<LocalDate?>(null) }
    AlertDialog(onDismissRequest = {}, containerColor = Surface, title = { Text("🎂 When is your birthday?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Mellow shows your age to your family, counts down to your birthday and makes sure nobody forgets it.", color = Muted, fontSize = 13.sp)
                SoftButton(date?.let { "${it.dayOfMonth} ${it.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())} ${it.year}" } ?: "Pick the day") {
                    val d = date ?: LocalDate.now().minusYears(12)
                    DatePickerDialog(ctx, { _, y, m, dd -> date = LocalDate.of(y, m + 1, dd) }, d.year, d.monthValue - 1, d.dayOfMonth).apply { datePicker.maxDate = System.currentTimeMillis() }.show()
                }
                date?.let { d ->
                    val now = LocalDate.now()
                    val age = now.year - d.year - (if (now.dayOfYear < d.withYear(now.year).dayOfYear) 1 else 0)
                    Text("You are $age.", style = MaterialTheme.typography.titleMedium)
                }
            }
        },
        confirmButton = { TextButton({ val d = date ?: return@TextButton; go(ctx, "Saved 🎂", { ctx.hb.api.setBirthday(d.monthValue, d.dayOfMonth, d.year) }) { ctx.hb.state.refreshFamily() }; onDone() }, enabled = date != null) { Text("Save") } },
        dismissButton = { TextButton(onDone) { Text("Not now") } })
}


// ================================================================ Quran (Noor): who read what, and reading tasks from parents
private val SURAH_NAMES = listOf("Al-Fatihah","Al-Baqarah","Ali 'Imran","An-Nisa","Al-Ma'idah","Al-An'am","Al-A'raf","Al-Anfal","At-Tawbah","Yunus","Hud","Yusuf","Ar-Ra'd","Ibrahim","Al-Hijr","An-Nahl","Al-Isra","Al-Kahf","Maryam","Taha","Al-Anbiya","Al-Hajj","Al-Mu'minun","An-Nur","Al-Furqan","Ash-Shu'ara","An-Naml","Al-Qasas","Al-'Ankabut","Ar-Rum","Luqman","As-Sajdah","Al-Ahzab","Saba","Fatir","Ya-Sin","As-Saffat","Sad","Az-Zumar","Ghafir","Fussilat","Ash-Shura","Az-Zukhruf","Ad-Dukhan","Al-Jathiyah","Al-Ahqaf","Muhammad","Al-Fath","Al-Hujurat","Qaf","Adh-Dhariyat","At-Tur","An-Najm","Al-Qamar","Ar-Rahman","Al-Waqi'ah","Al-Hadid","Al-Mujadilah","Al-Hashr","Al-Mumtahanah","As-Saff","Al-Jumu'ah","Al-Munafiqun","At-Taghabun","At-Talaq","At-Tahrim","Al-Mulk","Al-Qalam","Al-Haqqah","Al-Ma'arij","Nuh","Al-Jinn","Al-Muzzammil","Al-Muddaththir","Al-Qiyamah","Al-Insan","Al-Mursalat","An-Naba","An-Nazi'at","'Abasa","At-Takwir","Al-Infitar","Al-Mutaffifin","Al-Inshiqaq","Al-Buruj","At-Tariq","Al-A'la","Al-Ghashiyah","Al-Fajr","Al-Balad","Ash-Shams","Al-Layl","Ad-Duha","Ash-Sharh","At-Tin","Al-'Alaq","Al-Qadr","Al-Bayyinah","Az-Zalzalah","Al-'Adiyat","Al-Qari'ah","At-Takathur","Al-'Asr","Al-Humazah","Al-Fil","Quraysh","Al-Ma'un","Al-Kawthar","Al-Kafirun","An-Nasr","Al-Masad","Al-Ikhlas","Al-Falaq","An-Nas")
private val SURAH_AYAHS = listOf(7, 286, 200, 176, 120, 165, 206, 75, 129, 109, 123, 111, 43, 52, 99, 128, 111, 110, 98, 135, 112, 78, 118, 64, 77, 227, 93, 88, 69, 60, 34, 30, 73, 54, 45, 83, 182, 88, 75, 85, 54, 53, 89, 59, 37, 35, 38, 29, 18, 45, 60, 49, 62, 55, 78, 96, 29, 22, 24, 13, 14, 11, 11, 18, 12, 12, 30, 52, 52, 44, 28, 28, 20, 56, 40, 31, 50, 40, 46, 42, 29, 19, 36, 25, 22, 17, 19, 26, 30, 20, 15, 21, 11, 8, 8, 19, 5, 8, 8, 11, 11, 8, 3, 9, 5, 4, 7, 3, 6, 3, 5, 4, 5, 6)
private fun surahName(n: Int) = SURAH_NAMES.getOrNull(n - 1) ?: "Surah $n"
private fun kindWord(k: String) = when (k) { "memorize" -> "Memorize"; "revise" -> "Revise"; else -> "Read" }

@Composable
fun NoorScreen(nav: Nav) {
    val ctx = LocalContext.current
    val parent = ctx.hb.store.isParent
    val me = ctx.hb.store.myId
    var data by remember { mutableStateOf<app.homebase.family.NoorFamily?>(null) }
    var round by remember { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    LaunchedEffect(round) { data = runCatching { ctx.hb.api.noorFamily() }.getOrNull() ?: app.homebase.family.NoorFamily() }
    val open = { url: String -> runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
    Page(nav, "Quran (Noor)", "Noor is the family's Quran app. See who read what, and " + (if (parent) "give reading and memorizing tasks." else "do the tasks your parents give.")) {
        val d = data
        if (d == null) { CenterNote("Loading…"); return@Page }
        val mine = d.members.firstOrNull { it.id == me }
        SoftCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (mine?.linked == true) "Your Noor is linked." else "Link Noor to share your reading with the family and get tasks.", color = Muted, fontSize = 14.sp)
                BigButton(if (mine?.linked == true) "Link Noor again" else "📖 Link Noor") { go(ctx, "", { val c = ctx.hb.api.noorCode(); open(c.url) }) }
                if (d.noorUrl.isNotBlank()) SoftButton("Open Noor") { open(d.noorUrl) }
            }
        }
        if (parent) BigButton("＋ Give a reading task") { adding = true }
        val todo = d.tasks.filter { !it.done }
        SectionLabel("Tasks")
        if (todo.isEmpty()) CenterNote("No open tasks.")
        (todo + d.tasks.filter { it.done }.take(6)).forEach { t ->
            SoftCard {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${kindWord(t.kind)} ${surahName(t.surah)} ${t.from}–${t.upto}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = if (t.done) Muted else Ink)
                        if (t.done) Text("✅")
                    }
                    Text(listOf(if (parent) "for ${t.toName}" else "from ${t.by}", t.note, if (t.due.isNotBlank()) "by ${t.due}" else "",
                        if (t.done) "done " + timeAgo(t.doneAt) + (if (t.score >= 0) " · ${t.score}%" else "") else "").filter { it.isNotBlank() }.joinToString(" · "), color = Muted, fontSize = 13.sp)
                    if (!t.done) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton({ open("${d.noorUrl}/#/s/${t.surah}/${t.from}") }) { Text("Open in Noor") }
                        if (t.toId == me) TextButton({ go(ctx, "Well done!", { ctx.hb.api.noorTaskDone(t.id) }) { round++ } }) { Text("Done") }
                        if (parent) TextButton({ go(ctx, "Removed", { ctx.hb.api.noorDropTask(t.id) }) { round++ } }) { Text("Remove", color = Rose) }
                    }
                }
            }
        }
        SectionLabel("Who read what")
        d.members.forEach { p ->
            SoftCard {
                Column {
                    Text("${p.emoji} ${p.name}", style = MaterialTheme.typography.titleMedium)
                    val s = p.stats
                    if (!p.linked) Text("Not using Noor yet", color = Muted, fontSize = 13.sp)
                    else {
                        Text("🔥 ${s.streak} days · ${s.today} ayahs today" + (s.khatma?.let { " · khatma ${it.done}/${it.days}" } ?: "") + (s.hifz?.let { if (it.surahs > 0) " · ${it.surahs} surahs memorized" else "" } ?: ""), color = Muted, fontSize = 13.sp)
                        s.last?.let { if (it.s > 0) Text("Last read: ${surahName(it.s)} ${it.s}:${it.a}", color = Muted, fontSize = 13.sp) }
                        s.recite?.let { Text("Last recitation: ${it.score}%", color = Muted, fontSize = 13.sp) }
                        if (p.synced > 0) Text("Updated " + timeAgo(p.synced), color = Muted, fontSize = 12.sp)
                    }
                }
            }
        }
    }
    if (adding) {
        val others = data?.members.orEmpty().filter { it.id != me }
        var who by remember { mutableIntStateOf(others.firstOrNull()?.id ?: 0) }
        var kind by remember { mutableStateOf("read") }
        var surah by remember { mutableIntStateOf(67) }
        var from by remember { mutableStateOf("1") }
        var upto by remember { mutableStateOf(SURAH_AYAHS[66].toString()) }
        var note by remember { mutableStateOf("") }
        var query by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { adding = false }, containerColor = Surface, title = { Text("Give a reading task") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { others.forEach { k -> Pill(k.name, who == k.id) { who = k.id } } }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("read", "memorize", "revise").forEach { k -> Pill(kindWord(k), kind == k) { kind = k } } }
                    OutlinedTextField(query, { query = it.take(30) }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Surah: ${surah}. ${surahName(surah)} (type to change)") })
                    if (query.isNotBlank()) {
                        val hits = SURAH_NAMES.mapIndexed { i, n -> i + 1 to n }.filter { (i, n) -> n.contains(query, true) || i.toString() == query.trim() }.take(6)
                        hits.forEach { (i, n) -> TextButton({ surah = i; from = "1"; upto = SURAH_AYAHS[i - 1].toString(); query = "" }) { Text("$i. $n") } }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(from, { from = it.filter(Char::isDigit).take(3) }, Modifier.weight(1f), singleLine = true, label = { Text("From ayah") })
                        OutlinedTextField(upto, { upto = it.filter(Char::isDigit).take(3) }, Modifier.weight(1f), singleLine = true, label = { Text("To ayah") })
                    }
                    OutlinedTextField(note, { note = it.take(200) }, Modifier.fillMaxWidth(), placeholder = { Text("A note (optional)") })
                }
            },
            confirmButton = { TextButton({ if (who != 0) { go(ctx, "Task sent", { ctx.hb.api.noorAddTask(who, kind, surah, from.toIntOrNull() ?: 1, upto.toIntOrNull() ?: 999, note.trim()) }) { round++ }; adding = false } }) { Text("Send") } },
            dismissButton = { TextButton({ adding = false }) { Text("Cancel") } })
    }
}
