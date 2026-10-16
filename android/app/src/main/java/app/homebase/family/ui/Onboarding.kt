package app.homebase.family.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.homebase.family.AuthResp
import app.homebase.family.HomebaseService
import app.homebase.family.hb
import kotlinx.coroutines.launch

fun startHomebase(ctx: Context) {
    runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, HomebaseService::class.java)) }
}

fun signIn(ctx: Context, r: AuthResp) {
    val s = ctx.hb.store
    s.token = r.token; s.myId = r.me.id; s.role = r.me.role; s.myName = r.me.name; s.familyName = r.family.name; s.cursor = -1
}

@Composable
fun Page(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 18.dp)) { content() }
}

@Composable
fun EmojiPicker(options: List<String>, picked: String, onPick: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { e ->
            Row(Modifier.clip(CircleShape).clickable { onPick(e) }) { Avatar(e, 48, if (e == picked) Mint.copy(alpha = 0.35f) else AccentSoft) }
        }
    }
}

@Composable
fun WelcomeScreen(nav: Nav) {
    Page {
        Gap(40)
        Text("🏠", fontSize = 64.sp)
        Text("Homebase", style = MaterialTheme.typography.headlineLarge)
        Text("Stay close. Stay yourself.", style = MaterialTheme.typography.titleLarge, color = Accent, modifier = Modifier.padding(top = 4.dp))
        Gap(10)
        Text("Location, messages and calls for your family, and honest about every bit of it. Nothing is hidden, and you can always pause.", style = MaterialTheme.typography.bodyLarge, color = Muted)
        Gap(28)
        BigButton("Create a family (parent)") { nav.go(Screen.Create) }
        Gap(10)
        SoftButton("Join with a code") { nav.go(Screen.JoinCode) }
        Gap(14)
        TextButton({ nav.go(Screen.About) }, Modifier.fillMaxWidth()) { Text("What Homebase will never do") }
    }
}

@Composable
fun CreateScreen(nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var family by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var emoji by remember { mutableStateOf("🧑") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    Page {
        Heading("Create your family", "You will be the first parent. Invite everyone else with a code.")
        OutlinedTextField(family, { family = it }, label = { Text("Family name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Gap(8)
        OutlinedTextField(name, { name = it }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Gap(10)
        Text("Pick your picture", color = Muted)
        Gap(6)
        EmojiPicker(listOf("🧑", "👩", "👨", "🧔", "👵", "👴", "🙂", "😎"), emoji) { emoji = it }
        if (error.isNotEmpty()) { Gap(8); Text(error, color = MaterialTheme.colorScheme.error) }
        Gap(18)
        BigButton(if (busy) "Creating…" else "Create family", enabled = !busy && name.isNotBlank()) {
            busy = true; error = ""
            scope.launch {
                runCatching { ctx.hb.api.createFamily(family.ifBlank { "Our family" }, name, emoji) }
                    .onSuccess { signIn(ctx, it); startHomebase(ctx); nav.reset(Screen.Permissions) }
                    .onFailure { error = it.message ?: "Could not connect. Check the internet and try again."; busy = false }
            }
        }
        Gap(6)
        TextButton({ nav.back() }, Modifier.fillMaxWidth()) { Text("Back") }
    }
}
