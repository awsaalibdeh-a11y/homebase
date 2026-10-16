package app.homebase.family.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.homebase.family.Spot
import app.homebase.family.hb

/** The map on top, and under it everyone in the family with the buttons to message, call and ask where they are. */
@Composable
fun MapTab(nav: Nav) {
    val ctx = LocalContext.current
    val state = ctx.hb.state
    val fam by state.family.collectAsState()
    var focus by remember { mutableStateOf<Spot?>(null) }
    LaunchedEffect(Unit) { state.showOnMap.collect { focus = it } }
    val me = ctx.hb.store.myId
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(0.44f).fillMaxWidth()) {
            OsmMap(Modifier.fillMaxSize(), fam?.members.orEmpty(), me, fam?.places.orEmpty(), focus = focus)
        }
        Column(Modifier.weight(0.56f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            fam?.members.orEmpty().forEach { m -> PersonCard(nav, m) { focus = it } }
            if (ctx.hb.store.isParent) BigButton("Invite someone to the family") { nav.go(Screen.Invite) }
            SoftButton("Saved places") { nav.go(Screen.Places) }
            Gap(8)
        }
    }
}
