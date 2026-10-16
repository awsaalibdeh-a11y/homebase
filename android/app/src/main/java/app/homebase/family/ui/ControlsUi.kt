package app.homebase.family.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import app.homebase.family.DeviceInfo
import app.homebase.family.Member
import app.homebase.family.durationText
import app.homebase.family.hb
import kotlinx.coroutines.launch

/** "Today 1 h 12 min · Roblox", or a plain note when the child switched it off. */
fun screenSub(m: Member): String {
    val s = m.screen ?: return "Minutes and apps"
    if (s.hidden) return "Switched off by ${m.name}"
    return if (s.today > 0) "Today ${durationText(s.today)}" + (if (s.top.isNotBlank()) " · ${s.top}" else "") else "Nothing counted today"
}

/** The line shown under a child's name on the Family tab, so a parent sees screen time without opening anything. */
fun screenLine(m: Member): String? {
    val s = m.screen ?: return null
    if (s.hidden) return "⏱ ${m.name} has switched screen time off"
    return "⏱ ${durationText(s.today)} today" + (if (s.top.isNotBlank()) " · most on ${s.top}" else "")
}

@Composable
private fun Tile(emoji: String, title: String, sub: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    SoftCard(modifier.clickable(enabled = enabled) { onClick() }, tint = if (enabled) Surface2 else Surface) {
        Column(Modifier.heightIn(min = 84.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(emoji, style = MaterialTheme.typography.headlineSmall)
            Text(title, style = MaterialTheme.typography.titleSmall, color = if (enabled) Ink else Muted)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }
}

/** Everything a parent can do for one child, as big labelled tiles that show the current state. Same six tiles as on the laptop. */
@Composable
fun ControlsScreen(nav: Nav, memberId: Int) {
    val ctx = LocalContext.current
    val fam by ctx.hb.state.family.collectAsState()
    val m = fam?.members?.firstOrNull { it.id == memberId }
    var lockOpen by remember { mutableStateOf(false) }
    var groundOpen by remember { mutableStateOf(false) }
    var devicesOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { TextButton({ nav.back() }) { Text("Back") } }
        if (m == null) { CenterNote("This person is not in the family any more."); return@Column }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            MemberAvatar(m, 56, AccentSoft)
            Column(Modifier.weight(1f)) { Text(m.name, style = MaterialTheme.typography.headlineSmall); Text(memberLine(m), color = Muted) }
        }
        screenLine(m)?.let { SoftCard { Text(it, style = MaterialTheme.typography.titleMedium) } }
        if (m.lock != null) SoftCard(tint = SunSoft) { Text("🔒 Locked by ${m.lock.byName} until ${clockOf(m.lock.until)}") }
        val canScreen = m.allows("screentime")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile("⏱️", "Screen time", screenSub(m), canScreen, Modifier.weight(1f)) { nav.go(Screen.ScreenTime(m.id)) }
            Tile("🔒", "Lock device", if (m.lock != null) "Locked until ${clockOf(m.lock.until)}" else "Lock their devices", true, Modifier.weight(1f)) { lockOpen = true }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile("🚫", "Ground apps", if (m.blocks.isNotEmpty()) "${m.blocks.size} grounded now" else "Block certain apps", true, Modifier.weight(1f)) { groundOpen = true }
            Tile("📋", "Rules and limit", "House rules, daily limit", true, Modifier.weight(1f)) { nav.go(Screen.Rules) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile("💻", "Computers", if (m.devices.isEmpty()) "None linked yet" else m.devices.joinToString(", ") { it.name }, true, Modifier.weight(1f)) { devicesOpen = true }
            Tile("📊", "This week", "Summary", canScreen, Modifier.weight(1f)) { nav.go(Screen.ScreenTime(m.id)) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile("🧭", "Trail", "Where they have been", m.allows("trail"), Modifier.weight(1f)) { nav.go(Screen.History(m.id)) }
            Tile("💬", "Message", "Send a message", m.allows("messages"), Modifier.weight(1f)) { nav.go(Screen.Thread(m.id)) }
        }
        Spacer(Modifier.height(8.dp))
        if (lockOpen) LockDialog(m) { lockOpen = false }
        if (groundOpen) GroundDialog(m) { groundOpen = false }
        if (devicesOpen) DevicesDialog(m) { devicesOpen = false }
    }
}

/** The computers and tablets linked to a person, with when each last reported and what it sees right now. */
@Composable
fun DevicesDialog(m: Member, onClose: () -> Unit) {
    val now = System.currentTimeMillis() / 1000.0
    AlertDialog(onDismissRequest = onClose, title = { Text("💻 ${m.name}'s computers") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (m.devices.isEmpty()) Text("No computer is linked yet. ${m.name} links one from Homebase on the laptop: Menu, Screen time, Link a Windows computer.", color = Muted)
            m.devices.forEach { d ->
                val quiet = now - d.lastSeen > 20 * 60
                Text("${if (d.kind == "windows") "💻" else "📱"} ${d.name}", style = MaterialTheme.typography.titleSmall)
                Text((if (d.lastSeen > 0) (if (quiet) "Quiet for " else "Reported ") + timeAgo(d.lastSeen) else "Not reported yet") + (if (!quiet && d.status.isNotBlank()) " · ${d.status}" else "") + (if (d.version.isNotBlank()) " · ${d.version}" else ""),
                    color = if (quiet && d.lastSeen > 0) Sun else Muted)
            }
        }
    }, confirmButton = { TextButton(onClose) { Text("Close") } })
}
