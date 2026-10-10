package app.homebase.family.ui

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
        else -> Chip("Not sharing", Color(0xFFEDEFF5), Muted)
    }
}

@Composable
fun FamilyTab(nav: Nav) {
    val ctx = LocalContext.current
    val fam by ctx.hb.state.family.collectAsState()
    val me = ctx.hb.store
    val call = rememberCaller()
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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                    if (m.id != me.myId) {
                        TextButton({ nav.go(Screen.Thread(m.id)) }) { Text("Message") }
                        TextButton({ call(m, false) }) { Text("Call") }
                        TextButton({ askWhere(ctx, m) }) { Text("Where?") }
                    }
                    if (m.id == me.myId || (me.isParent && !m.isParent)) TextButton({ nav.go(Screen.History(m.id)) }) { Text("Trail") }
                }
            }
        }
        if (me.isParent) {
            BigButton("Invite someone to the family") { nav.go(Screen.Invite) }
        }
        SoftButton("Saved places") { nav.go(Screen.Places) }
        SoftButton("Family list") { nav.go(Screen.Lists) }
        Gap(8)
    }
}
