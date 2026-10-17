package app.homebase.family.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.homebase.family.CriticalAlarm
import app.homebase.family.RiskAlert
import app.homebase.family.hb
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val SAFE_DNS = "family-filter-dns.cleanbrowsing.org"

/** What this phone's web filter is: the Private DNS host name when one is on, otherwise "off". Android 9 or newer. */
fun safeDnsStatus(ctx: Context): String {
    if (Build.VERSION.SDK_INT < 28) return "off"
    val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return "off"
    val lp = runCatching { cm.getLinkProperties(cm.activeNetwork) }.getOrNull() ?: return "off"
    return if (lp.isPrivateDnsActive) (lp.privateDnsServerName ?: "automatic") else "off"
}

fun safeDnsLabel(v: String?): String = when {
    v.isNullOrBlank() -> "Safe browsing: not reported yet"
    v == "off" -> "Safe browsing: off"
    v == "automatic" -> "Safe browsing: off (automatic DNS only)"
    v.contains("cleanbrowsing") || v.contains("family") -> "🛡️ Safe browsing on"
    else -> "Safe browsing: another filter ($v)"
}

/** A family web filter that works in every browser and in private tabs: Android's Private DNS set to a family filter. */
@Composable
fun SafeBrowsingScreen(nav: Nav) {
    val ctx = LocalContext.current
    var now by remember { mutableStateOf(safeDnsStatus(ctx)) }
    LaunchedEffect(Unit) { while (true) { now = safeDnsStatus(ctx); delay(2000) } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { TextButton({ nav.back() }) { Text("Back") } }
        Heading("Safe browsing", "A family web filter for the whole phone. It works in every browser and app, private tabs too.")
        SoftCard(tint = if (safeDnsLabel(now).startsWith("🛡️")) MintSoft else SunSoft) {
            Text(safeDnsLabel(now), style = MaterialTheme.typography.titleMedium)
            Text("Parents see whether it is on. Nothing about which sites you visit is ever sent to Mellow.", color = Muted)
        }
        SoftCard {
            Text("Set it up (one minute)", style = MaterialTheme.typography.titleMedium)
            Text("1. Tap Copy the filter name.\n2. Tap Open settings, then Private DNS (it can be under Network and internet, or Connections, More connection settings).\n3. Choose Private DNS provider host name, paste, and Save.", Modifier.padding(top = 4.dp))
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Copy the filter name", false) {
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Private DNS", SAFE_DNS))
                    toast(ctx, "Copied: $SAFE_DNS")
                }
                Pill("Open settings", true) {
                    runCatching { ctx.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { runCatching { ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                }
            }
        }
        SoftCard {
            Text("What it blocks", style = MaterialTheme.typography.titleMedium)
            Text("CleanBrowsing's free Family filter blocks adult content, sites that spread malware, and proxy or VPN tricks that get around filters, and turns on safe search. It is not a perfect list, and anyone can switch it off in Android settings: if they do, parents see \"off\".", color = Muted)
        }
    }
}

/** Parents: the safety alerts. Only the messages that matched, with where they were sent. */
@Composable
fun SafetyScreen(nav: Nav) {
    val ctx = LocalContext.current
    var alerts by remember { mutableStateOf<List<RiskAlert>?>(null) }
    LaunchedEffect(Unit) { alerts = runCatching { ctx.hb.api.risk() }.getOrNull() ?: emptyList() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { TextButton({ nav.back() }) { Text("Back") } }
        Heading("Safety alerts", "Messages in Mellow that looked like bullying, self-harm, adult content or grooming. Nothing else is ever shown here.")
        SoftCard(tint = AccentSoft) {
            Text("How it works", style = MaterialTheme.typography.titleMedium)
            Text("Mellow checks messages in the family chat, private messages and friend groups for known warning phrases. It can be wrong both ways, so read the message and talk it through. It does not read other apps, texts or emails.", color = Muted)
        }
        val list = alerts
        if (list == null) CenterNote("Loading…")
        else if (list.isEmpty()) CenterNote("No alerts. That is good news.")
        else list.forEach { a ->
            SoftCard(tint = if (a.seen) Surface else RoseSoft) {
                Text("⚠️ ${a.name}: ${a.what}", style = MaterialTheme.typography.titleMedium)
                Text((if (a.role == "sent") "Sent by ${a.name}" else "Sent to ${a.name} by ${a.other}") + " in ${a.where} · ${timeAgo(a.at)}", color = Muted, fontSize = 13.sp)
                Text("“${a.snippet}”", Modifier.padding(top = 6.dp))
                if (!a.seen) TextButton({ ctx.hb.scope.launch { runCatching { ctx.hb.api.riskSeen(a.id) }; alerts = alerts?.map { if (it.id == a.id) it.copy(seen = true) else it } } }) { Text("Mark as seen") }
            }
        }
    }
}

/** The SOS countdown: three seconds to cancel, then the SOS goes to the whole family with live location. */
@Composable
fun SosCountdown(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var left by remember { mutableIntStateOf(3) }
    LaunchedEffect(Unit) {
        while (left > 0) { buzz(ctx); delay(1000); left-- }
        sendQuick(ctx, "sos", "I need help", 0, true, "SOS sent. Your family sees you live.")
        onDone()
    }
    AlertDialog(
        onDismissRequest = onDone,
        containerColor = Rose,
        titleContentColor = Color.White,
        textContentColor = Color.White,
        title = { Text("🚨 Sending SOS in $left") },
        text = { Text("Your whole family gets a loud alert and sees where you are, live, for the next 30 minutes.") },
        confirmButton = { TextButton(onDone) { Text("Cancel", color = Color.White, fontSize = 18.sp) } },
    )
}

/** A kind note for a child who wrote something that sounded like self-harm. */
@Composable
fun SupportDialog(text: String, onDone: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDone,
        containerColor = Surface,
        title = { Text("💛 You matter") },
        text = { Text(text) },
        confirmButton = { TextButton(onDone) { Text("OK") } },
    )
}

