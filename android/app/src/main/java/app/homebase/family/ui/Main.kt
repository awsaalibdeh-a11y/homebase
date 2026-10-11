package app.homebase.family.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
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
        m.lock != null -> "🔒 Locked until ${clockOf(m.lock.until)}"
        m.home -> "🏠 At home"
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
val PERM_LABELS = listOf("location" to "location", "trail" to "trail", "battery" to "battery", "messages" to "messages", "calls" to "calls", "ping" to "where-are-you", "screentime" to "screen time")

@Composable
fun MainScreen(nav: Nav, initialTab: Int = 0) {
    var tab by remember { mutableIntStateOf(initialTab) }
    val state = LocalContext.current.hb.state
    val owner = LocalLifecycleOwner.current
    val ctxU = LocalContext.current
    var newApk by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { runCatching { ctxU.hb.api.config() }.onSuccess { c -> if (c.versionCode > app.homebase.family.BuildConfig.VERSION_CODE) newApk = c.apkUrl } }
    newApk?.let { url ->
        androidx.compose.material3.AlertDialog(onDismissRequest = { newApk = null }, title = { Text("A new Homebase is ready") },
            text = { Text("Tap Update, then open the file when it finishes downloading. Your family, places and settings stay as they are.") },
            confirmButton = { TextButton({ ctxU.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); newApk = null }) { Text("Update") } },
            dismissButton = { TextButton({ newApk = null }) { Text("Later") } })
    }
    // Keep the map fresh while the screen is visible; nothing runs in the background.
    LaunchedEffect(owner) {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            state.refreshItems()
            while (true) { state.refreshFamily(); delay(10_000) }
        }
    }
    Scaffold(containerColor = Bg, bottomBar = {
        NavigationBar(containerColor = Surface) {
            val items = listOf("Family" to Icons.Filled.People, "Me" to Icons.Filled.Home)
            items.forEachIndexed { i, (label, icon) ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(icon, label) }, label = { Text(label) },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = Blue, selectedTextColor = Blue, indicatorColor = BlueSoft))
            }
        }
    }) { pad ->
        val famNow by state.family.collectAsState()
        val myLock = famNow?.members?.firstOrNull { it.id == LocalContext.current.hb.store.myId }?.lock
        var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
        LaunchedEffect(myLock) { while (myLock != null) { nowMs = System.currentTimeMillis(); kotlinx.coroutines.delay(2000) } }
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (myLock != null && myLock.until * 1000 > nowMs) { LockedScreen(myLock); return@Box }
            val isParent = LocalContext.current.hb.store.isParent
            if (tab == 0) FamilyTab(nav) else MeTab(nav)
            val online by state.online.collectAsState()
            if (!online) Text("📡 Offline · showing the last update", color = Color.White, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp).background(Color(0xFF1C2230), RoundedCornerShape(99.dp)).padding(horizontal = 16.dp, vertical = 8.dp))
        }
    }
}

/** A full-screen page opened from the Family tab, with a Back button on top. */
@Composable
fun WithBack(nav: Nav, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TextButton({ nav.back() }, Modifier.padding(start = 8.dp, top = 8.dp)) { Text("Back") }
        Box(Modifier.weight(1f)) { content() }
    }
}
