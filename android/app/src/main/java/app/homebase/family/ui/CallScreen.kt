package app.homebase.family.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.homebase.family.CallUi
import app.homebase.family.Member
import app.homebase.family.hb
import kotlinx.coroutines.delay

@Composable
private fun RoundButton(label: String, tint: Color, ink: Color = Color.White, big: Boolean = false, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(if (big) 78.dp else 66.dp).clip(CircleShape).background(tint).clickable { onClick() }, contentAlignment = Alignment.Center) {
            Text(label, fontSize = if (big) 32.sp else 26.sp, color = ink)
        }
    }
}

private fun statusText(c: CallUi, secs: Long): String = when (c.status) {
    "dialing" -> "Calling…"
    "ringing" -> if (c.incoming) (if (c.urgent) "Urgent call" else "Incoming call") else "Ringing…"
    "connecting" -> "Connecting…"
    "active" -> "%02d:%02d".format(secs / 60, secs % 60)
    "declined" -> "Declined"
    "missed" -> "No answer"
    "busy" -> "Busy"
    "quiet" -> "Quiet hours"
    else -> "Call ended"
}

/** The call screen, shown inside the app and over the lock screen when someone rings. */
@Composable
fun CallOverlay(c: CallUi) {
    val ctx = LocalContext.current
    val calls = ctx.hb.calls
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(c.status) { while (true) { now = System.currentTimeMillis(); delay(500) } }
    val answerAfterPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) calls.accept() else calls.decline() }
    fun answer() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) calls.accept() else answerAfterPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    val secs = if (c.startedAt > 0) (now - c.startedAt) / 1000 else 0
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF6FA8E6), Color(0xFF2E67BD), Color(0xFF13296B))))) {
        Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.padding(top = 60.dp).size(120.dp).clip(CircleShape).background(Color(0x33FFFFFF)), contentAlignment = Alignment.Center) { Text(c.peerEmoji, fontSize = 62.sp) }
            Text(c.peerName, Modifier.padding(top = 18.dp), color = Color.White, fontSize = 30.sp, textAlign = TextAlign.Center)
            Text(statusText(c, secs), Modifier.padding(top = 8.dp), color = Color(0xFF8FE9FF), fontSize = 22.sp)
            if (c.note.isNotBlank()) Text(c.note, Modifier.padding(top = 10.dp), color = Color(0xCCFFFFFF), textAlign = TextAlign.Center)
            if (c.status == "quiet") {
                TextButton({ calls.hangUp(); calls.start(Member(c.peerId, c.peerName, "", c.peerEmoji), true) }, Modifier.padding(top = 12.dp)) { Text("It is important: ring anyway", color = Color(0xFFFFE08A)) }
            }
            Box(Modifier.weight(1f))
            if (c.incoming && c.status == "ringing") {
                Row(Modifier.fillMaxWidth().padding(bottom = 40.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    RoundButton("✕", Color(0xFFE5484D), big = true) { calls.decline() }
                    RoundButton("📞", Color(0xFF1FB88A), big = true) { answer() }
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(bottom = 40.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    RoundButton(if (c.speaker) "🔊" else "🔈", if (c.speaker) Color.White else Color(0x44FFFFFF), if (c.speaker) Color(0xFF13296B) else Color.White) { calls.speaker(!c.speaker) }
                    RoundButton("📵", Color(0xFFE5484D), big = true) { calls.hangUp() }
                    RoundButton(if (c.muted) "🔇" else "🎙️", if (c.muted) Color.White else Color(0x44FFFFFF), if (c.muted) Color(0xFF13296B) else Color.White) { calls.mute(!c.muted) }
                }
            }
        }
    }
}
