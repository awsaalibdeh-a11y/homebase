package app.homebase.family.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.homebase.family.hb
import kotlinx.coroutines.launch

val STATUS_PRESETS = listOf("🏠" to "At home", "📚" to "Studying", "🎮" to "Gaming", "🍽️" to "Eating", "🚌" to "On my way", "⚽" to "Out playing", "😴" to "Sleeping", "🧘" to "Chilling")

/** "What are you up to?": one tap, for a few hours, chosen and changed only by the person themselves. */
@Composable
fun StatusDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.hb
    val fam by app.state.family.collectAsState()
    val mine = fam?.members?.firstOrNull { it.id == app.store.myId }?.status
    var hours by remember { mutableIntStateOf(3) }
    var own by remember { mutableStateOf("") }
    fun set(emoji: String, text: String) {
        app.scope.launch {
            runCatching { app.api.setStatus(emoji, text, hours) }
                .onSuccess { app.state.refreshFamily(); toast(ctx, "Your family can see it now"); onDismiss() }
                .onFailure { toast(ctx, it.message ?: "Could not set it") }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text("What are you up to?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Your family sees this for a few hours, then it goes away by itself. Only you can change it.", color = Muted, fontSize = 13.sp)
                STATUS_PRESETS.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { (e, t) ->
                            val on = mine != null && mine.emoji == e && mine.text == t
                            Column(
                                Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(if (on) AccentSoft else Surface2).clickable { set(e, t) }.padding(vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) { Text(e, fontSize = 22.sp); Text(t, fontSize = 11.sp, maxLines = 1) }
                        }
                    }
                }
                OutlinedTextField(own, { own = it.take(40) }, Modifier.fillMaxWidth(), placeholder = { Text("Or write your own") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1 to "1 hour", 3 to "3 hours", 24 to "All day").forEach { (h, label) ->
                        Text(label, Modifier.clip(RoundedCornerShape(50)).background(if (hours == h) Accent else Surface2).clickable { hours = h }.padding(horizontal = 12.dp, vertical = 6.dp),
                            color = if (hours == h) OnAccent else Ink, fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton({ if (own.isNotBlank()) set("💬", own.trim()) else onDismiss() }) { Text(if (own.isNotBlank()) "Set" else "Close") } },
        dismissButton = {
            if (mine != null) TextButton({ app.scope.launch { runCatching { app.api.clearStatus() }.onSuccess { app.state.refreshFamily(); onDismiss() } } }) { Text("Clear it") }
        },
    )
}
