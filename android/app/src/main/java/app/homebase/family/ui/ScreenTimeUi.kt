package app.homebase.family.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import app.homebase.family.ApiError
import app.homebase.family.ScreenApp
import app.homebase.family.ScreenDay
import app.homebase.family.ScreenTimeTracker
import app.homebase.family.durationText
import app.homebase.family.hb
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

private fun catEmoji(c: String) = when (c) { "watch" -> "📺"; "social" -> "💬"; else -> "🎮" }
private fun catWord(c: String) = when (c) { "watch" -> "Watching"; "social" -> "Scrolling"; else -> "Playing" }
private fun catColor(c: String) = when (c) { "watch" -> Rose; "social" -> Sun; else -> Blue }

/** A child's screen time: only time spent actively playing or watching, with the apps it was spent in. A parent sees it if the child allows. */
@Composable
fun ScreenTimeScreen(nav: Nav, memberId: Int) {
    val ctx = LocalContext.current
    val fam by ctx.hb.state.family.collectAsState()
    val m = fam?.members?.firstOrNull { it.id == memberId }
    val mine = memberId == ctx.hb.store.myId
    val scope = ctx.hb.scope
    var days by remember { mutableStateOf<List<ScreenDay>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf("") }
    var pick by remember { mutableIntStateOf(0) }                       // 0 = today, 1 = yesterday ...
    var reload by remember { mutableIntStateOf(0) }
    var sum by remember { mutableStateOf<app.homebase.family.SummaryResp?>(null) }
    LaunchedEffect(memberId) { runCatching { ctx.hb.api.summary(memberId) }.onSuccess { sum = it } }
    LaunchedEffect(reload) {
        loaded = false; problem = ""
        runCatching { ctx.hb.api.screenTime(memberId, 7) }
            .onSuccess { days = it.days }
            .onFailure { problem = (it as? ApiError)?.message ?: "Could not load it. Check your connection." }
        loaded = true
    }
    val week = (0..6).map { LocalDate.now().minusDays(it.toLong()) }
    val byDay = days.associateBy { it.day }
    val localToday = if (mine) ScreenTimeTracker.readToday(ctx.hb.store) else emptyList()
    fun dayData(i: Int): ScreenDay {
        val d = week[i].toString()
        val server = byDay[d]
        if (i == 0 && mine && localToday.isNotEmpty() && (server == null || localToday.sumOf { it.sec } > server.total)) return ScreenDay(d, localToday.sumOf { it.sec }, localToday)   // your own phone is always the freshest
        return server ?: ScreenDay(d)
    }
    val shown = dayData(pick)
    val maxTotal = (0..6).maxOf { dayData(it).total }.coerceAtLeast(1)
    val name = if (mine) "Your" else "${m?.name ?: "Their"}'s"

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { TextButton({ nav.back() }) { Text("Back") } }
        Heading("$name screen time", "Only time spent actively playing or watching. Not when the screen is off, the phone is sitting idle, or in study and work apps.")
        if (problem.isNotEmpty()) SoftCard { Text(problem, color = Muted) }
        else {
            SoftCard {
                Text(when (pick) { 0 -> "Today"; 1 -> "Yesterday"; else -> week[pick].dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()) }, color = Muted)
                Text(if (!loaded) "Loading…" else durationText(shown.total), style = MaterialTheme.typography.headlineMedium)
                Row(Modifier.fillMaxWidth().padding(top = 12.dp).height(110.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                    (6 downTo 0).forEach { i ->
                        val d = dayData(i)
                        Column(Modifier.weight(1f).clickable { pick = i }, horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.fillMaxWidth().height((8 + 70 * d.total / maxTotal).dp).clip(RoundedCornerShape(6.dp)).background(if (i == pick) Blue else Surface2))
                            Text(week[i].dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()), Modifier.padding(top = 4.dp), color = if (i == pick) Ink else Muted, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            sum?.let { s ->
                SoftCard {
                    Text("This week", style = MaterialTheme.typography.titleMedium)
                    s.screen?.let { sc ->
                        Text("${durationText(sc.total)} playing and watching · ${durationText(sc.avg)} a day on average", color = Muted)
                        if (sc.top.isNotEmpty()) Text("Most time: " + sc.top.joinToString(" · ") { "${it.label} ${durationText(it.sec)}" }, color = Muted)
                    }
                    Text("${s.chores.done} jobs done (${s.chores.points} points) · " + (if (s.locks.minutes > 0) "${durationText(s.locks.minutes * 60)} locked" else "no locks"), color = Muted)
                }
            }
            SectionLabel("Apps")
            if (shown.apps.isEmpty() && loaded) SoftCard {
                Text(if (mine) "Nothing counted for this day yet." else "Nothing recorded for this day.", style = MaterialTheme.typography.titleMedium)
                Text(if (mine) "Turn on \"Track my screen time\" in Me, then it counts while you play or watch." else "${m?.name ?: "They"} may not have turned tracking on yet. It is their choice, and it counts only on their own phone.", Modifier.padding(top = 4.dp), color = Muted)
            }
            shown.apps.sortedByDescending { it.sec }.forEach { a -> AppRow(a, shown.apps.maxOf { it.sec }.coerceAtLeast(1)) }
            if (mine && days.isNotEmpty()) SoftButton("Delete my screen time data") { scope.launch { runCatching { ctx.hb.api.deleteScreenTime() }.onSuccess { ctx.hb.store.screenData = "{}"; reload++; toast(ctx, "Deleted") } } }
            Text("Screen time is kept for a month, then deleted. Homebase only counts minutes per app. It never sees what is on the screen.", color = Muted)
        }
        Gap(8)
    }
}

@Composable
private fun AppRow(a: ScreenApp, max: Int) {
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(catEmoji(a.cat), style = MaterialTheme.typography.headlineSmall)
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(a.label.ifBlank { a.pkg }, style = MaterialTheme.typography.titleMedium)
                    Text(durationText(a.sec), color = Muted)
                }
                Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Surface2)) {
                    Box(Modifier.fillMaxWidth(a.sec.toFloat() / max).height(6.dp).clip(RoundedCornerShape(3.dp)).background(catColor(a.cat)))
                }
                Text(catWord(a.cat), color = Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/** For a child, in Me: the switch that starts or stops counting, the one permission it needs, and today's total. Always their choice. */
@Composable
fun ScreenTimeCard(nav: Nav) {
    val ctx = LocalContext.current
    val store = ctx.hb.store
    var on by remember { mutableStateOf(store.trackScreen) }
    var access by remember { mutableStateOf(app.homebase.family.ScreenTimeTracker.hasAccess(ctx)) }
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {                                          // coming back from the system settings page: look again
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) { access = ScreenTimeTracker.hasAccess(ctx); tick++ } }
        owner.lifecycle.addObserver(obs); onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val today = remember(tick) { ScreenTimeTracker.readToday(store).sumOf { it.sec } }
    SoftCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Track my screen time", style = MaterialTheme.typography.titleMedium)
                Text("Counts only when you are playing or watching, never when you are idle or studying. You and your parents can see the minutes per app. Never what is on the screen.", color = Muted)
            }
            Switch(checked = on, onCheckedChange = { v ->
                on = v; store.trackScreen = v
                if (v && !access) ScreenTimeTracker.openAccessSettings(ctx)
            })
        }
        if (on && !access) {
            Text("One more step: allow \"Usage access\" for Homebase on the page that opened. Android asks for this once.", Modifier.padding(top = 8.dp), color = Sun)
            TextButton({ ScreenTimeTracker.openAccessSettings(ctx) }) { Text("Open the page again") }
        } else if (on) {
            Text("Today: ${durationText(today)}", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium)
        }
        TextButton({ nav.go(Screen.ScreenTime(store.myId)) }) { Text("See details") }
    }
    val famNow by ctx.hb.state.family.collectAsState()
    val mine = famNow?.members?.firstOrNull { it.id == store.myId }?.blocks.orEmpty()
    if (mine.isNotEmpty()) SoftCard {
        Text("🚫 Grounded apps", style = MaterialTheme.typography.titleMedium)
        mine.forEach { b -> Text("${b.label} until ${clockOf(b.until)}", color = Muted) }
    }
    if (!android.provider.Settings.canDrawOverlays(ctx)) SoftCard {
        Text("Parent locks", style = MaterialTheme.typography.titleMedium)
        Text("A parent can lock your device for a while (never more than 4 hours a day). To show the lock over other apps, allow \"Display over other apps\" for Homebase. You can always ask to be unlocked or send an SOS.", color = Muted)
        TextButton({ ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + ctx.packageName)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Allow the lock screen") }
    }
}
