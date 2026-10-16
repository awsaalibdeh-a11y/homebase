package app.homebase.family.ui

import android.util.Base64
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.homebase.family.hb
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class LinkableApp(val name: String, val icon: String, val url: String, val what: String)

val LINKABLE_APPS = listOf(
    LinkableApp("Platter", "🍽️", "https://platter-87b0.onrender.com", "Recipes you plan there appear in the family meal plan, with ingredients and method."),
    LinkableApp("Scout", "🧭", "https://scout-15t6.onrender.com", "Put a place on the family calendar, save it as a family place, or share it in the chat."),
)

/** The address that connects a Platter or Scout to this family: the private key is in the link, and the app keeps it and cleans the address bar. */
private fun connectLink(base: String, key: String, family: String, appUrl: String): String {
    val json = buildJsonObject { put("b", base); put("k", key); put("f", family) }.toString()
    val code = Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    return "$appUrl/#/homebase?c=$code"
}

/** What Platter and Scout are, and the two steps to use them: open and install the app, then link it. Parents only. */
@Composable
fun LinkAppsDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var linked by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(Unit) { linked = runCatching { ctx.hb.api.linkedApps() }.getOrNull() }
    fun done() { ctx.hb.store.linkPrompt = true; onDismiss() }
    AlertDialog(
        onDismissRequest = { done() },
        containerColor = Surface,
        title = { Text("Link Platter and Scout") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Two sister apps work with Mellow. Install each one, link it once, and it shares with your family.", color = Muted, fontSize = 13.sp)
                LINKABLE_APPS.forEach { a ->
                    val isLinked = linked?.contains(a.name) == true
                    Text("${a.icon}  ${a.name}" + if (isLinked) "  ✓ linked" else "", style = MaterialTheme.typography.titleMedium)
                    Text(a.what, color = Muted, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("1 · Open ${a.name} and install it", false) { openLink(ctx, a.url) }
                    }
                    if (!isLinked) Pill("2 · Link ${a.name}", true) {
                        ctx.hb.scope.launch {
                            runCatching {
                                val key = ctx.hb.api.linkApp(a.name)
                                connectLink(ctx.hb.store.server, key, ctx.hb.store.familyName, a.url)
                            }.onSuccess { link -> ctx.hb.store.linkPrompt = true; linked = (linked ?: emptyList()) + a.name; openLink(ctx, link) }
                                .onFailure { toast(ctx, it.message ?: "Could not link ${a.name}") }
                        }
                    }
                }
                Text("Linking opens a private link. Open it on the device where you use the app, in the same browser or app, and it connects by itself. You can unlink any time on the Mellow website: More, then Linked apps.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
        },
        confirmButton = { TextButton({ done() }) { Text("Done") } },
        dismissButton = {},
    )
}
