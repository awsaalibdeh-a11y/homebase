package app.homebase.family.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.homebase.family.Member
import app.homebase.family.Message
import app.homebase.family.Spot
import app.homebase.family.hb
import coil.compose.AsyncImage
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/** "3:41 PM" today, "Yesterday", the weekday this week, otherwise "12 Oct". */
fun listWhen(epochSeconds: Double): String {
    if (epochSeconds <= 0) return ""
    val t = (epochSeconds * 1000).toLong()
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = t }
    val days = ((now.apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis -
        then.clone().let { (it as Calendar).apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis }) / 86_400_000L).toInt()
    return when {
        days <= 0 -> DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(t))
        days == 1 -> "Yesterday"
        days < 7 -> java.text.SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(t))
        else -> java.text.SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(t))
    }
}

/** What to show for a message in a list: its words, or a short name for a picture, a sticker or a game. */
fun messagePreview(m: Message, me: Int): String {
    val text = when (m.kind) {
        "gif" -> "🎞️ GIF"
        "image" -> "📷 Photo"
        "voice" -> "🎤 Voice note"
        "heart" -> "❤️"
        "game" -> "🎮 " + gameText(m.body)
        "checkin" -> "✓ " + m.body.ifBlank { "I'm OK" }
        "late" -> "⏰ " + m.body
        "sos" -> "🚨 " + m.body
        "pickup" -> "🚗 " + m.body
        else -> m.body
    }
    return (if (m.from == me && m.kind != "game") "You: " else "") + text
}

private fun initials(name: String): String = name.trim().split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }.ifBlank { "?" }

@Composable
private fun RoundPhoto(m: Member?, emoji: String, size: Int) {
    if (m != null && m.avatar != null) MemberAvatar(m, size, Surface2, circle = true)
    else Box(Modifier.size(size.dp).clip(CircleShape).background(Surface2), contentAlignment = Alignment.Center) {
        if (m != null) Text(if (size >= 80) m.emoji else initials(m.name), fontSize = (size * (if (size >= 80) 0.46 else 0.34)).sp, fontWeight = FontWeight.SemiBold, color = Ink)
        else Text(emoji, fontSize = (size * 0.46).sp)
    }
}

private class Convo(val key: String, val name: String, val member: Member?, val emoji: String, val preview: String, val at: Double, val open: () -> Unit)

