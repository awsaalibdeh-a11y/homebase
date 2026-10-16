package app.homebase.family.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.homebase.family.Message
import app.homebase.family.Spot
import app.homebase.family.hb
import kotlinx.coroutines.launch

@Composable
fun ChatTab(nav: Nav) {
    val ctx = LocalContext.current
    val state = ctx.hb.state
    val fam by state.family.collectAsState()
    val threads by state.threads.collectAsState()
    val me = ctx.hb.store.myId
    LaunchedEffect(fam?.members?.size) {
        state.loadThread(0)
        fam?.members.orEmpty().filter { it.id != me }.forEach { state.loadThread(it.id) }
    }
    fun preview(id: Int): String = threads[id]?.lastOrNull { it.kind != "system" }?.let { (if (it.from == me) "You: " else "") + (it.body.ifBlank { if (it.kind == "heart") "❤️" else "" }) } ?: "Say hello"
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Heading("Chat")
        SoftCard(onClick = { nav.go(Screen.Thread(0)) }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Avatar("👨‍👩‍👧", 48, SunSoft)
                Column { Text("Whole family", style = MaterialTheme.typography.titleMedium); Text(preview(0), color = Muted, maxLines = 1) }
            }
        }
        fam?.members.orEmpty().filter { it.id != me }.forEach { m ->
            SoftCard(onClick = { nav.go(Screen.Thread(m.id)) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Avatar(m.emoji, 48)
                    Column { Text(m.name, style = MaterialTheme.typography.titleMedium); Text(preview(m.id), color = Muted, maxLines = 1) }
                }
            }
        }
    }
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
private fun Bubble(m: Message, mine: Boolean, who: String, me: Int, picking: Boolean, onTap: () -> Unit, onReact: (String) -> Unit, onSpot: (Spot) -> Unit) {
    if (m.kind == "system") {
        Text(m.body, Modifier.fillMaxWidth().padding(vertical = 4.dp), textAlign = TextAlign.Center, color = Muted, fontSize = 12.sp)
        return
    }
    val (tint, ink) = when (m.kind) {
        "sos" -> Rose to OnAccent
        "checkin" -> MintSoft to Ink
        "late" -> SunSoft to Ink
        "pickup" -> AccentSoft to Ink
        else -> if (mine) Accent to OnAccent else Surface2 to Ink
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Column(Modifier.widthIn(max = 290.dp).clip(RoundedCornerShape(18.dp)).background(tint).clickable { onTap() }.padding(horizontal = 14.dp, vertical = 9.dp)) {
            if (!mine) Text(who, fontSize = 11.sp, color = if (m.kind == "sos") OnAccent else Muted)
            when (m.kind) {
                "heart" -> Text("❤️", fontSize = 34.sp)
                "checkin" -> Text("✓ ${m.body.ifBlank { "I'm OK" }}", color = ink)
                "late" -> Text("⏰ ${m.body}", color = ink)
                "sos" -> Text("🚨 ${m.body}", color = ink, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                "pickup" -> Text("🚗 ${m.body}", color = ink, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                else -> Text(m.body, color = ink)
            }
            if (m.lat != null && m.lng != null) TextButton({ onSpot(Spot(m.lat, m.lng)) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("Show on map", color = if (m.kind == "sos") Color.White else Accent) }
            if (m.kind == "pickup" && !mine) {
                val ctx = LocalContext.current
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
    val peer = fam?.members?.firstOrNull { it.id == id }
    val call = rememberCaller()
    LaunchedEffect(id) { state.openThread.value = id; state.loadThread(id) }
    androidx.compose.runtime.DisposableEffect(id) { onDispose { if (state.openThread.value == id) state.openThread.value = null } }
    LaunchedEffect(list.size) { if (list.isNotEmpty()) listState.animateScrollToItem(list.size - 1) }
    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(Modifier.fillMaxWidth().background(Surface).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton({ nav.back() }) { Text("Back") }
            Avatar(peer?.emoji ?: "👨‍👩‍👧", 36, if (peer == null) SunSoft else AccentSoft)
            Text(peer?.name ?: "Whole family", Modifier.padding(start = 10.dp).weight(1f), style = MaterialTheme.typography.titleMedium)
            if (peer != null) TextButton({ call(peer, false) }) { Text("Call") }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = listState) {
            items(list, key = { it.id }) { m ->
                Bubble(m, m.from == me, fam?.members?.firstOrNull { it.id == m.from }?.name ?: "", me, picking == m.id,
                    onTap = { picking = if (picking == m.id) null else m.id },
                    onReact = { e -> reactTo(ctx, m.id, e); picking = null },
                    onSpot = { s -> state.showOnMap.tryEmit(s); nav.back() })
            }
        }
        Row(Modifier.fillMaxWidth().background(Surface).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QUICK.forEach { q -> Text(q, Modifier.clip(RoundedCornerShape(50)).background(Surface2).clickable { sendQuick(ctx, "text", q, id, done = "Sent") }.padding(horizontal = 14.dp, vertical = 8.dp), fontSize = 14.sp) }
        }
        Row(Modifier.fillMaxWidth().background(Surface).padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton({ sendQuick(ctx, "heart", "", id, done = "❤️ sent") }) { Text("❤️", fontSize = 22.sp) }
            OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("Message") }, maxLines = 4, shape = RoundedCornerShape(24.dp))
            TextButton({ if (text.isNotBlank()) { sendQuick(ctx, "text", text.trim(), id, done = "Sent"); text = "" } }) { Text("Send") }
        }
    }
}
