package app.homebase.family.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.homebase.family.CallUi
import app.homebase.family.Member
import app.homebase.family.hb
import kotlinx.coroutines.delay

/** A round control with its label underneath: filled when it is switched on, quiet when it is off. */
@Composable
private fun CallButton(icon: ImageVector, label: String, fill: Color, ink: Color, size: Int = 68, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(size.dp).clip(CircleShape).background(fill).clickable(role = Role.Button, onClickLabel = label) { onClick() }, contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, tint = ink, modifier = Modifier.size((size * 0.42).dp))
        }
        Text(label, color = Muted, fontSize = 13.sp)
    }
}

private fun statusText(c: CallUi, secs: Long): String = when (c.status) {
    "dialing" -> "Calling…"
    "ringing" -> if (c.incoming) (if (c.urgent) "Urgent call" else "Incoming Homebase call") else "Ringing…"
    "connecting" -> "Connecting…"
    "active" -> "%02d:%02d".format(secs / 60, secs % 60)
    "declined" -> "Declined"
    "missed" -> "No answer"
    "busy" -> "Busy"
    "quiet" -> "Quiet hours"
    else -> "Call ended"
}

/** Soft rings that spread out from the avatar while a call is ringing or connecting, and a steady glow once it is live. */
@Composable
private fun CallAvatar(emoji: String, ringing: Boolean, live: Boolean) {
    val t = rememberInfiniteTransition(label = "rings")
    val a by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart), label = "a")
    val b by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, delayMillis = 1100, easing = LinearEasing), RepeatMode.Restart), label = "b")
    Box(Modifier.size(260.dp), contentAlignment = Alignment.Center) {
        if (ringing) listOf(a, b).forEach { p -> Box(Modifier.size(140.dp).scale(1f + p * 0.8f).alpha((1f - p) * 0.35f).border(2.dp, Accent, CircleShape)) }
        if (live) Box(Modifier.size(168.dp).border(2.dp, Mint.copy(alpha = 0.55f), CircleShape))
        Box(Modifier.size(140.dp).clip(CircleShape).background(Surface2).border(1.dp, Color(0x1F000000), CircleShape), contentAlignment = Alignment.Center) { Text(emoji, fontSize = 70.sp) }
    }
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
    val ringing = c.status == "ringing" || c.status == "dialing" || c.status == "connecting"
    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (c.incoming) "Homebase call" else "Calling from Homebase", Modifier.padding(top = 36.dp), color = Muted, fontSize = 14.sp, letterSpacing = 1.2.sp)
            Box(Modifier.padding(top = 28.dp)) { CallAvatar(c.peerEmoji, ringing, c.status == "active") }
            Text(c.peerName, Modifier.padding(top = 4.dp), color = Ink, fontSize = 32.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Text(statusText(c, secs), Modifier.padding(top = 8.dp), color = if (c.status == "active") Mint else Muted, fontSize = if (c.status == "active") 22.sp else 18.sp,
                fontWeight = if (c.status == "active") FontWeight.Medium else FontWeight.Normal)
            if (c.note.isNotBlank()) Text(c.note, Modifier.padding(top = 10.dp), color = Muted, textAlign = TextAlign.Center)
            if (c.status == "quiet") {
                TextButton({ calls.hangUp(); calls.start(Member(c.peerId, c.peerName, "", c.peerEmoji), true) }, Modifier.padding(top = 12.dp)) { Text("It is important: ring anyway", color = Sun) }
            }
            Box(Modifier.weight(1f))
            if (c.incoming && c.status == "ringing") {
                Row(Modifier.fillMaxWidth().padding(bottom = 36.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    CallButton(Icons.Filled.CallEnd, "Decline", Rose, Color.White, 78) { calls.decline() }
                    CallButton(Icons.Filled.Call, "Answer", Mint, Color.White, 78) { answer() }
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(bottom = 36.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top) {
                    CallButton(if (c.speaker) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff, "Speaker", if (c.speaker) Ink else Surface2, if (c.speaker) Bg else Ink) { calls.speaker(!c.speaker) }
                    CallButton(Icons.Filled.CallEnd, "End", Rose, Color.White, 78) { calls.hangUp() }
                    CallButton(if (c.muted) Icons.Filled.MicOff else Icons.Filled.Mic, if (c.muted) "Muted" else "Mute", if (c.muted) Ink else Surface2, if (c.muted) Bg else Ink) { calls.mute(!c.muted) }
                }
            }
        }
    }
}
