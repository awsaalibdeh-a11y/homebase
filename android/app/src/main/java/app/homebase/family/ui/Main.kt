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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.platform.LocalLifecycleOwner
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import app.homebase.family.Connectivity
import app.homebase.family.Member
import app.homebase.family.hb

fun memberLine(m: Member): String {
    val where = when {
        m.share == "paused" -> "Location paused"
        m.share == "off" -> "Not sharing location"
        m.location != null -> (if (Connectivity.online && System.currentTimeMillis() / 1000.0 - m.lastSeen < 150) "Live · " else "Last known · ") + timeAgo(m.location.at)
        else -> "Waiting for a location"
    }
    val line = if (m.battery in 0..100) "$where · 🔋 ${m.battery}%" else where
    val off = PERM_LABELS.filter { (key, _) -> !m.allows(key) }.map { it.second }
    return if (off.isEmpty()) line else "$line · off: ${off.joinToString(", ")}"
}

/** Plain names for the things a person can switch off for their parents. */
val PERM_LABELS = listOf("location" to "location", "trail" to "trail", "battery" to "battery", "messages" to "messages", "calls" to "calls", "ping" to "where-are-you")

@Composable
fun MainScreen(nav: Nav, initialTab: Int = 0) {
    var tab by remember { mutableIntStateOf(initialTab) }
    val state = LocalContext.current.hb.state
    val owner = LocalLifecycleOwner.current
    // Keep the map fresh while the screen is visible; nothing runs in the background.
    LaunchedEffect(owner) {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            state.refreshItems()
            while (true) { state.refreshFamily(); delay(10_000) }
        }
    }
    Scaffold(containerColor = Bg, bottomBar = {
        NavigationBar(containerColor = Surface) {
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
            val online by state.online.collectAsState()
            if (!online) Text("📡 Offline · showing the last update", color = Color.White, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp).background(Color(0xFF1C2230), RoundedCornerShape(99.dp)).padding(horizontal = 16.dp, vertical = 8.dp))
        }
    }
}