/** Messages, laid out like a phone's own messages app: the people you talk to most as big circles, then every conversation, newest first. */
@Composable
fun ChatTab(nav: Nav) {
    val ctx = LocalContext.current
    val state = ctx.hb.state
    val fam by state.family.collectAsState()
    val threads by state.threads.collectAsState()
    val groups by state.groups.collectAsState()
    val me = ctx.hb.store.myId
    var query by remember { mutableStateOf("") }
    var compose by remember { mutableStateOf(false) }
    var newGroup by remember { mutableStateOf(false) }
    var joinGroup by remember { mutableStateOf(false) }
    val others = fam?.members.orEmpty().filter { it.id != me }
    LaunchedEffect(fam?.members?.size) {
        state.loadThread(0)
        others.forEach { state.loadThread(it.id) }
        state.refreshGroups()
    }
    val convos = buildList {
        add(Convo("fam", "Whole family", null, "👨‍👩‍👧", threads[0]?.lastOrNull { it.kind != "system" }?.let { messagePreview(it, me) } ?: "", threads[0]?.lastOrNull { it.kind != "system" }?.at ?: 0.0) { nav.go(Screen.Thread(0)) })
        others.forEach { m ->
            val last = threads[m.id]?.lastOrNull { it.kind != "system" }
            add(Convo("m${m.id}", m.name, m, m.emoji, last?.let { messagePreview(it, me) } ?: "", last?.at ?: 0.0) { nav.go(Screen.Thread(m.id)) })
        }
        groups.forEach { g ->
            val pending = g.status == "pending"
            add(Convo("g${g.id}", g.name, null, g.emoji, if (pending) "Waiting for a parent to say yes" else g.last?.let { "${it.name}: ${it.body}" } ?: "Friend group", g.last?.at ?: 0.0) { if (!pending) nav.go(Screen.Group(g.id)) })
        }
    }
    val shown = convos.filter { it.at > 0 || it.key.startsWith("g") }.filter { query.isBlank() || it.name.contains(query, true) || it.preview.contains(query, true) }.sortedByDescending { it.at }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Text("Messages", Modifier.padding(top = 18.dp, bottom = 12.dp), style = MaterialTheme.typography.headlineLarge)
            GroupRequests()
            if (query.isBlank()) {
                val pinned = listOf<Member?>(null) + others
                pinned.chunked(3).forEach { rowItems ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
                        rowItems.forEach { m ->
                            Column(Modifier.weight(1f).clickable { nav.go(Screen.Thread(m?.id ?: 0)) }, horizontalAlignment = Alignment.CenterHorizontally) {
                                RoundPhoto(m, "👨‍👩‍👧", 92)
                                Text(m?.name ?: "Family", Modifier.padding(top = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, color = Muted)
                            }
                        }
                        repeat(3 - rowItems.size) { Box(Modifier.weight(1f)) }
                    }
                }
            }
            shown.forEach { c ->
                Row(Modifier.fillMaxWidth().clickable { c.open() }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RoundPhoto(c.member, c.emoji, 46)
                    Column(Modifier.weight(1f)) {
                        Text(c.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1)
                        Text(c.preview.ifBlank { "Say hello" }, color = Muted, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Text(listWhen(c.at) + "  ›", color = Muted, fontSize = 13.sp)
                }
                Box(Modifier.fillMaxWidth().padding(start = 58.dp).height(1.dp).background(Hairline))
            }
            if (shown.isEmpty()) CenterNote(if (query.isBlank()) "No conversations yet. Tap a person above to say hello." else "Nothing found.")
            Box(Modifier.size(12.dp))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(24.dp), placeholder = { Text("Search") }, leadingIcon = { Icon(Icons.Filled.Search, null) })
            Box(Modifier.size(48.dp).clip(CircleShape).background(Surface2).clickable { compose = true }, contentAlignment = Alignment.Center) { Icon(Icons.Filled.Edit, "New message") }
        }
    }
    if (compose) AlertDialog(
        onDismissRequest = { compose = false },
        containerColor = Surface,
        title = { Text("New message") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                TextButton({ compose = false; nav.go(Screen.Thread(0)) }, Modifier.fillMaxWidth()) { Text("👨‍👩‍👧  Whole family") }
                others.forEach { m -> TextButton({ compose = false; nav.go(Screen.Thread(m.id)) }, Modifier.fillMaxWidth()) { Text("${m.emoji}  ${m.name}") } }
                TextButton({ compose = false; newGroup = true }, Modifier.fillMaxWidth()) { Text("👥  New friend group") }
                TextButton({ compose = false; joinGroup = true }, Modifier.fillMaxWidth()) { Text("🔑  Join a group with a code") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton({ compose = false }) { Text("Cancel") } },
    )
    if (newGroup) NewGroupDialog { newGroup = false }
    if (joinGroup) JoinGroupDialog { joinGroup = false }
}

private val REACTION_SET = listOf("👍", "❤️", "😂", "😮", "🙏", "🎉")
private val QUICK = listOf("On my way 🚶", "Home soon 🏠", "OK 👍", "Call me 📞", "Thank you ❤️")

private fun reactTo(ctx: android.content.Context, id: Int, emoji: String) {
    ctx.hb.scope.launch {
        runCatching { ctx.hb.api.react(id, emoji) }
            .onSuccess { ctx.hb.state.setReactions(id, it) }
            .onFailure { toast(ctx, it.message ?: "Could not react") }
    }
}

