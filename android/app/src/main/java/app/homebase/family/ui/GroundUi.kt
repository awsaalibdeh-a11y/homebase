package app.homebase.family.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.homebase.family.BlockInfo
import app.homebase.family.Member
import app.homebase.family.hb
import kotlinx.coroutines.launch

private val PRESETS = listOf("Roblox", "Minecraft", "Fortnite", "YouTube", "TikTok", "Netflix", "Discord", "Chrome")
private val LENGTHS = listOf(30 to "30 min", 60 to "1 h", 120 to "2 h", 240 to "4 h", 720 to "12 h", 1440 to "Tomorrow")

/** A parent grounds apps for one child: pick apps, pick how long. The family sees it in the chat. */
@Composable
fun GroundDialog(child: Member, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = ctx.hb.scope
    val picked = remember { mutableStateListOf<String>() }
    var other by remember { mutableStateOf("") }
    var mins by remember { mutableIntStateOf(60) }
    var error by remember { mutableStateOf("") }
    var active by remember { mutableStateOf<List<BlockInfo>>(child.blocks) }
    LaunchedEffect(child.id) { runCatching { ctx.hb.api.blocks(child.id) }.onSuccess { active = it.blocks } }
    AlertDialog(onDismissRequest = onClose, title = { Text("🚫 Ground apps for ${child.name}") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Apps are closed on their laptop and tablet for as long as you choose, and ${child.name} is told why.", color = Muted)
            if (active.isNotEmpty()) {
                Text("Grounded now", style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
                active.forEach { b ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${b.label} until ${clockOf(b.until)}")
                        TextButton({ scope.launch { runCatching { ctx.hb.api.liftBlock(b.id) }.onSuccess { active = active.filter { it.id != b.id }; ctx.hb.state.refreshFamily() } } }) { Text("Lift") }
                    }
                }
            }
            Text("Choose apps", style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
            PRESETS.chunked(2).forEach { pair ->
                Row { pair.forEach { n -> TextButton({ if (n in picked) picked.remove(n) else picked.add(n) }) { Text((if (n in picked) "✓ " else "") + n, maxLines = 1, softWrap = false) } } }
            }
            OutlinedTextField(other, { other = it.take(40) }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Another app or website") })
            Text("For how long", style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
            LENGTHS.chunked(3).forEach { row ->
                Row { row.forEach { (m, t) -> TextButton({ mins = m }) { Text((if (mins == m) "● " else "") + t, maxLines = 1, softWrap = false) } } }
            }
            if (error.isNotEmpty()) Text(error, color = Rose)
        }
    }, confirmButton = {
        TextButton({
            val list = picked.toList() + listOfNotNull(other.trim().ifBlank { null })
            if (list.isEmpty()) { error = "Pick at least one app."; return@TextButton }
            scope.launch {
                runCatching { ctx.hb.api.ground(child.id, mins, list) }
                    .onSuccess { toast(ctx, "${child.name} is grounded from ${list.joinToString(", ")}"); ctx.hb.state.refreshFamily(); onClose() }
                    .onFailure { error = it.message ?: "Could not ground" }
            }
        }) { Text("Ground them") }
    }, dismissButton = { TextButton(onClose) { Text("Close") } })
}

object Notices {
    fun grounded(ctx: Context, label: String, until: String) {
        app.homebase.family.Notifications.post(ctx, app.homebase.family.Notifications.CH_ALERTS, "$label is grounded", "A parent grounded $label until $until.", "map")
    }
}
