package app.homebase.family.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.homebase.family.Member
import app.homebase.family.hb
import kotlinx.coroutines.launch

/** A parent can only do what the child leaves switched on. The server enforces it; this screen just edits it. */
private val CONTROLS = listOf(
    Triple("location", "See where I am", "Parents see you on the map. Turn it off and they only see \"not sharing\"."),
    Triple("trail", "See my trail", "The line of where you have been in the last day."),
    Triple("battery", "See my battery", "So they know if your phone is about to die."),
    Triple("messages", "Send me messages", "Check-ins and SOS from you always go through, and you can still message them."),
    Triple("calls", "Call me", "You can always call your parents."),
    Triple("ping", "Ask \"Where are you?\"", "A request you can answer or decline either way."),
    Triple("screentime", "See my screen time", "How long you spend playing or watching, and in which apps. Only that, never what you do in them."),
)

/** Whether the buttons a parent sees for [m] should work. Everyone else is never limited. */
@Composable
fun canDo(m: Member, key: String): Boolean = !LocalContext.current.hb.store.isParent || m.isParent || m.allows(key)

@Composable
fun PermsScreen(nav: Nav) {
    val ctx = LocalContext.current
    val fam by ctx.hb.state.family.collectAsState()
    val me = fam?.members?.firstOrNull { it.id == ctx.hb.store.myId }
    val scope = ctx.hb.scope
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { TextButton({ nav.back() }) { Text("Back") } }
        Heading("What parents can do", "You decide. Switch something off and Homebase stops it straight away. Your parents see that it is off, so nobody is guessing.")
        CONTROLS.forEach { (key, title, why) ->
            val on = me?.allows(key) ?: true
            SoftCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(why, color = Muted)
                    }
                    Switch(checked = on, onCheckedChange = { v ->
                        scope.launch {
                            runCatching { ctx.hb.api.setPerms(mapOf(key to v)) }
                                .onSuccess { ctx.hb.state.refreshFamily() }
                                .onFailure { toast(ctx, it.message ?: "Could not change it") }
                        }
                    })
                }
            }
        }
        Text("For safety, an SOS or \"I'm OK\" check-in from you always reaches your parents.", color = Muted)
        Gap(8)
    }
}
