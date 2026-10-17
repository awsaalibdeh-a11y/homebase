package app.homebase.family.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.homebase.family.hb
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Records a short voice note (AAC in an .m4a file, up to a minute). */
object Voice {
    private var rec: MediaRecorder? = null
    private var file: File? = null
    private var started = 0L
    val recording get() = rec != null

    fun start(ctx: Context): Boolean {
        cancel()
        return runCatching {
            val f = File(ctx.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(48_000)
            r.setAudioSamplingRate(22_050)
            r.setMaxDuration(60_000)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            rec = r; file = f; started = System.currentTimeMillis()
            true
        }.getOrElse { cancel(); false }
    }

    /** Stops and gives back the file, or null if it was too short to be a message. */
    fun stop(): File? {
        val r = rec ?: return null
        val f = file
        val long = System.currentTimeMillis() - started
        rec = null; file = null
        val ok = runCatching { r.stop() }.isSuccess
        runCatching { r.release() }
        return if (ok && long > 700 && f != null && f.length() > 0) f else { f?.delete(); null }
    }

    fun cancel() {
        runCatching { rec?.stop() }; runCatching { rec?.release() }
        rec = null; file?.delete(); file = null
    }
}

fun sendVoice(ctx: Context, f: File, to: Int) {
    ctx.hb.scope.launch {
        runCatching {
            val data = withContext(Dispatchers.IO) { f.readBytes() }
            val url = ctx.hb.api.uploadAudio("data:audio/mp4;base64," + Base64.encodeToString(data, Base64.NO_WRAP))
            ctx.hb.api.send("voice", url, to)
        }.onSuccess { ctx.hb.state.add(it); toast(ctx, "Voice note sent") }.onFailure { toast(ctx, it.message ?: "Could not send the voice note") }
        f.delete()
    }
}

/** A voice note in a chat bubble: tap to play, tap again to stop. */
@Composable
fun VoiceBubble(body: String, ink: Color) {
    val ctx = LocalContext.current
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    DisposableEffect(body) { onDispose { runCatching { player?.release() } } }
    Row(
        Modifier.clip(RoundedCornerShape(20.dp)).clickable {
            if (playing) { runCatching { player?.stop(); player?.release() }; player = null; playing = false }
            else runCatching {
                player = MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    setDataSource(pictureUrl(ctx, body))
                    setOnPreparedListener { it.start(); playing = true }
                    setOnCompletionListener { playing = false }
                    prepareAsync()
                }
            }.onFailure { toast(ctx, "Could not play it") }
        }.padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(if (playing) "■" else "▶", color = ink, fontSize = 20.sp)
        Text("🎤 Voice note" + if (playing) " · playing" else "", color = ink)
    }
}
