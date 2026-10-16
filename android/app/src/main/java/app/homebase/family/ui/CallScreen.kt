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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import app.homebase.family.CallUi
import app.homebase.family.Member
import app.homebase.family.R
import app.homebase.family.hb
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.delay
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

private val GreenGo = Color(0xFF1F9D5C)
private val QUICK_REPLIES = listOf("Can't talk right now", "I'll call you back soon", "On my way 🚶", "Text me 💬")

/** One round control, like the row of three under the picture: quiet when off, solid when switched on. */
@Composable
private fun RoundControl(icon: ImageVector, label: String, on: Boolean, video: Boolean, onClick: () -> Unit) {
    val fill = if (video) Color.White.copy(alpha = if (on) 0.95f else 0.22f) else if (on) Ink else Surface2
    val ink = if (video) (if (on) Color.Black else Color.White) else if (on) Bg else Ink
    Box(Modifier.size(64.dp).clip(CircleShape).background(fill).clickable(role = Role.Button, onClickLabel = label) { onClick() }, contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = label, tint = ink, modifier = Modifier.size(28.dp))
    }
}

/** The long coloured buttons: green to answer or use the camera, red to end. */
@Composable
private fun WidePill(icon: ImageVector, label: String, fill: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(62.dp).clip(RoundedCornerShape(31.dp)).background(fill).clickable(role = Role.Button, onClickLabel = label) { onClick() },
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
    }
}

private fun statusText(c: CallUi, secs: Long): String = when (c.status) {
    "dialing" -> "Calling…"
    "ringing" -> if (c.incoming) (if (c.urgent) "Urgent call" else "Incoming Mellow call") else "Ringing…"
    "connecting" -> "Connecting…"
    "active" -> "%02d:%02d".format(secs / 60, secs % 60)
    "declined" -> "Declined"
    "missed" -> "No answer"
    "busy" -> "Busy"
    "quiet" -> "Quiet hours"
    else -> "Call ended"
}

/** Moving bars on each side of the picture while the call is live, small and still when it is not. */
@Composable
private fun Waves(live: Boolean, flip: Boolean) {
    val t = rememberInfiniteTransition(label = "waves")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    val bars = 6
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(bars) { n ->
            val i = if (flip) bars - 1 - n else n
            val wave = abs(sin(phase * 2f * PI.toFloat() + i * 0.9f))
            val h = if (live) 12f + 44f * wave * (1f - i * 0.1f) else 8f + 6f * (1f - i * 0.12f)
            val color = lerp(Color(0xFFD6E84B), GreenGo, i / (bars - 1f))
            Box(Modifier.width(5.dp).height(h.dp).clip(RoundedCornerShape(3.dp)).background(color.copy(alpha = 1f - i * 0.1f)))
        }
    }
}

/** The person's photo (or emoji) in a big circle, with rings spreading out while it rings. */
@Composable
private fun CallAvatar(peerId: Int, emoji: String, ringing: Boolean) {
    val ctx = LocalContext.current
    val fam by ctx.hb.state.family.collectAsState()
    val member = fam?.members?.firstOrNull { it.id == peerId }
    val t = rememberInfiniteTransition(label = "rings")
    val a by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart), label = "a")
    val b by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, delayMillis = 1100, easing = LinearEasing), RepeatMode.Restart), label = "b")
    Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
        if (ringing) listOf(a, b).forEach { p -> Box(Modifier.size(140.dp).scale(1f + p * 0.45f).alpha((1f - p) * 0.35f).border(2.dp, Ink, CircleShape)) }
        if (member != null) MemberAvatar(member, 140, Surface2, circle = true)
        else Box(Modifier.size(140.dp).clip(CircleShape).background(Surface2), contentAlignment = Alignment.Center) { Text(emoji, fontSize = 70.sp) }
    }
}

/** A live video picture. Sinks are added and removed with the track, and the renderer is released with the screen. */
@Composable
fun VideoView(track: VideoTrack?, mirror: Boolean, overlay: Boolean, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val egl = ctx.hb.calls.eglBase
    val renderer = remember {
        SurfaceViewRenderer(ctx).apply {
            init(egl.eglBaseContext, null)
            setEnableHardwareScaler(true)
            setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            setMirror(mirror)
            if (overlay) setZOrderMediaOverlay(true)
        }
    }
    DisposableEffect(track) {
        runCatching { track?.addSink(renderer) }
        onDispose { runCatching { track?.removeSink(renderer) } }
    }
    DisposableEffect(Unit) { onDispose { runCatching { renderer.release() } } }
    AndroidView({ renderer }, modifier)
}

