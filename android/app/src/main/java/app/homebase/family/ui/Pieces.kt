package app.homebase.family.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun PickDialog(title: String, options: List<Pair<String, Int>>, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column { options.forEach { (label, v) -> TextButton({ onPick(v); onDismiss() }, Modifier.fillMaxWidth()) { Text(label, style = MaterialTheme.typography.bodyLarge) } } }
    }, confirmButton = {}, dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

fun buzz(ctx: Context) {
    runCatching {
        val v = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java).defaultVibrator else @Suppress("DEPRECATION") (ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
        v.vibrate(VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}

/** Hold for two seconds to send an SOS with your location to the whole family. A quick tap does nothing, so it cannot go off by accident. */
@Composable
fun SosButton(onFire: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var holding by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    Box(
        Modifier.fillMaxWidth().height(64.dp).background(if (holding) Rose else RoseSoft, RoundedCornerShape(32.dp)).pointerInput(Unit) {
            detectTapGestures(onPress = {
                holding = true
                job = scope.launch { delay(2000); buzz(ctx); onFire(); holding = false }
                tryAwaitRelease()
                job?.cancel(); holding = false
            })
        },
        contentAlignment = Alignment.Center,
    ) {
        Text(if (holding) "Keep holding…" else "🚨  Hold 2 seconds for SOS", color = if (holding) OnAccent else Rose, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(text, Modifier.padding(top = 10.dp, bottom = 2.dp), style = MaterialTheme.typography.titleMedium, color = Muted)
}

@Composable
fun TwoUp(left: @Composable () -> Unit, right: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        androidx.compose.foundation.layout.Box(Modifier.weight(1f)) { left() }
        androidx.compose.foundation.layout.Box(Modifier.weight(1f)) { right() }
    }
}
