package app.homebase.family.ui

import android.app.TimePickerDialog
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
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.homebase.family.hb
import java.util.TimeZone
import kotlinx.coroutines.launch

/** One square tile: a big emoji, a name, and a line about it. */
@Composable
fun Tile(emoji: String, title: String, sub: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(Surface.copy(alpha = 0.92f)).clickable { onClick() }.padding(14.dp).height(104.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(emoji, fontSize = 28.sp)
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
        Text(sub, color = Muted, fontSize = 12.sp, maxLines = 2, lineHeight = 15.sp)
    }
}

class TileItem(val emoji: String, val title: String, val sub: String, val go: () -> Unit)

@Composable
fun TileGrid(items: List<TileItem>) {
    items.chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { t -> Tile(t.emoji, t.title, t.sub, Modifier.weight(1f)) { t.go() } }
            if (row.size == 1) Box(Modifier.weight(1f))
        }
    }
}

private val MORE_TABS = listOf("Me", "Family", "Safety", "Settings")

/** More: four tidy tabs instead of one long list. */
@Composable
fun MeTab(nav: Nav) {
    val ctx = LocalContext.current
    val app = ctx.hb
    val scope = app.scope
    val parent = app.store.isParent
    val fam by app.state.family.collectAsState()
    val section by app.state.moreTab.collectAsState()
    val me = fam?.members?.firstOrNull { it.id == app.store.myId }
    var pause by remember { mutableStateOf(false) }
    var late by remember { mutableStateOf(false) }
    var statusOpen by remember { mutableStateOf(false) }
    var birthdayOpen by remember { mutableStateOf(false) }
    var tutorial by remember { mutableStateOf(false) }
    var linkApps by remember { mutableStateOf(false) }
    var tripOpen by remember { mutableStateOf(false) }
    var walkOpen by remember { mutableStateOf(false) }
    var quietFrom by remember { mutableIntStateOf(-1) }
    var quietTo by remember { mutableIntStateOf(-1) }
    LaunchedEffect(Unit) {
        runCatching { app.api.me() }.onSuccess { quietFrom = it.quiet.from; quietTo = it.quiet.to }
        runCatching { app.api.patchMe("tz_offset" to -(TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000)) }
    }
    fun setSharing(state: String, minutes: Int = 60) = scope.launch {
        runCatching { app.api.sharing(state, minutes) }.onSuccess { app.state.refreshFamily() }.onFailure { toast(ctx, it.message ?: "Could not change it") }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
            Heading("Hi, ${me?.name ?: app.store.myName} ${me?.emoji ?: ""}", app.store.familyName)
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surface2).padding(4.dp)) {
                MORE_TABS.forEachIndexed { i, name ->
                    Text(name, Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (section == i) Surface else Surface2).clickable { app.state.moreTab.value = i }.padding(vertical = 10.dp),
                        style = MaterialTheme.typography.labelLarge, color = if (section == i) Ink else Muted, textAlign = TextAlign.Center)
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (section) {
                0 -> {
                    SetupBanner()
                    me?.let { meM ->
                        SoftCard {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                MemberAvatar(meM, 64, if (meM.isParent) SunSoft else AccentSoft)
                                Column(Modifier.weight(1f)) { Text("Your profile picture", style = MaterialTheme.typography.titleMedium); Text("A photo of your face, taken here in the app.", color = Muted) }
                            }
                            Row {
                                TextButton({ nav.go(Screen.Camera("pfp")) }) { Text(if (meM.avatar != null) "Take a new one" else "Take a selfie") }
                                if (meM.avatar != null) TextButton({ scope.launch { runCatching { ctx.hb.api.clearAvatar() }.onSuccess { app.state.refreshFamily(); toast(ctx, "Photo removed") } } }) { Text("Remove") }
                            }
                        }
                    }
                    val share = me?.share ?: "off"
                    SoftCard(tint = when (share) { "on" -> MintSoft; "paused" -> SunSoft; else -> Surface }) {
                        Text(when (share) { "on" -> "📍 Sharing your location"; "paused" -> "⏸ Sharing is paused"; else -> "Location sharing is off" }, style = MaterialTheme.typography.titleMedium)
                        Text(when (share) {
                            "on" -> if (parent) "Your family can see where you are." else "Your family can see where you are. You can pause any time, and they will see that it is paused."
                            "paused" -> "Nobody can see your location right now. Your family can see that it is paused."
                            else -> "Nobody can see where you are."
                        }, Modifier.padding(top = 4.dp), color = Muted)
                        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (share != "on") OutlinedButton({ setSharing("on") }) { Text("Share") }
                            if (share == "on") OutlinedButton({ pause = true }) { Text("Pause…") }
                            if (share != "off") OutlinedButton({ setSharing("off") }) { Text("Turn off") }
                        }
                    }
                    SectionLabel("Tell the family")
                    TwoUp({ BigButton("I'm OK ✓", color = Mint) { sendQuick(ctx, "checkin", "I'm OK", 0, true, "Checked in") } },
                        { BigButton("I'm home 🏠", color = Mint) { sendQuick(ctx, "checkin", "I'm home", 0, true, "Checked in") } })
                    TwoUp({ SoftButton("Running late ⏰") { late = true } }, { SoftButton("Pick me up 🚗") { sendQuick(ctx, "pickup", "Can someone pick me up?", 0, true, "Sent to the family") } })
                    TileGrid(listOfNotNull(
                        TileItem("🙂", me?.status?.let { "${it.emoji} ${it.text}".trim() } ?: "What I'm up to", "Tell the family in one tap") { statusOpen = true },
                        TileItem("😊", "How I feel", "A quick mood check-in") { nav.go(Screen.Mood()) },
                        TileItem("🎂", "My birthday", me?.birthday?.let { birthdayText(it) } ?: "So the family remembers") { birthdayOpen = true },
                        TileItem("🌙", if (quietFrom < 0) "Quiet hours" else "Quiet ${clockText(quietFrom)}–${clockText(quietTo)}", "Calls and messages stay quiet") {
                            TimePickerDialog(ctx, { _, h, m ->
                                val from = h * 60 + m
                                TimePickerDialog(ctx, { _, h2, m2 ->
                                    val to = h2 * 60 + m2
                                    scope.launch { runCatching { app.api.patchMe("quiet_from" to from, "quiet_to" to to) }.onSuccess { quietFrom = from; quietTo = to; app.state.refreshFamily() } }
                                }, 7, 0, false).apply { setTitle("Quiet hours end") }.show()
                            }, 21, 0, false).apply { setTitle("Quiet hours start") }.show()
                        },
                        if (!parent) TileItem("😴", "Bedtime reminder", "A gentle wind-down nudge") { pickBedtime(ctx, app.store.myId) } else null,
                        TileItem("👀", "What my family sees", "Everything that is shared") { nav.go(Screen.Transparency) },
                        if (!parent) TileItem("🎛️", "What parents can do", "You choose, they see it") { nav.go(Screen.Perms) } else null,
                        TileItem("🧰", "Pocket", "Timer and private notes") { nav.go(Screen.Pocket) },
                    ))
                    if (quietFrom >= 0) TextButton({ scope.launch { runCatching { app.api.patchMe("quiet_from" to -1, "quiet_to" to -1) }.onSuccess { quietFrom = -1; quietTo = -1; app.state.refreshFamily() } } }) { Text("Turn quiet hours off") }
                    if (!parent) { SectionLabel("Screen time"); ScreenTimeCard(nav) }
                }
                1 -> TileGrid(listOfNotNull(
                    TileItem("💰", "Pocket money", "Wallet, requests, goals") { nav.go(Screen.Wallet) },
                    TileItem("📸", "Album", "Family photos") { nav.go(Screen.Album) },
                    TileItem("✅", "Jobs", "Points for helping") { nav.go(Screen.Chores) },
                    TileItem("🎁", "Rewards", "What points can buy") { nav.go(Screen.Rewards) },
                    TileItem("⭐", "Kudos and badges", "Thanks and achievements") { nav.go(Screen.Kudos) },
                    TileItem("📚", "Homework", "Due dates and reminders") { nav.go(Screen.Homework) },
                    TileItem("🛒", "Family list", "Shopping and to-dos") { nav.go(Screen.Lists) },
                    TileItem("📌", "Family board", "Wi-Fi, the doctor…") { nav.go(Screen.Board) },
                    TileItem("📍", "Zones", "School, Home, Grandma's") { nav.go(Screen.Places) },
                    TileItem("🕓", "Activity", "What has been happening") { nav.go(Screen.Feed) },
                    TileItem("🎉", "Fun", "Games for the family") { nav.go(Screen.Fun) },
                    TileItem("🎮", "Family game", "Start a game of Alibi") { startFamilyGame(ctx) },
                    if (parent) TileItem("📋", "Rules", "House rules and limits") { nav.go(Screen.Rules) } else null,
                    if (parent) TileItem("＋", "Invite", "Add someone to the family") { nav.go(Screen.Invite) } else null,
                    if (parent) TileItem("🔗", "Platter and Scout", "Link the sister apps") { linkApps = true } else null,
                ))
                2 -> {
                    SosButton { ctx.hb.state.sosAsk.value = true }
                    TileGrid(listOfNotNull(
                        TileItem("🚶", "Safe Walk", "Live until you get there") { walkOpen = true },
                        TileItem("🔗", "Share my trip", "A link that runs out") { tripOpen = true },
                        TileItem("🆘", "Emergency card", "Works with no signal") { nav.go(Screen.Emergency) },
                        TileItem("🧑‍🤝‍🧑", "Trusted adults", "Who to call") { nav.go(Screen.Trusted) },
                        TileItem("🛡️", "Safe browsing", "A family web filter") { nav.go(Screen.SafeBrowsing) },
                        if (parent) TileItem("⚠️", "Safety alerts", "Messages that looked risky") { nav.go(Screen.Safety) } else null,
                        TileItem("📱", "Find my phone", "Make it ring loudly") { scope.launch { runCatching { app.api.ring(app.store.myId) }.onSuccess { toast(ctx, "Your phones will ring") }.onFailure { toast(ctx, it.message ?: "Could not ring") } } },
                    ))
                }
                else -> {
                    SoftCard {
                        Text("Look", style = MaterialTheme.typography.titleMedium)
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Pill("🌙 Dark", Look.dark) { Look.dark = true; app.store.darkLook = true }
                            Pill("☀️ White", !Look.dark) { Look.dark = false; app.store.darkLook = false }
                        }
                    }
                    UpdateCard()
                    TileGrid(listOf(
                        TileItem("⚙️", "Settings", "Name, server, leave") { nav.go(Screen.Settings) },
                        TileItem("🎓", "Tutorial", "How Mellow works") { tutorial = true },
                        TileItem("🤝", "Our promise", "What Mellow will never do") { nav.go(Screen.About) },
                    ))
                }
            }
            Gap(8)
        }
    }
    if (tutorial) TutorialDialog { tutorial = false }
    if (linkApps) LinkAppsDialog { linkApps = false }
    if (tripOpen) TripDialog { tripOpen = false }
    if (walkOpen) WalkDialog { walkOpen = false }
    if (statusOpen) StatusDialog { statusOpen = false }
    if (birthdayOpen) BirthdayDialog(me?.birthday) { birthdayOpen = false }
    if (pause) PickDialog("Pause sharing for…", listOf("15 minutes" to 15, "1 hour" to 60, "3 hours" to 180, "Until tomorrow" to 720), { setSharing("paused", it) }, { pause = false })
    if (late) PickDialog("How late will you be?", LATE_CHOICES, { lateSend(ctx, it) }, { late = false })
}
