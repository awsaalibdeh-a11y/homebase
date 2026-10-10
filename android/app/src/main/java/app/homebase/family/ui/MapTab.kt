package app.homebase.family.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.homebase.family.Spot
import app.homebase.family.hb

@Composable
fun MapTab(nav: Nav) {
    val ctx = LocalContext.current
    val state = ctx.hb.state
    val fam by state.family.collectAsState()
    var focus by remember { mutableStateOf<Spot?>(null) }
    LaunchedEffect(Unit) { state.showOnMap.collect { focus = it } }
    val call = rememberCaller()
    val me = ctx.hb.store.myId
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            OsmMap(Modifier.fillMaxSize(), fam?.members.orEmpty(), me, fam?.places.orEmpty(), focus = focus)
        }
        LazyRow(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(fam?.members.orEmpty()) { m ->
                SoftCard(Modifier.width(260.dp), onClick = { m.location?.let { focus = it } }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Avatar(m.emoji, 40, if (m.isParent) SunSoft else BlueSoft)
                        Column {
                            Text(if (m.id == me) "${m.name} (you)" else m.name, style = MaterialTheme.typography.titleMedium)
                            Text(memberLine(m), style = MaterialTheme.typography.bodyMedium, color = Muted)
                        }
                    }
                    if (m.id != me) {
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            TextButton({ nav.go(Screen.Thread(m.id)) }) { Text("Message") }
                            TextButton({ call(m, false) }) { Text("Call") }
                            TextButton({ askWhere(ctx, m) }) { Text("Where?") }
                        }
                    }
                }
            }
        }
    }
}