/** The call screen, shown inside the app and over the lock screen when someone rings. */
@Composable
fun CallOverlay(c: CallUi) {
    val ctx = LocalContext.current
    val calls = ctx.hb.calls
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var replying by remember { mutableStateOf(false) }
    LaunchedEffect(c.status) { while (true) { now = System.currentTimeMillis(); delay(500) } }
    val remoteTrack by calls.remoteVideo.collectAsState()
    val localTrack by calls.localVideo.collectAsState()
    val answerAfterPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) calls.accept() else calls.decline() }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) calls.camera(true) else toast(ctx, "Allow the camera to use video.") }
    fun answer() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) calls.accept() else answerAfterPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    fun toggleCamera() {
        if (c.cameraOn) calls.camera(false)
        else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) calls.camera(true)
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }
    val secs = if (c.startedAt > 0) (now - c.startedAt) / 1000 else 0
    val ringing = c.status == "ringing" || c.status == "dialing" || c.status == "connecting"
    val video = c.remoteCam && remoteTrack != null                    // their camera is showing: it fills the screen
    val ink = if (video) Color.White else Ink
    val soft = if (video) Color.White.copy(alpha = 0.8f) else Muted
    Box(Modifier.fillMaxSize().background(if (video) Color.Black else Bg)) {
        if (video) VideoView(remoteTrack, mirror = false, overlay = false, modifier = Modifier.fillMaxSize())
        else Image(painterResource(if (Look.dark) R.drawable.bg_dark else R.drawable.bg_light), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(c.peerName, Modifier.padding(top = 36.dp), color = ink, fontSize = 30.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
            Text(statusText(c, secs), Modifier.padding(top = 6.dp), color = soft, fontSize = if (c.status == "active") 20.sp else 17.sp)
            if (c.note.isNotBlank()) Text(c.note, Modifier.padding(top = 10.dp), color = soft, textAlign = TextAlign.Center)
            if (c.status == "quiet") {
                TextButton({ calls.hangUp(); calls.start(Member(c.peerId, c.peerName, "", c.peerEmoji), true) }, Modifier.padding(top = 12.dp)) { Text("It is important: ring anyway", color = Sun) }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (!video) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Waves(live = c.status == "active", flip = false)
                    Spacer(Modifier.width(10.dp))
                    CallAvatar(c.peerId, c.peerEmoji, ringing)
                    Spacer(Modifier.width(10.dp))
                    Waves(live = c.status == "active", flip = true)
                }
            }
            // green: answer, or the camera. a row of small controls. red: decline or end.
            Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (c.incoming && c.status == "ringing") WidePill(Icons.Filled.Call, "Answer", GreenGo) { answer() }
                else WidePill(if (c.cameraOn) Icons.Filled.Videocam else Icons.Filled.VideocamOff, if (c.cameraOn) "Camera on" else "Turn on camera", GreenGo) { toggleCamera() }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    RoundControl(if (c.muted) Icons.Filled.MicOff else Icons.Filled.Mic, if (c.muted) "Muted" else "Mute", c.muted, video) { calls.mute(!c.muted) }
                    RoundControl(Icons.Filled.Chat, "Message", false, video) { replying = true }
                    RoundControl(if (c.speaker) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff, "Speaker", c.speaker, video) { calls.speaker(!c.speaker) }
                    if (c.cameraOn) RoundControl(Icons.Filled.FlipCameraAndroid, "Flip camera", false, video) { calls.flipCamera() }
                }
                WidePill(Icons.Filled.CallEnd, if (c.incoming && c.status == "ringing") "Decline" else "End", Color(0xFFD93A3A)) { if (c.incoming && c.status == "ringing") calls.decline() else calls.hangUp() }
            }
        }
        if (c.cameraOn && localTrack != null) {
            Box(Modifier.align(Alignment.TopEnd).padding(top = 40.dp, end = 16.dp).size(width = 104.dp, height = 144.dp).clip(RoundedCornerShape(16.dp)).border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(16.dp))) {
                VideoView(localTrack, mirror = true, overlay = true, modifier = Modifier.fillMaxSize())
            }
        }
    }
    if (replying) AlertDialog(
        onDismissRequest = { replying = false },
        containerColor = Surface,
        title = { Text("Send a message") },
        text = {
            Column {
                QUICK_REPLIES.forEach { q ->
                    TextButton({
                        replying = false
                        sendQuick(ctx, "text", q, c.peerId, false, "Sent")
                        if (c.incoming && c.status == "ringing") calls.decline()
                    }, Modifier.fillMaxWidth()) { Text(q) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton({ replying = false }) { Text("Cancel") } },
    )
}
