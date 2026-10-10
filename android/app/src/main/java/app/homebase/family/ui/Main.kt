package app.homebase.family.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import app.homebase.family.Member
import app.homebase.family.hb

fun memberLine(m: Member): String {
    val where = when {
        m.share == "paused" -> "Location paused"
        m.share == "off" -> "Not sharing location"
        m.location != null -> "Seen ${timeAgo(m.location.at)}"
        else -> "Waiting for a location"
    }
    return if (m.battery in 0..100) "$where · 🔋 ${m.battery}%" else where
}

@Composable
fun MainScreen(nav: Nav, initialTab: Int = 0) {
    var tab by remember { mutableIntStateOf(initialTab) }
    val state = LocalContext.current.hb.state
    LaunchedEffect(Unit) { state.refreshFamily(); state.refreshItems() }
    Scaffold(containerColor = Bg, bottomBar = {
        NavigationBar(containerColor = Color.White) {
            val items = listOf("Map" to Icons.Filled.Place, "Family" to Icons.Filled.People, "Chat" to Icons.Filled.Chat, "Me" to Icons.Filled.Home)
            items.forEachIndexed { i, (label, icon) ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(icon, label) }, label = { Text(label) },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = Blue, selectedTextColor = Blue, indicatorColor = BlueSoft))
            }
        }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> MapTab(nav)
                1 -> FamilyTab(nav)
                2 -> ChatTab(nav)
                else -> MeTab(nav)
            }
        }
    }
}
