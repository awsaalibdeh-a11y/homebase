package app.homebase.family.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.homebase.family.RulesResp
import app.homebase.family.hb
import kotlinx.coroutines.launch

/** For parents only: the family's house rules and a daily playing/watching limit per child. Children can read what applies to them. */
@Composable
fun RulesTab() {
    val ctx = LocalContext.current
    val scope = ctx.hb.scope
    var loaded by remember { mutableStateOf<RulesResp?>(null) }
    var text by remember { mutableStateOf("") }
    val limits = remember { mutableStateMapOf<Int, String>() }
    LaunchedEffect(Unit) {
        runCatching { ctx.hb.api.rules() }.onSuccess { r ->
            loaded = r; text = r.text
            r.children.forEach { limits[it.id] = r.limits[it.id.toString()]?.takeIf { v -> v > 0 }?.toString() ?: "" }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Heading("Family rules", "Everyone in the family can read these. Changes are told to the family.")
        SoftCard {
            Text("House rules", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(text, { text = it.take(1500) }, Modifier.fillMaxWidth(), minLines = 5, placeholder = { Text("e.g. Homework before games. Phones off at dinner. Bed at 9.") })
        }
        SoftCard {
            Text("Daily playing and watching limit", style = MaterialTheme.typography.titleMedium)
            Text("When it is reached, your child gets a friendly nudge and the family is told. Leave empty for no limit.", color = Muted)
            loaded?.children.orEmpty().forEach { c ->
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${c.emoji} ${c.name}", Modifier.weight(1f))
                    OutlinedTextField(limits[c.id] ?: "", { limits[c.id] = it.filter(Char::isDigit).take(3) }, Modifier.width(120.dp), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), suffix = { Text("min") })
                }
            }
            if (loaded?.children.isNullOrEmpty() && loaded != null) Text("No children in the family yet.", color = Muted)
        }
        BigButton("Save the rules") {
            scope.launch {
                runCatching { ctx.hb.api.saveRules(text, limits.mapValues { it.value.toIntOrNull() ?: 0 }) }
                    .onSuccess { toast(ctx, "Rules saved") }.onFailure { toast(ctx, it.message ?: "Could not save") }
            }
        }
        Gap(8)
    }
}


/** The same rules editor as the Rules tab, as its own screen with a Back button (opened from a child's Controls). */
@Composable
fun RulesScreen(nav: Nav) {
    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxSize()) {
        androidx.compose.material3.TextButton({ nav.back() }, Modifier.padding(start = 8.dp, top = 8.dp)) { androidx.compose.material3.Text("Back") }
        RulesTab()
    }
}