@Composable
private fun Bubble(m: Message, mine: Boolean, who: String, me: Int, picking: Boolean, onTap: () -> Unit, onReact: (String) -> Unit, onSpot: (Spot) -> Unit, onPhoto: (String) -> Unit) {
    val ctx = LocalContext.current
    if (m.kind == "system") {
        Text(m.body, Modifier.fillMaxWidth().padding(vertical = 4.dp), textAlign = TextAlign.Center, color = Muted, fontSize = 12.sp)
        return
    }
    val (tint, ink) = when (m.kind) {
        "sos" -> Rose to Color.White
        "checkin" -> MintSoft to Ink
        "late" -> SunSoft to Ink
        "pickup" -> AccentSoft to Ink
        "game" -> AccentSoft to Ink
        else -> if (mine) Accent to OnAccent else Surface2 to Ink
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Column(Modifier.widthIn(max = 290.dp).clip(RoundedCornerShape(20.dp)).background(tint).clickable { onTap() }.padding(horizontal = 14.dp, vertical = 9.dp)) {
            if (!mine && who.isNotBlank()) Text(who, fontSize = 11.sp, color = if (m.kind == "sos") Color.White else Muted)
            when (m.kind) {
                "heart" -> Text("❤️", fontSize = 34.sp)
                "gif" -> GifImage(m.body, Modifier.size(180.dp))
                "voice" -> VoiceBubble(m.body, ink)
                "image" -> AsyncImage(pictureUrl(ctx, m.body), "Photo", Modifier.widthIn(max = 240.dp).size(width = 240.dp, height = 240.dp).clip(RoundedCornerShape(14.dp)).clickable { onPhoto(pictureUrl(ctx, m.body)) }, contentScale = ContentScale.Crop)
                "game" -> {
                    Text("🎮 " + gameText(m.body), color = ink)
                    val join = gameJoinUrl(m.body)
                    if (join != null) TextButton({ joinGame(ctx, join, ctx.hb.state.me?.name ?: ctx.hb.store.myName) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("Join the game", fontWeight = FontWeight.Bold) }
                }
                "checkin" -> Text("✓ ${m.body.ifBlank { "I'm OK" }}", color = ink)
                "late" -> Text("⏰ ${m.body}", color = ink)
                "sos" -> Text("🚨 ${m.body}", color = ink, fontWeight = FontWeight.Bold)
                "pickup" -> Text("🚗 ${m.body}", color = ink, fontWeight = FontWeight.Bold)
                else -> Text(m.body, color = ink)
            }
            if (m.lat != null && m.lng != null) TextButton({ onSpot(Spot(m.lat, m.lng)) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("Show on map", color = if (m.kind == "sos") Color.White else AccentDark) }
            if (m.kind == "pickup" && !mine) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(5, 10, 20, 30).forEach { n ->
                        Text("On my way · $n min", Modifier.clip(RoundedCornerShape(50)).background(Accent).clickable { sendQuick(ctx, "text", "On my way, about $n minutes 🚗", 0, false, "Sent") }.padding(horizontal = 12.dp, vertical = 6.dp),
                            color = OnAccent, fontSize = 13.sp)
                    }
                }
            }
            if (m.reactions.isNotEmpty()) Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                m.reactions.forEach { (e, ids) ->
                    val mineR = me in ids
                    Text("$e ${ids.size}", Modifier.clip(RoundedCornerShape(50)).background(if (mineR) AccentSoft else Color.Black.copy(alpha = 0.18f)).clickable { onReact(e) }.padding(horizontal = 8.dp, vertical = 2.dp),
                        color = if (mineR) AccentDark else ink, fontSize = 13.sp)
                }
            }
        }
        if (picking) Row(Modifier.padding(top = 4.dp).clip(RoundedCornerShape(50)).background(Surface2).padding(horizontal = 6.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            REACTION_SET.forEach { e -> Text(e, Modifier.clip(CircleShape).clickable { onReact(e) }.padding(6.dp), fontSize = 20.sp) }
        }
    }
}

@Composable
fun ThreadScreen(nav: Nav, id: Int) {
    val ctx = LocalContext.current
    val state = ctx.hb.state
    val me = ctx.hb.store.myId
    val fam by state.family.collectAsState()
    val threads by state.threads.collectAsState()
    val list = threads[id].orEmpty()
    val listState = rememberLazyListState()
    var text by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf<Int?>(null) }
    var plus by remember { mutableStateOf(false) }
    var tray by remember { mutableStateOf(false) }
    var big by remember { mutableStateOf<String?>(null) }
    var recording by remember { mutableStateOf(false) }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (!ok) toast(ctx, "Mellow needs the microphone for voice notes") }
    DisposableEffect(Unit) { onDispose { if (recording) Voice.cancel() } }
    val peer = fam?.members?.firstOrNull { it.id == id }
    val call = rememberCaller()
    val videoCall = rememberVideoCaller()
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) ctx.hb.scope.launch { readBitmap(ctx, uri)?.let { sendPhoto(ctx, it, id) } ?: toast(ctx, "That picture could not be read") }
    }
    LaunchedEffect(id) { state.openThread.value = id; state.loadThread(id) }
    DisposableEffect(id) { onDispose { if (state.openThread.value == id) state.openThread.value = null } }
    LaunchedEffect(list.size) { if (list.isNotEmpty()) listState.animateScrollToItem(list.size - 1) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Surface).padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton({ nav.back() }) { Text("‹ Back") }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                RoundPhoto(peer, "👨‍👩‍👧", 40)
                Text(peer?.name ?: "Whole family", fontSize = 12.sp, maxLines = 1)
            }
            if (peer != null) {
                Box(Modifier.size(44.dp).clickable { videoCall(peer) }, contentAlignment = Alignment.Center) { Icon(Icons.Filled.Videocam, "Video call") }
                Box(Modifier.size(44.dp).clickable { call(peer, false) }, contentAlignment = Alignment.Center) { Icon(Icons.Filled.Call, "Call") }
            } else Box(Modifier.size(44.dp).clickable { startFamilyGame(ctx) }, contentAlignment = Alignment.Center) { Text("🎮", fontSize = 22.sp) }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = listState) {
            items(list, key = { it.id }) { m ->
                Bubble(m, m.from == me, fam?.members?.firstOrNull { it.id == m.from }?.name ?: "", me, picking == m.id,
                    onTap = { picking = if (picking == m.id) null else m.id },
                    onReact = { e -> reactTo(ctx, m.id, e); picking = null },
                    onSpot = { s -> state.showOnMap.tryEmit(s); nav.back() },
                    onPhoto = { big = it })
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QUICK.forEach { q -> Text(q, Modifier.clip(RoundedCornerShape(50)).background(Surface2).clickable { sendQuick(ctx, "text", q, id, done = "Sent") }.padding(horizontal = 14.dp, vertical = 8.dp), fontSize = 14.sp) }
        }
        if (tray) StickerTray { pick -> tray = false; sendQuick(ctx, "gif", pick, id, done = "Sent") }
        Row(Modifier.fillMaxWidth().background(Surface).padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                Box(Modifier.size(40.dp).clip(CircleShape).background(Surface2).clickable { plus = true }, contentAlignment = Alignment.Center) { Icon(Icons.Filled.Add, "More") }
                DropdownMenu(plus, { plus = false }) {
                    DropdownMenuItem({ Text("📷  Take a photo") }, { plus = false; nav.go(Screen.Camera("chat:$id")) })
                    DropdownMenuItem({ Text("🖼️  Choose a photo") }, { plus = false; gallery.launch("image/*") })
                    DropdownMenuItem({ Text("🎞️  Sticker or GIF") }, { plus = false; tray = !tray })
                    DropdownMenuItem({ Text("❤️  Send a heart") }, { plus = false; sendQuick(ctx, "heart", "", id, done = "❤️ sent") })
                    if (id == 0) DropdownMenuItem({ Text("🎮  Start a family game") }, { plus = false; startFamilyGame(ctx) })
                }
            }
            OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("Message") }, maxLines = 4, shape = RoundedCornerShape(24.dp))
            if (text.isNotBlank()) Box(Modifier.size(40.dp).clip(CircleShape).background(Accent).clickable { sendQuick(ctx, "text", text.trim(), id, done = "Sent"); text = "" }, contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.ArrowUpward, "Send", tint = OnAccent)
            } else Box(Modifier.size(40.dp).clip(CircleShape).background(if (recording) Rose else Surface2).clickable {
                if (recording) { recording = false; Voice.stop()?.let { sendVoice(ctx, it, id) } ?: toast(ctx, "Too short. Tap, talk, then tap again.") }
                else if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) askMic.launch(android.Manifest.permission.RECORD_AUDIO)
                else { recording = Voice.start(ctx); if (recording) toast(ctx, "Recording… tap again to send") else toast(ctx, "Could not use the microphone") }
            }, contentAlignment = Alignment.Center) {
                Text(if (recording) "■" else "🎤", color = if (recording) androidx.compose.ui.graphics.Color.White else Ink, fontSize = 18.sp)
            }
        }
    }
    big?.let { url ->
        Dialog({ big = null }) {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.Black).clickable { big = null }) {
                AsyncImage(url, "Photo", Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
            }
        }
    }
}
