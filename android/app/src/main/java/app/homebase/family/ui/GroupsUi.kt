package app.homebase.family.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import app.homebase.family.FriendGroup
import app.homebase.family.GroupHead
import app.homebase.family.GroupMessage
import app.homebase.family.hb
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val GROUP_EMOJI = listOf("👥", "⚽", "🎮", "🎨", "🎵", "📚", "🏊", "🚲")
private const val GROUP_RULES = "Your parents can read this group. Rude words, insults and slurs are hidden and reported to them."

/** A parent's yes or not-now for a child who wants to join a friend group. */
@Composable
fun GroupRequests() {
    val ctx = LocalContext.current
    val asks by ctx.hb.state.groupAsks.collectAsState()
    if (!ctx.hb.store.isParent) return
    asks.forEach { a ->
        SoftCard(tint = AccentSoft) {
            Text("👥 ${a.name} wants to join ${a.groupName}", style = MaterialTheme.typography.titleMedium)
            Text("A friend group. You will be able to read it.", color = Muted, style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Not now", false) { answerGroup(ctx, a.group, a.member, false) }
                Pill("Yes", true) { answerGroup(ctx, a.group, a.member, true) }
            }
        }
    }
}

private fun answerGroup(ctx: android.content.Context, gid: Int, member: Int, ok: Boolean) {
    ctx.hb.scope.launch {
        runCatching { ctx.hb.api.approveGroup(gid, member, ok) }
            .onSuccess { ctx.hb.state.refreshGroups() }
            .onFailure { toast(ctx, it.message ?: "Could not save that") }
    }
}

/** The friend groups list on the Chat tab, with Join and New. */
@Composable
fun GroupsSection(nav: Nav) {
    val ctx = LocalContext.current
    val groups by ctx.hb.state.groups.collectAsState()
    var newGroup by remember { mutableStateOf(false) }
    var joinGroup by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { ctx.hb.state.refreshGroups() }
    SectionLabel("👥 Friend groups")
    Text("Groups of friends, separate from the family. " + (if (ctx.hb.store.isParent) "You can read the groups your children are in." else "Your parents can read your groups, and rude words are hidden and reported."), color = Muted, style = MaterialTheme.typography.bodyMedium)
    groups.forEach { g ->
        val pending = g.status == "pending"
        SoftCard(onClick = { if (!pending) nav.go(Screen.Group(g.id)) }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Avatar(g.emoji, 48, AccentSoft)
                Column(Modifier.weight(1f)) {
                    Text(g.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        g.last?.let { "${it.name}: ${it.body}" } ?: if (pending) "A parent has to say yes first." else "No messages yet.",
                        color = Muted, maxLines = 1, style = MaterialTheme.typography.bodyMedium,
                    )
                    if (pending) Chip("Waiting for a parent", SunSoft, Ink)
                    else if (g.status == null) Chip("👀 ${g.overseeing.joinToString(", ")} is in it")
                }
            }
        }
    }
    if (groups.isEmpty()) CenterNote("No friend groups yet. Make one, or join a friend's with their code.")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SoftButton("Join with a code", Modifier.weight(1f)) { joinGroup = true }
        SoftButton("＋ New group", Modifier.weight(1f)) { newGroup = true }
    }
    if (newGroup) NewGroupDialog { newGroup = false }
    if (joinGroup) JoinGroupDialog { joinGroup = false }
}

