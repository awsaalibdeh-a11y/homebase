package app.homebase.family.ui

import androidx.compose.runtime.setValue
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
    val call = rememberCaller()
    var lockFor by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Member?>(null) }
    var groundFor by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Member?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Heading(fam?.family?.name ?: me.familyName.ifBlank { "Family" }, "${fam?.members?.size ?: 1} people")
        fam?.members.orEmpty().forEach { m ->
            SoftCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Avatar(m.emoji, 52, if (m.isParent) SunSoft else BlueSoft)
                    Column(Modifier.weight(1f)) {
                        Text(if (m.id == me.myId) "${m.name} (you)" else m.name, style = MaterialTheme.typography.titleMedium)
                        Text(memberLine(m), style = MaterialTheme.typography.bodyMedium, color = Muted)
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Chip(if (m.isParent) "Parent" else "Child")
                            ShareChip(m)
                            if (m.quiet) Chip("Quiet hours", SunSoft, Ink)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    if (m.id != me.myId) {
                        TextButton({ nav.go(Screen.Thread(m.id)) }, enabled = canDo(m, "messages")) { Text("Message", maxLines = 1, softWrap = false) }
                        TextButton({ call(m, false) }, enabled = canDo(m, "calls")) { Text("Call", maxLines = 1, softWrap = false) }
                        TextButton({ askWhere(ctx, m) }, enabled = canDo(m, "ping")) { Text("Where?", maxLines = 1, softWrap = false) }
                    }
                    if (m.id == me.myId || (me.isParent && !m.isParent)) TextButton({ nav.go(Screen.History(m.id)) }) { Text("Trail", maxLines = 1, softWrap = false) }
                }
                if (!m.isParent && (m.id == me.myId || me.isParent)) Row(Modifier.fillMaxWidth()) {
                    if (me.isParent) TextButton({ lockFor = m }) { Text(if (m.lock != null) "🔒 Locked" else "Lock", maxLines = 1, softWrap = false) }
                    if (me.isParent) TextButton({ groundFor = m }) { Text(if (m.blocks.isNotEmpty()) "🚫 Grounded (${m.blocks.size})" else "Ground apps", maxLines = 1, softWrap = false) }
                    TextButton({ nav.go(Screen.ScreenTime(m.id)) }, enabled = canDo(m, "screentime")) { Text("Screen time", maxLines = 1, softWrap = false) }
                }
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
    lockFor?.let { LockDialog(it) { lockFor = null } }
    groundFor?.let { GroundDialog(it) { groundFor = null } }
}
