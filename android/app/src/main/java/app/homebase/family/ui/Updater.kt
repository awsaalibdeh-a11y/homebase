package app.homebase.family.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.homebase.family.BuildConfig
import app.homebase.family.FamilyConfig
import app.homebase.family.hb
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Updates inside the app: Mellow downloads the new version itself and hands it to Android's installer.
 * No browser, no Downloads folder. Android always asks "Install?" for an app that is not from the Play Store; that one tap cannot be skipped.
 */
object Updater {
    val progress = MutableStateFlow(-1f)                              // -1 not downloading, 0..1 downloading, 2 ready
    val error = MutableStateFlow<String?>(null)

    fun start(ctx: Context) {
        if (progress.value in 0f..1f) return
        progress.value = 0f; error.value = null
        ctx.hb.scope.launch {
            runCatching {
                val file = withContext(Dispatchers.IO) {
                    val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
                    val out = File(dir, "mellow.apk")
                    val req = Request.Builder().url(ctx.hb.store.server + "/download").build()
                    OkHttpClient().newCall(req).execute().use { r ->
                        if (!r.isSuccessful) error("The update could not be downloaded (${r.code}).")
                        val body = r.body ?: error("Empty download.")
                        val total = body.contentLength().coerceAtLeast(1)
                        var done = 0L
                        body.byteStream().use { input ->
                            out.outputStream().use { o ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    o.write(buf, 0, n); done += n
                                    progress.value = (done.toFloat() / total).coerceAtMost(1f)
                                }
                            }
                        }
                    }
                    out
                }
                progress.value = 2f
                install(ctx, file)
            }.onFailure { progress.value = -1f; error.value = it.message ?: "The update could not be downloaded." }
        }
    }

    fun install(ctx: Context, file: File = File(File(ctx.cacheDir, "updates"), "mellow.apk")) {
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            toast(ctx, "Allow Mellow to install updates, then tap Install again.")
            runCatching { ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { toast(ctx, "Could not open the installer") }
    }
}

/** The update prompt: what is new, and one button that downloads and installs it inside the app. */
@Composable
fun UpdateDialog(c: FamilyConfig, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val p by Updater.progress.collectAsState()
    val err by Updater.error.collectAsState()
    AlertDialog(onDismissRequest = onDismiss, containerColor = Surface, title = { Text("Mellow ${c.versionName} is ready") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                c.notes.take(6).forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                if (p in 0f..1f) { Text("Downloading… ${(p * 100).toInt()}%", Modifier.padding(top = 8.dp)); LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth()) }
                if (p == 2f) Text("Downloaded. Tap Install on the next screen. Your family and settings stay as they are.", Modifier.padding(top = 8.dp), color = Muted)
                err?.let { Text(it, color = Rose) }
            }
        },
        confirmButton = { TextButton({ if (p == 2f) Updater.install(ctx) else Updater.start(ctx) }, enabled = p !in 0f..1f) { Text(if (p == 2f) "Install" else "Update now") } },
        dismissButton = { TextButton(onDismiss) { Text("Later") } })
}

/** Settings: the version, and a check for a newer one. */
@Composable
fun UpdateCard() {
    val ctx = LocalContext.current
    var cfg by remember { mutableStateOf<FamilyConfig?>(null) }
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { cfg = runCatching { ctx.hb.api.config() }.getOrNull() }
    val c = cfg
    SoftCard {
        Text("Mellow ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
        if (c != null && c.versionCode > BuildConfig.VERSION_CODE) {
            Text("Mellow ${c.versionName} is ready. It downloads and installs right here.", color = Muted)
            BigButton("Update now", Modifier.padding(top = 8.dp)) { open = true }
        } else Text(if (c == null) "Checking for updates…" else "You have the newest version.", color = Muted)
    }
    if (open && c != null) UpdateDialog(c) { open = false }
}
