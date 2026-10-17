package app.homebase.family.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.sp
import app.homebase.family.Member
import app.homebase.family.hb
import kotlinx.coroutines.launch

@Composable
private fun ShareChip(m: Member) {
    when (m.share) {
        "on" -> Chip("Sharing location", MintSoft, Mint)
        "paused" -> Chip("Paused", SunSoft, Ink)
        else -> Chip("Not sharing", Surface2, Muted)
    }
}

/** One person: where they are, what they are up to, and the buttons to reach them. Used on the Map tab. */
@Composable
fun PersonCard(nav: Nav, m: Member, onFocus: (app.homebase.family.Spot) -> Unit) {
    val ctx = LocalContext.current
    val me = ctx.hb.store
    val call = rememberCaller()
    var more by remember { mutableStateOf(false) }
    var expectOpen by remember { mutableStateOf(false) }
    var speedOpen by remember { mutableStateOf(false) }
    SoftCard(onClick = { m.location?.let(onFocus) }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MemberAvatar(m, 52, if (m.isParent) SunSoft else AccentSoft)
            Column(Modifier.weight(1f)) {
                Text((if (m.id == me.myId) "${m.name} (you)" else m.name) + (m.age?.let { " · $it" } ?: ""), style = MaterialTheme.typography.titleMedium)
                Text(memberLine(m), style = MaterialTheme.typography.bodyMedium, color = Muted)
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(if (m.isParent) "Parent" else "Child")
                    ShareChip(m)
                    if (m.quiet) Chip("Quiet hours", SunSoft, Ink)
                    m.status?.let { s -> Chip("${s.emoji} ${s.text}".trim(), AccentSoft, AccentDark) }
                    if (me.isParent && m.id != me.myId && m.appVersion in 1 until app.homebase.family.BuildConfig.VERSION_CODE) Chip("App update available", SunSoft, Ink)
                }
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (m.sos) Chip("🚨 SOS live", Rose, androidx.compose.ui.graphics.Color.White)
                    m.eta?.let { e -> if (e.until * 1000 > System.currentTimeMillis()) Chip("🏃 about ${e.min} min from home", AccentSoft, AccentDark) }
                    if (!m.isParent && (me.isParent || m.id == me.myId) && m.safeDns != null) Chip(safeDnsLabel(m.safeDns), if (safeDnsLabel(m.safeDns).startsWith("🛡️")) MintSoft else SunSoft, Ink)
                }
            }
        }
        if (!m.isParent) screenLine(m)?.let { Text(it, Modifier.padding(top = 2.dp), color = if (m.screen?.hidden == true) Sun else Ink, style = MaterialTheme.typography.bodyMedium) }
        Row(Modifier.fillMaxWidth()) {
            if (m.id != me.myId) {
                TextButton({ nav.go(Screen.Thread(m.id)) }, enabled = canDo(m, "messages")) { Text("Message", maxLines = 1, softWrap = false) }
                TextButton({ call(m, false) }, enabled = canDo(m, "calls")) { Text("Call", maxLines = 1, softWrap = false) }
                TextButton({ askWhere(ctx, m) }, enabled = canDo(m, "ping")) { Text("Where?", maxLines = 1, softWrap = false) }
            }
            if (m.id == me.myId || (me.isParent && !m.isParent)) TextButton({ nav.go(Screen.History(m.id)) }) { Text("Trail", maxLines = 1, softWrap = false) }
            Box {
                TextButton({ more = true }) { Text("More ▾", maxLines = 1, softWrap = false) }
                androidx.compose.material3.DropdownMenu(more, { more = false }) {
                    if (m.id == me.myId || (me.isParent && !m.isParent)) androidx.compose.material3.DropdownMenuItem({ Text("🕓 Today's places") }, { more = false; nav.go(Screen.Timeline(m.id)) })
                    if (m.id != me.myId) androidx.compose.material3.DropdownMenuItem({ Text("⭐ Give kudos") }, { more = false; nav.go(Screen.Kudos) })
                    if (me.isParent && !m.isParent) {
                        androidx.compose.material3.DropdownMenuItem({ Text("😊 Moods") }, { more = false; nav.go(Screen.Mood(m.id)) })
                        androidx.compose.material3.DropdownMenuItem({ Text("📍 Tell me when they get there") }, { more = false; expectOpen = true })
                        androidx.compose.material3.DropdownMenuItem({ Text("🚗 Speed alert" + if (m.speedLimit > 0) " (${m.speedLimit} km/h)" else "") }, { more = false; speedOpen = true })
                        androidx.compose.material3.DropdownMenuItem({ Text("🌙 Bedtime reminder") }, { more = false; pickBedtime(ctx, m.id) })
                    }
                    if (m.id == me.myId || (me.isParent && (!m.isParent || true))) androidx.compose.material3.DropdownMenuItem({ Text("📱 Ring their phone") }, {
                        more = false
                        ctx.hb.scope.launch { runCatching { ctx.hb.api.ring(m.id) }.onSuccess { toast(ctx, "Ringing ${m.name}'s phone") }.onFailure { toast(ctx, it.message ?: "Could not ring it") } }
                    })
                }
            }
        }
        m.walk?.let { w -> Text("🚶 Safe Walk to ${w.emoji} ${w.place} · live", color = Ink, style = MaterialTheme.typography.bodyMedium) }
        if (expectOpen) ExpectDialog(m) { expectOpen = false }
        if (speedOpen) SpeedDialog(m) { speedOpen = false }
        if (!m.isParent && me.isParent) BigButton("🎛️ Controls: screen time, lock, ground apps") { nav.go(Screen.Controls(m.id)) }
        if (!m.isParent && me.isParent) TextButton({
            ctx.hb.scope.launch { runCatching { ctx.hb.api.critical(m.id) }.onSuccess { toast(ctx, "${m.name}'s phone will ring until they tap I'm OK") }.onFailure { toast(ctx, it.message ?: "Could not send it") } }
        }, enabled = canDo(m, "critical")) { Text("🚨 Critical Check-In (rings through silent)", maxLines = 1) }
        if (!m.isParent && m.id == me.myId) TextButton({ nav.go(Screen.ScreenTime(m.id)) }) { Text("My screen time", maxLines = 1, softWrap = false) }
        if (me.isParent && m.id != me.myId) TextButton({
            ctx.hb.scope.launch { runCatching { ctx.hb.api.askUpdate(m.id) }.onSuccess { toast(ctx, "Asked ${m.name} to update") }.onFailure { toast(ctx, it.message ?: "Could not ask") } }
        }) { Text("📲 Ask ${m.name} to update Mellow", maxLines = 1) }
    }
}