@Composable
fun NewGroupDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf("") }
    var emoji by remember { mutableStateOf("👥") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text("New friend group") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (ctx.hb.store.isParent) "Friends join with a code." else "A parent will be asked to say yes first, and your parents can read the group. Rude words are hidden and reported.", color = Muted, fontSize = 13.sp)
                OutlinedTextField(name, { name = it.take(40) }, Modifier.fillMaxWidth(), placeholder = { Text("For example: Football mates") }, singleLine = true)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GROUP_EMOJI.forEach { e -> Text(e, Modifier.clip(RoundedCornerShape(12.dp)).background(if (e == emoji) AccentSoft else Surface2).clickable { emoji = e }.padding(8.dp), fontSize = 22.sp) }
                }
            }
        },
        confirmButton = {
            TextButton({
                if (name.isBlank()) toast(ctx, "Give the group a name")
                else ctx.hb.scope.launch {
                    runCatching { ctx.hb.api.makeGroup(name.trim(), emoji) }
                        .onSuccess { r ->
                            ctx.hb.state.refreshGroups()
                            Toast.makeText(ctx, if (r.status == "pending") "Made. A parent has to say yes before you can use it." else "Group made. Share its code with friends.", Toast.LENGTH_LONG).show()
                            onDismiss()
                        }
                        .onFailure { toast(ctx, it.message ?: "Could not make it") }
                }
            }) { Text("Make it") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun JoinGroupDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text("Join a friend group") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Type the code a friend gave you." + (if (ctx.hb.store.isParent) "" else " A parent will be asked to say yes first."), color = Muted, fontSize = 13.sp)
                OutlinedTextField(code, { code = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(12) }, Modifier.fillMaxWidth(), placeholder = { Text("ABCD1234") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton({
                if (code.length < 4) toast(ctx, "Type the whole code")
                else ctx.hb.scope.launch {
                    runCatching { ctx.hb.api.joinGroup(code) }
                        .onSuccess { r ->
                            ctx.hb.state.refreshGroups()
                            Toast.makeText(ctx, if (r.status == "pending") "Asked. A parent has to say yes before you can join ${r.name}." else "You are in ${r.name}", Toast.LENGTH_LONG).show()
                            onDismiss()
                        }
                        .onFailure { toast(ctx, it.message ?: "Could not join") }
                }
            }) { Text("Join") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun GroupBubble(m: GroupMessage, mine: Boolean) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Column(
            Modifier.widthIn(max = 290.dp).clip(RoundedCornerShape(16.dp))
                .background(if (m.flagged) SunSoft else if (mine) Accent else Surface2).padding(horizontal = 14.dp, vertical = 9.dp),
        ) {
            val ink = if (mine && !m.flagged) OnAccent else Ink
            if (!mine) Text(m.name, fontSize = 11.sp, color = Muted)
            if (m.kind == "gif") GifImage(m.body, Modifier.size(170.dp)) else Text(m.body, color = ink)
            if (m.flagged) {
                val why = if (m.flags.isNotEmpty()) "Contained: " + m.flags.joinToString(", ") { "${it.word} (${it.cat})" } + (if (m.original.isNotBlank()) ". ${m.name} typed: “${m.original}”" else "")
                else "A rude word was hidden and reported."
                Text("⚠️ $why", Modifier.padding(top = 4.dp), color = Ink, fontSize = 12.sp)
            }
        }
    }
}

/** One friend group: messages (checked every few seconds while it is open), stickers and GIFs, the code, and who is in it. */
@Composable
fun GroupScreen(nav: Nav, gid: Int) {
    val ctx = LocalContext.current
    val app = ctx.hb
    val me = app.store.myId
    val groups by app.state.groups.collectAsState()
    val msgs = remember { mutableStateListOf<GroupMessage>() }
    var role by remember { mutableStateOf("") }
    var head by remember { mutableStateOf<GroupHead?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    var tray by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    suspend fun load(full: Boolean) {
        val after = if (full) 0 else (msgs.maxOfOrNull { it.id } ?: 0)
        runCatching { app.api.groupMessages(gid, after) }
            .onSuccess { r ->
                role = r.role
                head = r.group
                if (full) { msgs.clear(); msgs.addAll(r.messages) } else r.messages.forEach { n -> if (msgs.none { it.id == n.id }) msgs.add(n) }
                failed = null
            }
            .onFailure { if (full) failed = it.message ?: "Could not open this group" }
    }
    LaunchedEffect(gid) { load(true); while (true) { delay(4000); load(false) } }
    DisposableEffect(gid) { app.state.openGroup.value = gid; onDispose { if (app.state.openGroup.value == gid) app.state.openGroup.value = null } }
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.size - 1) }

    fun send(kind: String, body: String) {
        app.scope.launch {
            runCatching { app.api.sendGroup(gid, kind, body) }
                .onSuccess { r -> r.note?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() }; load(false) }
                .onFailure { Toast.makeText(ctx, it.message ?: "Could not send", Toast.LENGTH_LONG).show() }
        }
    }

    val canWrite = role == "member"
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Surface).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton({ nav.back() }) { Text("Back") }
            Avatar(head?.emoji ?: "👥", 36, AccentSoft)
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                Text(head?.name ?: "Friend group", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(head?.members?.joinToString(", ") { it.name } ?: "", color = Muted, fontSize = 12.sp, maxLines = 1)
            }
            TextButton({ info = true }) { Text("Info") }
        }
        Text(
            if (role == "overseer") "👀 You are reading this as a parent. Only its members can write in it." else "👀 $GROUP_RULES",
            Modifier.fillMaxWidth().background(AccentSoft).padding(horizontal = 14.dp, vertical = 8.dp), fontSize = 12.sp, color = Ink,
        )
        failed?.let { Text(it, Modifier.fillMaxWidth().padding(24.dp), color = Rose, textAlign = TextAlign.Center) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = listState) {
            items(msgs, key = { it.id }) { m -> GroupBubble(m, m.from == me) }
            if (msgs.isEmpty() && failed == null) item { CenterNote("No messages yet. Say hello 👋") }
        }
        if (tray && canWrite) StickerTray { pick -> tray = false; send("gif", pick) }
        Row(Modifier.fillMaxWidth().background(Surface).padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton({ tray = !tray }, enabled = canWrite) { Text("🎞️", fontSize = 22.sp) }
            OutlinedTextField(text, { text = it }, Modifier.weight(1f), enabled = canWrite, placeholder = { Text(if (canWrite) "Write to the group" else "Only members can write") }, maxLines = 4, shape = RoundedCornerShape(14.dp))
            TextButton({ if (text.isNotBlank()) { send("text", text.trim()); text = "" } }, enabled = canWrite) { Text("Send") }
        }
    }
    if (info) GroupInfoDialog(nav, gid, head, role, groups.firstOrNull { it.id == gid }) { info = false }
}

