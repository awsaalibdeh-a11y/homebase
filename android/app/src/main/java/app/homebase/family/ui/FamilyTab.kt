package app.homebase.family.ui

import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.homebase.family.Member
import app.homebase.family.hb

@Composable
private fun ShareChip(m: Member) {
    when (m.share) {
        "on" -> Chip("Sharing location", MintSoft, Mint)
        "paused" -> Chip("Paused", SunSoft, Ink)
        else -> Chip("Not sharing", Surface2, Muted)
    }
}

@Composable
fun FamilyTab(nav: Nav) {
    val ctx = LocalContext.current
    val fam by ctx.hb.state.family.collectAsState()
    val me = ctx.hb.store
    val plans by ctx.hb.state.plans.collectAsState()
    val polls by ctx.hb.state.polls.collectAsState()
    val call = rememberCaller()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Heading(fam?.family?.name ?: me.familyName.ifBlank { "Family" }, "${fam?.members?.size ?: 1} people")
        var statusOpen by remember { mutableStateOf(false) }
        run {
            val now = System.currentTimeMillis() / 1000.0
            val all = fam?.members.orEmpty()
            val live = all.count { it.share == "on" && it.location != null && now - (it.location?.at ?: 0.0) < 150 }
            val home = all.count { it.home }
            val mine = all.firstOrNull { it.id == me.myId }?.status
            SoftCard(tint = AccentSoft) {
                Text("Today", style = MaterialTheme.typography.titleMedium)
                Text(listOfNotNull("${all.size} people", if (live > 0) "$live live on the map" else "nobody live right now", if (home > 0) "$home at home" else null).joinToString(" · "), color = Muted, style = MaterialTheme.typography.bodyMedium)
                plans.firstOrNull()?.let { p -> Text("Next: ${p.emoji} ${p.title} · ${planWhen(p.start)}", Modifier.padding(top = 4.dp), color = Ink, style = MaterialTheme.typography.bodyMedium) }
                SoftButton(if (mine != null) "${mine.emoji} ${mine.text}".trim() else "🙂 What I'm up to", Modifier.padding(top = 10.dp)) { statusOpen = true }
                val waiting = polls.count { !it.closed && it.mine == null }
                SoftButton("📅 Plans and votes" + (if (waiting > 0) " · $waiting to vote" else ""), Modifier.padding(top = 8.dp)) { nav.go(Screen.Plans) }
            }
        }
        if (statusOpen) StatusDialog { statusOpen = false }
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftButton("🗺️ Map", Modifier.weight(1f)) { nav.go(Screen.MapFull) }
            SoftButton("💬 Chat", Modifier.weight(1f)) { nav.go(Screen.ChatFull) }
            if (me.isParent) SoftButton("📋 Rules", Modifier.weight(1f)) { nav.go(Screen.Rules) }
        }
        fam?.members.orEmpty().forEach { m ->
            SoftCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MemberAvatar(m, 52, if (m.isParent) SunSoft else AccentSoft)
                    Column(Modifier.weight(1f)) {
                        Text(if (m.id == me.myId) "${m.name} (you)" else m.name, style = MaterialTheme.typography.titleMedium)
                        Text(memberLine(m), style = MaterialTheme.typography.bodyMedium, color = Muted)
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Chip(if (m.isParent) "Parent" else "Child")
                            ShareChip(m)
                            if (m.quiet) Chip("Quiet hours", SunSoft, Ink)
                            m.status?.let { s -> Chip("${s.emoji} ${s.text}".trim(), AccentSoft, AccentDark) }
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
                }
                if (!m.isParent && me.isParent) BigButton("🎛️ Controls: screen time, lock, ground apps") { nav.go(Screen.Controls(m.id)) }
                if (!m.isParent && m.id == me.myId) TextButton({ nav.go(Screen.ScreenTime(m.id)) }) { Text("My screen time", maxLines = 1, softWrap = false) }
            }
        }
        if (me.isParent) {
            BigButton("Invite someone to the family") { nav.go(Screen.Invite) }
        }
        SoftButton("Jobs and points") { nav.go(Screen.Chores) }
        SoftButton("Saved places") { nav.go(Screen.Places) }
        SoftButton("Family list") { nav.go(Screen.Lists) }
        Gap(8)
    }
}