/** Home: today at a glance. What is for dinner, what is coming up, quick check-ins, and a way to start a family game. */
@Composable
fun HomeTab(nav: Nav) {
    val ctx = LocalContext.current
    val state = ctx.hb.state
    val fam by state.family.collectAsState()
    val me = ctx.hb.store
    val plans by state.plans.collectAsState()
    val polls by state.polls.collectAsState()
    val meals by state.meals.collectAsState()
    var statusOpen by remember { mutableStateOf(false) }
    var announceOpen by remember { mutableStateOf(false) }
    var lateOpen by remember { mutableStateOf(false) }
    var planOpen by remember { mutableStateOf(false) }
    var mealOpen by remember { mutableStateOf(false) }
    var pollOpen by remember { mutableStateOf(false) }
    var walkOpen by remember { mutableStateOf(false) }
    val nameOf: (Int) -> String = { id -> if (id == me.myId) "You" else fam?.members?.firstOrNull { it.id == id }?.name ?: "Someone" }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SetupBanner()
        SafetyBanners()
        SosAlerts(nav)
        WalkBanner()
        Heading(fam?.family?.name ?: me.familyName.ifBlank { "Home" }, "${fam?.members?.size ?: 1} people")
        if (!me.isParent) SosButton { state.sosAsk.value = true }
        GroupRequests()
        val now = System.currentTimeMillis() / 1000.0
        val all = fam?.members.orEmpty()
        val live = all.count { it.share == "on" && it.location != null && now - (it.location?.at ?: 0.0) < 150 }
        val home = all.count { it.home }
        val mine = all.firstOrNull { it.id == me.myId }?.status
        SoftCard(tint = AccentSoft) {
            fam?.family?.notice?.let { n ->
                Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("📢 ${n.text}", style = MaterialTheme.typography.titleMedium)
                        Text(n.by, color = Muted, fontSize = 12.sp)
                    }
                    if (me.isParent) TextButton({ ctx.hb.scope.launch { runCatching { ctx.hb.api.clearNotice() }; ctx.hb.state.refreshFamily() } }) { Text("✕") }
                }
            }
            Text("Today", style = MaterialTheme.typography.titleMedium)
            Text(listOfNotNull("${all.size} people", if (live > 0) "$live live on the map" else "nobody live right now", if (home > 0) "$home at home" else null).joinToString(" · "), color = Muted, style = MaterialTheme.typography.bodyMedium)
            plans.firstOrNull()?.let { p -> Text("Next: ${p.emoji} ${p.title} · ${planWhen(p.start)}", Modifier.padding(top = 4.dp), color = Ink, style = MaterialTheme.typography.bodyMedium) }
            all.mapNotNull { b -> b.birthday?.let { b to daysToBirthday(it) } }.filter { it.second <= 30 }.minByOrNull { it.second }?.let { (b, days) ->
                Text("🎂 " + (if (days == 0) "It's ${b.name}'s birthday today!" + (b.age?.let { " $it today 🎉" } ?: "") else "${b.name}" + (b.age?.let { " turns ${it + 1}" } ?: "'s birthday is") + " ${if (days == 1) "tomorrow" else "in $days days"}"), Modifier.padding(top = 4.dp), color = Ink, style = MaterialTheme.typography.bodyMedium)
            }
            SoftButton(if (mine != null) "${mine.emoji} ${mine.text}".trim() else "🙂 What I'm up to", Modifier.padding(top = 10.dp)) { statusOpen = true }
            if (me.isParent) SoftButton("📢 Announce to the family", Modifier.padding(top = 8.dp)) { announceOpen = true }
        }
        WeatherCard()
        CountdownsCard()
        val today = java.time.LocalDate.now().toString()
        val tonight = meals.filter { it.day == today }.sortedBy { MEAL_SLOTS.indexOf(it.slot) }
        SectionLabel("🍽️ What are we eating?")
        if (tonight.isEmpty()) SoftCard { Text("Nothing planned today.", color = Muted); TextButton({ mealOpen = true }) { Text("Add a meal") } }
        else tonight.forEach { MealCard(it, nav, nameOf) }
        SectionLabel("Quick")
        TwoUp({ BigButton("I'm OK ✓", color = Mint) { sendQuick(ctx, "checkin", "I'm OK", 0, true, "Checked in") } }, { SoftButton("Running late ⏰") { lateOpen = true } })
        TwoUp({ SoftButton("Pick me up 🚗") { sendQuick(ctx, "pickup", "Can someone pick me up?", 0, true, "Sent to the family") } }, { SoftButton("🎮 Family game") { startFamilyGame(ctx) } })
        TwoUp({ SoftButton("📅 Add a plan") { planOpen = true } }, { SoftButton("🗳️ Ask the family") { pollOpen = true } })
        TwoUp({ SoftButton("🔎 Search recipes") { nav.go(Screen.Recipes()) } }, { SoftButton("🎉 Fun") { nav.go(Screen.Fun) } })
        TwoUp({ SoftButton("🚶 Safe Walk") { walkOpen = true } }, { SoftButton("😊 How I feel") { nav.go(Screen.Mood()) } })
        val waiting = polls.count { !it.closed && it.mine == null }
        if (waiting > 0) SoftButton("🗳️ $waiting to vote on") { state.tab.value = 2 }
        if (plans.isNotEmpty()) {
            SectionLabel("📅 Coming up")
            plans.take(2).forEach { PlanCard(it, nameOf) }
        }
        if (me.isParent) {
            val kids = fam?.members.orEmpty().filter { !it.isParent }
            if (kids.isNotEmpty()) SoftCard(tint = AccentSoft) {
                Text("⏱️ Screen time today", style = MaterialTheme.typography.titleMedium)
                kids.forEach { k ->
                    val sc = k.screen
                    Row(Modifier.fillMaxWidth().clickable { nav.go(Screen.ScreenTime(k.id)) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        MemberAvatar(k, 36, Surface2)
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(k.name, style = MaterialTheme.typography.titleSmall)
                            Text(if (sc == null) "Not linked yet" else if (sc.hidden) "Switched off by ${k.name}" else if (sc.top.isNotBlank()) "Most on ${sc.top}" else "Nothing counted yet", color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(if (sc == null || sc.hidden) "–" else app.homebase.family.durationText(sc.today), style = MaterialTheme.typography.titleLarge)
                    }
                }
                Text("Tap a child for the full list of apps", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        Gap(8)
    }
    if (statusOpen) StatusDialog { statusOpen = false }
    if (announceOpen) AnnounceDialog { announceOpen = false }
    if (planOpen) AddPlanDialog(null) { planOpen = false }
    if (pollOpen) AskPollDialog { pollOpen = false }
    if (walkOpen) WalkDialog { walkOpen = false }
    if (mealOpen) AddMealDialog(null) { mealOpen = false }
    if (lateOpen) PickDialog("How late will you be?", LATE_CHOICES, { lateSend(ctx, it) }, { lateOpen = false })
}
