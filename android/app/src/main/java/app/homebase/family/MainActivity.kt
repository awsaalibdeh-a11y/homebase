package app.homebase.family

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.homebase.family.ui.AboutScreen
import app.homebase.family.ui.AddPlaceScreen
import app.homebase.family.ui.CallOverlay
import app.homebase.family.ui.ConsentScreen
import app.homebase.family.ui.CreateScreen
import app.homebase.family.ui.HistoryScreen
import app.homebase.family.ui.HomebaseTheme
import app.homebase.family.ui.InviteScreen
import app.homebase.family.ui.JoinScreen
import app.homebase.family.ui.ListsScreen
import app.homebase.family.ui.MainScreen
import app.homebase.family.ui.Nav
import app.homebase.family.ui.PermissionsScreen
import app.homebase.family.ui.PlacesScreen
import app.homebase.family.ui.Screen
import app.homebase.family.ui.SettingsScreen
import app.homebase.family.ui.ThreadScreen
import app.homebase.family.ui.TransparencyScreen
import app.homebase.family.ui.WelcomeScreen
import app.homebase.family.ui.startHomebase
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val open = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = hb.store
        val start = if (!store.signedIn) Screen.Welcome else if (!store.onboarded) Screen.Permissions else if (!store.tourDone) Screen.Tour else Screen.Main
        val nav = Nav(start)
        if (store.signedIn) startHomebase(this)
        open.value = intent?.getStringExtra("open")
        setContent { HomebaseTheme { Root(nav, open) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        open.value = intent.getStringExtra("open")
    }
}

@Composable
private fun Root(nav: Nav, open: androidx.compose.runtime.MutableState<String?>) {
    val ctx = LocalContext.current
    val state = ctx.hb.state
    val call by state.call.collectAsState()
    val ping by state.pingAsk.collectAsState()
    BackHandler(enabled = nav.stack.size > 1) { nav.back() }
    LaunchedEffect(open.value) {
        val o = open.value ?: return@LaunchedEffect
        if (o.startsWith("chat:") && ctx.hb.store.signedIn) { nav.reset(Screen.Main); nav.go(Screen.Thread(o.removePrefix("chat:").toIntOrNull() ?: 0)) }
        else if (o.startsWith("group:") && ctx.hb.store.signedIn) { nav.reset(Screen.Main); state.tab.value = 3; nav.go(Screen.Group(o.removePrefix("group:").toIntOrNull() ?: 0)) }
        else if (o == "map" && ctx.hb.store.signedIn) { nav.reset(Screen.Main); state.tab.value = 1 }
        else if (o == "plans" && ctx.hb.store.signedIn) { nav.reset(Screen.Main); state.tab.value = 2 }
        else if (o == "meals" && ctx.hb.store.signedIn) { nav.reset(Screen.Main); state.tab.value = 2 }
        else if (o == "groups" && ctx.hb.store.signedIn) { nav.reset(Screen.Main); state.tab.value = 3 }
        else if (o == "rewards" && ctx.hb.store.signedIn) { nav.reset(Screen.Main); nav.go(Screen.Rewards) }
        open.value = null
    }
    Box(Modifier.fillMaxSize()) {
        when (val s = nav.current) {
            Screen.Welcome -> WelcomeScreen(nav)
            Screen.About -> AboutScreen(nav)
            Screen.Create -> CreateScreen(nav)
            Screen.JoinCode -> JoinScreen(nav)
            is Screen.Consent -> ConsentScreen(nav, s)
            Screen.Permissions -> PermissionsScreen(nav)
            Screen.Main -> MainScreen(nav)
            Screen.Tour -> app.homebase.family.ui.TourScreen(nav)
            is Screen.Thread -> ThreadScreen(nav, s.id)
            is Screen.Perms -> app.homebase.family.ui.PermsScreen(nav)
            is Screen.Chores -> app.homebase.family.ui.ChoresScreen(nav)
            is Screen.Plans -> app.homebase.family.ui.PlansScreen(nav)
            is Screen.Rewards -> app.homebase.family.ui.RewardsScreen(nav)
            is Screen.Board -> app.homebase.family.ui.BoardScreen(nav)
            is Screen.Controls -> app.homebase.family.ui.ControlsScreen(nav, s.memberId)
            Screen.Rules -> app.homebase.family.ui.RulesScreen(nav)
            is Screen.Meal -> app.homebase.family.ui.MealScreen(nav, s.id)
            is Screen.Group -> app.homebase.family.ui.GroupScreen(nav, s.id)
            Screen.Feed -> app.homebase.family.ui.FeedScreen(nav)
            Screen.Emergency -> app.homebase.family.ui.EmergencyScreen(nav)
            Screen.Pocket -> app.homebase.family.ui.PocketScreen(nav)
            is Screen.Camera -> app.homebase.family.ui.CameraScreen(nav, s.purpose)
            is Screen.ScreenTime -> app.homebase.family.ui.ScreenTimeScreen(nav, s.memberId)
            Screen.Places -> PlacesScreen(nav)
            Screen.AddPlace -> AddPlaceScreen(nav)
            is Screen.History -> HistoryScreen(nav, s.memberId)
            Screen.Lists -> ListsScreen(nav)
            Screen.Settings -> SettingsScreen(nav)
            Screen.Transparency -> TransparencyScreen(nav)
            Screen.Invite -> InviteScreen(nav)
        }
        call?.let { CallOverlay(it) }
    }
    ping?.let { p ->
        AlertDialog(onDismissRequest = {}, title = { Text("${p.emoji} ${p.name} asked where you are") },
            text = { Text("You choose. Share your location with them right now, or say not now.") },
            confirmButton = {
                TextButton({
                    state.pingAsk.value = null
                    ctx.hb.scope.launch {
                        val spot = app.homebase.family.ui.lastSpot(ctx)
                        runCatching { if (spot != null) ctx.hb.api.answerPing(p.id, true, spot.lat, spot.lng) else ctx.hb.api.answerPing(p.id, false) }
                    }
                }) { Text("Share where I am") }
            },
            dismissButton = { TextButton({ state.pingAsk.value = null; ctx.hb.scope.launch { runCatching { ctx.hb.api.answerPing(p.id, false) } } }) { Text("Not now") } })
    }
}