@Composable
private fun GroupInfoDialog(nav: Nav, gid: Int, head: GroupHead?, role: String, mine: FriendGroup?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.hb
    var confirmLeave by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text("${head?.emoji ?: "👥"} ${head?.name ?: "Group"}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(GROUP_RULES, color = Muted, fontSize = 13.sp)
                mine?.code?.let { code ->
                    Text("Invite code", style = MaterialTheme.typography.titleMedium)
                    Text(code, style = MaterialTheme.typography.headlineMedium)
                    Text("Friends type this in Join. A child needs a parent to say yes before they can read or write.", color = Muted, fontSize = 13.sp)
                }
                Text("Members", style = MaterialTheme.typography.titleMedium)
                head?.members.orEmpty().forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${p.emoji}  ${p.name}", Modifier.weight(1f))
                        if (app.store.isParent && p.role == "child") TextButton({
                            app.scope.launch {
                                runCatching { app.api.removeFromGroup(gid, p.id) }
                                    .onSuccess { toast(ctx, "Taken out"); app.state.refreshGroups(); onDismiss() }
                                    .onFailure { toast(ctx, it.message ?: "Could not do that") }
                            }
                        }) { Text("Take out") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Close") } },
        dismissButton = { if (role == "member") TextButton({ confirmLeave = true }) { Text("Leave the group", color = Rose) } },
    )
    if (confirmLeave) AlertDialog(
        onDismissRequest = { confirmLeave = false },
        containerColor = Surface,
        title = { Text("Leave this group?") },
        text = { Text("You will not see its messages any more.") },
        confirmButton = {
            TextButton({
                confirmLeave = false
                app.scope.launch {
                    runCatching { app.api.leaveGroup(gid) }
                        .onSuccess { app.state.refreshGroups(); onDismiss(); nav.back() }
                        .onFailure { toast(ctx, it.message ?: "Could not leave") }
                }
            }) { Text("Leave", color = Rose) }
        },
        dismissButton = { TextButton({ confirmLeave = false }) { Text("Stay") } },
    )
}
