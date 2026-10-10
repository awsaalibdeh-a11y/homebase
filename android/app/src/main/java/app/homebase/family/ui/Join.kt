package app.homebase.family.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.homebase.family.hb
import kotlinx.coroutines.launch

@Composable
fun JoinScreen(nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var emoji by remember { mutableStateOf("🧒") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    Page {
        Heading("Join your family", "Ask a parent to open Homebase, tap Family, then Invite, and read you the code.")
        OutlinedTextField(code, { code = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(8) }, label = { Text("Invite code") }, singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters), modifier = Modifier.fillMaxWidth())
        Gap(8)
        OutlinedTextField(name, { name = it }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Gap(10)
        Text("Pick your picture", color = Muted)
        Gap(6)
        EmojiPicker(listOf("🧒", "👦", "👧", "🧑‍🎓", "😎", "🦊", "🐱", "🙂", "🧑"), emoji) { emoji = it }
        if (error.isNotEmpty()) { Gap(8); Text(error, color = MaterialTheme.colorScheme.error) }
        Gap(18)
        BigButton(if (busy) "Checking…" else "Continue", enabled = !busy && code.length >= 6 && name.isNotBlank()) {
            busy = true; error = ""
            scope.launch {
                runCatching { ctx.hb.api.lookInvite(code) }
                    .onSuccess { look ->
                        if (look.role == "child") { busy = false; nav.go(Screen.Consent(code, name, emoji, look)) }
                        else runCatching { ctx.hb.api.join(code, name, emoji) }
                            .onSuccess { signIn(ctx, it); startHomebase(ctx); nav.reset(Screen.Permissions) }
                            .onFailure { error = it.message ?: "Could not join."; busy = false }
                    }
                    .onFailure { error = it.message ?: "Could not connect. Check the internet and try again."; busy = false }
            }
        }
        Gap(6)
        TextButton({ nav.back() }, Modifier.fillMaxWidth()) { Text("Back") }
    }
}

@Composable
private fun Bullets(lines: List<String>, mark: String) {
    Column { lines.forEach { Text("$mark  $it", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 3.dp)) } }
}

/** What a child reads, and agrees to, before anything is shared. */
@Composable
fun ConsentScreen(nav: Nav, s: Screen.Consent) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    Page {
        Heading("${s.look.invitedBy.ifBlank { "Your family" }} invited you to ${s.look.family}", "Read this first. You decide if you want to join.")
        SoftCard(tint = MintSoft) {
            Text("Your family will see", style = MaterialTheme.typography.titleMedium)
            Gap(6)
            Bullets(listOf("Where you are, on a map, while sharing is on", "Your battery level", "Messages and check-ins you send them", "When you arrive at or leave a saved place, like school. You get the same message."), "✓")
        }
        Gap(12)
        SoftCard(tint = RoseSoft) {
            Text("Homebase will never", style = MaterialTheme.typography.titleMedium)
            Gap(6)
            Bullets(listOf("Hide itself, or share anything in secret", "Read your other apps, browsing or private messages", "Switch on your microphone or camera by itself", "Lock your device or limit your screen time"), "✕")
        }
        Gap(12)
        SoftCard {
            Text("You are in control", style = MaterialTheme.typography.titleMedium)
            Gap(6)
            Bullets(listOf("Pause sharing whenever you need. The family sees it is paused.", "\"Where are you?\" is a request: you can say not now.", "Set quiet hours so calls and messages stay calm.", "Leave any time and everything you shared is deleted."), "•")
        }
        if (error.isNotEmpty()) { Gap(8); Text(error, color = MaterialTheme.colorScheme.error) }
        Gap(18)
        BigButton(if (busy) "Joining…" else "I understand, join", enabled = !busy) {
            busy = true; error = ""
            scope.launch {
                runCatching { ctx.hb.api.join(s.code, s.name, s.emoji) }
                    .onSuccess { signIn(ctx, it); startHomebase(ctx); nav.reset(Screen.Permissions) }
                    .onFailure { error = it.message ?: "Could not join."; busy = false }
            }
        }
        Gap(8)
        SoftButton("Not now") { nav.back() }
    }
}

@Composable
fun AboutScreen(nav: Nav) {
    Page {
        Heading("What Homebase will never do", "Homebase is for staying in touch, not for watching over someone's shoulder.")
        SoftCard(tint = MintSoft) {
            Bullets(listOf("It never hides. A notification always shows when it is running.", "It never reads your other apps, your browsing or your private messages.", "It never turns on your microphone or camera by itself. Calls only start when you answer.",
                "It never locks your tablet or limits your screen time.", "Location is kept for 7 days, then deleted. Leave the family and everything you shared goes."), "•")
        }
        Gap(12)
        SoftCard {
            Text("What the family can see", style = MaterialTheme.typography.titleMedium)
            Gap(6)
            Text("Only what is on your screen under Me, then \"What my family can see\". Nothing else.", style = MaterialTheme.typography.bodyLarge)
        }
        Gap(16)
        BigButton("Got it") { nav.back() }
    }
}
