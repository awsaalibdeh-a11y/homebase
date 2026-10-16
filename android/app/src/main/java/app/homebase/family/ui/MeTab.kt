package app.homebase.family.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.homebase.family.hb
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import java.util.TimeZone

@Composable
fun MeTab(nav: Nav) {
    val ctx = LocalContext.current
    val app = ctx.hb
    val scope = app.scope
    val fam by app.state.family.collectAsState()
    val me = fam?.members?.firstOrNull { it.id == app.store.myId }
    var pause by remember { mutableStateOf(false) }
    var late by remember { mutableStateOf(false) }
    var statusOpen by remember { mutableStateOf(false) }
    var birthdayOpen by remember { mutableStateOf(false) }
    var quietFrom by remember { mutableIntStateOf(-1) }
    var quietTo by remember { mutableIntStateOf(-1) }
    LaunchedEffect(Unit) {
        runCatching { app.api.me() }.onSuccess { quietFrom = it.quiet.from; quietTo = it.quiet.to }
        runCatching { app.api.patchMe("tz_offset" to -(TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000)) }
    }
    fun setSharing(state: String, minutes: Int = 60) = scope.launch {
        runCatching { app.api.sharing(state, minutes) }.onSuccess { app.state.refreshFamily() }.onFailure { toast(ctx, it.message ?: "Could not change it") }
    }

        val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) scope.launch {
                runCatching {
                    val data = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    }
                    val src = BitmapFactory.decodeByteArray(data, 0, data.size) ?: error("That image could not be read.")
                    val s0 = minOf(src.width, src.height)
                    val sq = Bitmap.createBitmap(src, (src.width - s0) / 2, (src.height - s0) / 2, s0, s0)
                    val small = Bitmap.createScaledBitmap(sq, 256, 256, true)
                    val out = ByteArrayOutputStream(); small.compress(Bitmap.CompressFormat.JPEG, 82, out)
                    val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                    ctx.hb.api.setAvatar("data:image/jpeg;base64,$b64")
                }.onSuccess { app.state.refreshFamily(); Avatars.cache.clear(); toast(ctx, "Photo saved") }.onFailure { toast(ctx, it.message ?: "Could not save the photo") }
            }
        }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SetupBanner()
        Heading("Hi, ${me?.name ?: app.store.myName} ${me?.emoji ?: ""}", app.store.familyName)
        me?.let { meM ->
            SoftCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    MemberAvatar(meM, 64, if (meM.isParent) SunSoft else AccentSoft)
                    Column(Modifier.weight(1f)) { Text("Your photo", style = MaterialTheme.typography.titleMedium); Text("Shown on the map and in the family.", color = Muted) }
                }
                Row {
                    TextButton({ pickPhoto.launch("image/*") }) { Text(if (meM.avatar != null) "Change" else "Add a photo") }
                    if (meM.avatar != null) TextButton({ scope.launch { runCatching { ctx.hb.api.clearAvatar() }.onSuccess { app.state.refreshFamily(); toast(ctx, "Photo removed") } } }) { Text("Remove") }
                }
            }
        }

        val share = me?.share ?: "off"
        SoftCard(tint = when (share) { "on" -> MintSoft; "paused" -> SunSoft; else -> Surface }) {
            Text(when (share) { "on" -> "📍 Sharing your location"; "paused" -> "⏸ Sharing is paused"; else -> "Location sharing is off" }, style = MaterialTheme.typography.titleMedium)
            Text(when (share) {
                "on" -> if (app.store.isParent) "Your family can see where you are." else "Your family can see where you are. You can pause any time, and they will see that it is paused."
                "paused" -> "Nobody can see your location right now. Your family can see that it is paused."
                else -> "Nobody can see where you are."
            }, Modifier.padding(top = 4.dp), color = Muted)
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (share != "on") OutlinedButton({ setSharing("on") }) { Text("Share") }
                if (share == "on") OutlinedButton({ pause = true }) { Text("Pause…") }
                if (share != "off") OutlinedButton({ setSharing("off") }) { Text("Turn off") }
            }
        }

        SoftCard {
            val st = me?.status
            Text("🙂 What I'm up to", style = MaterialTheme.typography.titleMedium)
            Text(if (st != null) "${st.emoji} ${st.text}".trim() else "Tell your family in one tap. It goes away by itself.", Modifier.padding(top = 4.dp), color = Muted)
            OutlinedButton({ statusOpen = true }, Modifier.padding(top = 8.dp)) { Text(if (st != null) "Change" else "Set") }
        }
        SoftButton("🎂 My birthday" + (me?.birthday?.let { " · " + birthdayText(it) } ?: "")) { birthdayOpen = true }

        SectionLabel("Quick messages to the family")
        TwoUp({ BigButton("I'm OK ✓", color = Mint) { sendQuick(ctx, "checkin", "I'm OK", 0, true, "Checked in") } },
            { BigButton("I'm home 🏠", color = Mint) { sendQuick(ctx, "checkin", "I'm home", 0, true, "Checked in") } })
        TwoUp({ SoftButton("Running late ⏰") { late = true } }, { SoftButton("Send love ❤️") { sendQuick(ctx, "heart", "", 0, false, "❤️ sent") } })
        TwoUp({ SoftButton("Pick me up 🚗") { sendQuick(ctx, "pickup", "Can someone pick me up?", 0, true, "Sent to the family") } }, { SoftButton("Rewards 🎁") { nav.go(Screen.Rewards) } })
        SosButton { sendQuick(ctx, "sos", "I need help", 0, true, "SOS sent to your family") }

        SectionLabel("Quiet hours")
        SoftCard {
            Text(if (quietFrom < 0) "Off" else "${clockText(quietFrom)} to ${clockText(quietTo)}", style = MaterialTheme.typography.titleMedium)
            Text("Calls and messages stay quiet. An urgent call or SOS can still get through.", Modifier.padding(top = 2.dp), color = Muted)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({
                    TimePickerDialog(ctx, { _, h, m ->
                        val from = h * 60 + m
                        TimePickerDialog(ctx, { _, h2, m2 ->
                            val to = h2 * 60 + m2
                            scope.launch { runCatching { app.api.patchMe("quiet_from" to from, "quiet_to" to to) }.onSuccess { quietFrom = from; quietTo = to; app.state.refreshFamily() } }
                        }, 7, 0, false).apply { setTitle("Quiet hours end") }.show()
                    }, 21, 0, false).apply { setTitle("Quiet hours start") }.show()
                }) { Text(if (quietFrom < 0) "Set" else "Change") }
                if (quietFrom >= 0) OutlinedButton({ scope.launch { runCatching { app.api.patchMe("quiet_from" to -1, "quiet_to" to -1) }.onSuccess { quietFrom = -1; quietTo = -1; app.state.refreshFamily() } } }) { Text("Turn off") }
            }
        }

        if (!ctx.hb.store.isParent) { SectionLabel("Screen time"); ScreenTimeCard(nav) }

        SectionLabel("More")
        SoftButton("What my family can see") { nav.go(Screen.Transparency) }
        if (!ctx.hb.store.isParent) SoftButton("What parents can do") { nav.go(Screen.Perms) }
        SoftButton("Family list") { nav.go(Screen.Lists) }
        SoftButton("Saved places") { nav.go(Screen.Places) }
        SoftButton("How Homebase works") { nav.go(Screen.Tour) }
        SoftButton("Settings") { nav.go(Screen.Settings) }
        Gap(8)
    }
    if (statusOpen) StatusDialog { statusOpen = false }
    if (birthdayOpen) BirthdayDialog(me?.birthday) { birthdayOpen = false }
    if (pause) PickDialog("Pause sharing for…", listOf("15 minutes" to 15, "1 hour" to 60, "3 hours" to 180, "Until tomorrow" to 720), { setSharing("paused", it) }, { pause = false })
    if (late) PickDialog("How late will you be?", listOf("About 10 minutes" to 10, "About 20 minutes" to 20, "About 30 minutes" to 30, "About an hour" to 60), { sendQuick(ctx, "late", "Running about $it minutes late", 0, false, "Sent") }, { late = false })
}
