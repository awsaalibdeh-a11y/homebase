package app.homebase.family.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.homebase.family.HistoryPoint
import app.homebase.family.hb
import kotlinx.coroutines.launch

@Composable
fun PlacesScreen(nav: Nav) {
    val ctx = LocalContext.current
    val fam by ctx.hb.state.family.collectAsState()
    val scope = ctx.hb.scope
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { TextButton({ nav.back() }) { Text("Back") } }
        Heading("Zones", "Places like School, Home or Grandma's. Everyone is told when someone arrives or leaves, including the person it is about." + (if (ctx.hb.store.isParent) "" else " You can ask for one; a parent says yes."))
        fam?.places.orEmpty().forEach { p ->
            SoftCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Avatar(p.emoji, 44, MintSoft)
                    Column(Modifier.weight(1f)) { Text(p.name, style = MaterialTheme.typography.titleMedium); Text("${p.radius} m around" + if (p.pending) " · waiting for a parent" else "", color = Muted) }
                    if (ctx.hb.store.isParent && p.pending) {
                        TextButton({ scope.launch { runCatching { ctx.hb.api.approvePlace(p.id, true) }.onSuccess { ctx.hb.state.refreshFamily() } } }) { Text("Yes") }
                        TextButton({ scope.launch { runCatching { ctx.hb.api.approvePlace(p.id, false) }.onSuccess { ctx.hb.state.refreshFamily() } } }) { Text("Not now") }
                    } else if (ctx.hb.store.isParent) TextButton({ scope.launch { runCatching { ctx.hb.api.removePlace(p.id) }.onSuccess { ctx.hb.state.refreshFamily() } } }) { Text("Remove") }
                }
            }
        }
        if (fam?.places.isNullOrEmpty()) CenterNote("No places yet.")
        if (ctx.hb.store.isParent) {
            val hasHome = fam?.places.orEmpty().any { it.name == "Home" }
            BigButton(if (hasHome) "🏠 Move the Home area to where I am" else "🏠 Set the Home area where I am") {
                val st = ctx.hb.store
                if (st.lastSentAt == 0L) toast(ctx, "Turn on your location sharing first, so Mellow knows where you are.")
                else scope.launch { runCatching { ctx.hb.api.setHome(st.lastLat.toDouble(), st.lastLng.toDouble(), 200) }.onSuccess { ctx.hb.state.refreshFamily(); toast(ctx, "Home area saved") }.onFailure { toast(ctx, it.message ?: "Could not save it") } }
            }
            SoftButton("Add another zone") { nav.go(Screen.AddPlace) }
        } else SoftButton("Ask for a zone") { nav.go(Screen.AddPlace) }
    }
}

@Composable
fun AddPlaceScreen(nav: Nav) {
    val ctx = LocalContext.current
    val fam by ctx.hb.state.family.collectAsState()
    var picked by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var name by remember { mutableStateOf("") }
    var emoji by remember { mutableStateOf("🏫") }
    var radius by remember { mutableIntStateOf(150) }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) { TextButton({ nav.back() }) { Text("Back") }; Text("Tap the map to choose the spot", color = Muted) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            OsmMap(Modifier.fillMaxSize(), fam?.members.orEmpty(), ctx.hb.store.myId, fam?.places.orEmpty(), pick = { la, lo -> picked = la to lo }, picked = picked)
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Name, like School or Grandma's") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            EmojiPicker(listOf("🏫", "🏠", "👵", "⚽", "🎵", "🏊", "📚", "🛒", "📍"), emoji) { emoji = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(100, 150, 300, 500).forEach { r -> TextButton({ radius = r }) { Text(if (radius == r) "● $r m" else "$r m") } }
            }
            BigButton(if (busy) "Saving…" else "Save place", enabled = picked != null && name.isNotBlank() && !busy) {
                busy = true
                ctx.hb.scope.launch {
                    runCatching { ctx.hb.api.addPlace(name, emoji, picked!!.first, picked!!.second, radius) }
                        .onSuccess { ctx.hb.state.refreshFamily(); if (!ctx.hb.store.isParent) toast(ctx, "Asked. A parent will say yes or not now."); nav.back() }
                        .onFailure { toast(ctx, it.message ?: "Could not save"); busy = false }
                }
            }
        }
    }
}

@Composable
fun HistoryScreen(nav: Nav, memberId: Int) {
    val ctx = LocalContext.current
    val fam by ctx.hb.state.family.collectAsState()
    var hours by remember { mutableIntStateOf(12) }
    var points by remember { mutableStateOf<List<HistoryPoint>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    val m = fam?.members?.firstOrNull { it.id == memberId }
    LaunchedEffect(hours) {
        loaded = false
        runCatching { ctx.hb.api.history(memberId, hours) }.onSuccess { points = it.points }
        loaded = true
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton({ nav.back() }) { Text("Back") }
            Text("${m?.name ?: "Trail"}'s last $hours hours", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            listOf(3, 12, 24).forEach { h -> TextButton({ hours = h }) { Text(if (hours == h) "● ${h}h" else "${h}h") } }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            OsmMap(Modifier.fillMaxSize(), listOfNotNull(m), ctx.hb.store.myId, fam?.places.orEmpty(), trail = points)
        }
        Text(if (!loaded) "Loading…" else if (points.isEmpty()) "No locations shared in this time." else "${points.size} points. Locations are kept for 7 days, then deleted.", Modifier.padding(16.dp).height(24.dp), color = Muted)
    }
}
