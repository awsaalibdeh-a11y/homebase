package app.homebase.family.ui

import android.content.Context
import android.graphics.Color as AColor
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.homebase.family.LockInfo
import app.homebase.family.LockResp
import app.homebase.family.Member
import app.homebase.family.hb
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val IDEAS = listOf("Ride your bike 🚲", "Read a book 📖", "Bring someone a glass of water 💧", "Go outside for some fresh air 🌳", "Tidy up a little 🧹", "Draw or build something ✏️")
private val CHOICES = listOf(15, 30, 60, 90, 120, 180, 240)
fun minutesText(m: Int) = if (m >= 60) "${m / 60} h" + (if (m % 60 != 0) " ${m % 60} min" else "") else "$m min"
fun clockOf(epochSeconds: Double): String = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date((epochSeconds * 1000).toLong()))

/** A parent's lock for one child: pick how long (never more than four hours a day in total), or unlock now. */
@Composable
fun LockDialog(child: Member, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = ctx.hb.scope
    var st by remember { mutableStateOf<LockResp?>(null) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(child.id) { runCatching { ctx.hb.api.lockState(child.id) }.onSuccess { st = it }.onFailure { error = it.message ?: "Could not load it" } }
    val left = (st?.left ?: 0) / 60
    AlertDialog(onDismissRequest = onClose, title = { Text("🔒 Lock ${child.name}'s devices") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val lk = st?.lock
            Text(if (lk != null) "Locked by ${lk.byName} until ${clockOf(lk.until)}." else "Their phone and computer show a lock screen for as long as you choose. They can still send an SOS or ask to be unlocked.", color = Muted)
            if (st != null) Text("${minutesText(left)} of lock time left today (the most is 4 hours a day).", style = MaterialTheme.typography.titleSmall)
            if (error.isNotEmpty()) Text(error, color = Rose)
            if (lk == null && st != null) CHOICES.forEach { mins ->
                TextButton({
                    scope.launch {
                        runCatching { ctx.hb.api.lock(child.id, mins) }
                            .onSuccess { toast(ctx, "${child.name} is locked until ${clockOf(it.until)}"); ctx.hb.state.refreshFamily(); onClose() }
                            .onFailure { error = it.message ?: "Could not lock" }
                    }
                }, enabled = mins <= left) { Text(minutesText(mins)) }
            }
        }
    }, confirmButton = {
        if (st?.lock != null) TextButton({ scope.launch { runCatching { ctx.hb.api.releaseLock(child.id) }.onSuccess { toast(ctx, "${child.name} is unlocked"); ctx.hb.state.refreshFamily(); onClose() } } }) { Text("Unlock now") }
    }, dismissButton = { TextButton(onClose) { Text("Close") } })
}

/** What a locked child sees inside Homebase. It always offers a way to ask for the device back and to send an SOS. */
@Composable
fun LockedScreen(lock: LockInfo) {
    val ctx = LocalContext.current
    val scope = ctx.hb.scope
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var asked by remember { mutableStateOf(false) }
    val idea = remember { IDEAS.random() }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val left = ((lock.until * 1000 - now) / 1000).toLong().coerceAtLeast(0L)
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("🔒", style = MaterialTheme.typography.displayMedium)
        Text("Locked", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(top = 8.dp))
        Text("${lock.byName} locked this device until ${clockOf(lock.until)}.", color = Muted, textAlign = TextAlign.Center)
        Text(if (left >= 3600) "${left / 3600} h ${left % 3600 / 60} min left" else "${left / 60}:${"%02d".format(left % 60)} left", style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(vertical = 18.dp))
        Text("While you wait: $idea", textAlign = TextAlign.Center)
        Gap(24)
        BigButton(if (asked) "Asked. They will see it." else "Ask to unlock early", enabled = !asked) { asked = true; scope.launch { runCatching { ctx.hb.api.askUnlock() } } }
        Gap(10)
        BigButton("🆘 I need help", color = Rose) { scope.launch { runCatching { ctx.hb.api.send("sos", "I need help", 0) }.onSuccess { toast(ctx, "SOS sent to your family") } } }
    }
}

/** The same lock, drawn over every other app on a child's phone (needs "Display over other apps", which the child allows once). */
class LockOverlay(private val ctx: Context, private val onAsk: () -> Unit, private val onSos: () -> Unit) {
    private val h = Handler(Looper.getMainLooper())
    private var view: LinearLayout? = null
    private var title: TextView? = null
    private var count: TextView? = null
    private var until = 0.0
    private val tick = object : Runnable {
        override fun run() {
            val left = ((until * 1000 - System.currentTimeMillis()) / 1000).toLong()
            if (left <= 0) { hide(); return }
            count?.text = if (left >= 3600) "${left / 3600} h ${left % 3600 / 60} min left" else "${left / 60}:${"%02d".format(left % 60)} left"
            h.postDelayed(this, 1000)
        }
    }

    fun update(lock: LockInfo?) {
        h.post {
            if (lock == null || lock.until * 1000 <= System.currentTimeMillis() || !Settings.canDrawOverlays(ctx)) hide() else show(lock)
        }
    }

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
        text = t; textSize = size; setTextColor(color); gravity = Gravity.CENTER; if (bold) setTypeface(typeface, Typeface.BOLD); setPadding(0, 8, 0, 8)
    }

    private fun button(t: String, bg: Int, fg: Int, click: () -> Unit) = Button(ctx).apply {
        text = t; isAllCaps = false; textSize = 17f; setTextColor(fg); setOnClickListener { click() }
        background = android.graphics.drawable.GradientDrawable().apply { setColor(bg); cornerRadius = 90f }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 150).apply { topMargin = 24 }
    }

    private fun show(lock: LockInfo) {
        until = lock.until
        if (view == null) {
            val v = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(AColor.parseColor("#0A0A0B")); setPadding(64, 64, 64, 64)
                isClickable = true; isFocusable = false                                    // covers everything and takes the touches
            }
            v.addView(text("🔒", 56f, AColor.WHITE))
            title = text("", 17f, AColor.parseColor("#9A9AA3")); v.addView(text("Locked", 32f, AColor.WHITE, true)); v.addView(title)
            count = text("", 40f, AColor.WHITE, true); v.addView(count)
            v.addView(text("While you wait: ${IDEAS.random()}", 17f, AColor.parseColor("#ECECF0")))
            v.addView(button("Ask to unlock early", AColor.parseColor("#1B1B1F"), AColor.WHITE) { onAsk() })
            v.addView(button("🆘 I need help", AColor.parseColor("#FF6B74"), AColor.parseColor("#08101F")) { onSos() })
            val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, PixelFormat.OPAQUE)
            runCatching { (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(v, lp); view = v }
        }
        title?.text = "${lock.byName} locked this device until ${clockOf(lock.until)}."
        h.removeCallbacks(tick); h.post(tick)
    }

    fun hide() {
        h.removeCallbacks(tick)
        view?.let { runCatching { (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it) } }
        view = null
    }
}