/** On a child's Home: a waiting Critical Check-In (the sound stops with "I'm OK"), and the SOS that is still live. */
@Composable
fun SafetyBanners() {
    val ctx = LocalContext.current
    val fam by ctx.hb.state.family.collectAsState()
    val me = fam?.members?.firstOrNull { it.id == ctx.hb.store.myId } ?: return
    me.critical?.let { c ->
        SoftCard(tint = RoseSoft) {
            Text("🚨 ${c.by} needs you to check in", style = MaterialTheme.typography.titleLarge)
            Text("Tap I'm OK and the sound stops. Your phone goes back to how it was.", color = Muted)
            BigButton("I'm OK ✓", Modifier.padding(top = 8.dp), color = Mint) {
                CriticalAlarm.stop(ctx)
                sendQuick(ctx, "checkin", "I'm OK", 0, true, "Your family knows you are OK")
            }
        }
    }
    if (me.sos) SoftCard(tint = RoseSoft) {
        Text("🚨 SOS is on", style = MaterialTheme.typography.titleLarge)
        Text("Your family sees where you are, live. When you are safe, tell them.", color = Muted)
        BigButton("I'm safe now ✓", Modifier.padding(top = 8.dp), color = Mint) { sendQuick(ctx, "checkin", "I'm safe now", 0, true, "Your family knows you are safe") }
    }
}

/** On a parent's Home: someone's live SOS, with a way to see them on the map. */
@Composable
fun SosAlerts(nav: Nav) {
    val ctx = LocalContext.current
    val fam by ctx.hb.state.family.collectAsState()
    fam?.members.orEmpty().filter { it.sos && it.id != ctx.hb.store.myId }.forEach { m ->
        SoftCard(tint = Rose) {
            Text("🚨 ${m.name} sent an SOS", style = MaterialTheme.typography.titleLarge, color = Color.White)
            Text(if (m.location != null) "Live location · ${timeAgo(m.location.at)}" else "Waiting for a location…", color = Color.White)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Show on the map", false) { m.location?.let { ctx.hb.state.showOnMap.tryEmit(it) }; ctx.hb.state.tab.value = 1 }
                Pill("Call", false) { nav.go(Screen.Thread(m.id)) }
            }
        }
    }
}
