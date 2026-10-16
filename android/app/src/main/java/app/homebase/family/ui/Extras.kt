package app.homebase.family.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.homebase.family.FeedItem
import app.homebase.family.hb

private fun feedIcon(it: FeedItem): String = when {
    it.kind == "sos" -> "🚨"
    it.kind == "late" -> "⏰"
    it.kind == "checkin" -> "✅"
    it.kind == "pickup" -> "🚗"
    Regex("arrived|left").containsMatchIn(it.body) -> "📍"
    Regex("job|approved|finished").containsMatchIn(it.body) -> "✅"
    it.body.contains("reward") -> "🎁"
    it.body.contains("plan") -> "📅"
    Regex("joined|linked").containsMatchIn(it.body) -> "👋"
    else -> "•"
}

/** What has been happening in the family: arrivals, jobs, rewards, plans, check-ins. */
@Composable
fun FeedScreen(nav: Nav) {
    val ctx = LocalContext.current
    val items by ctx.hb.state.feed.collectAsState()
    LaunchedEffect(Unit) { ctx.hb.state.refreshFeed() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { TextButton({ nav.back() }) { Text("Back") } }
        Heading("Activity", "What has been happening in the family.")
        items.forEach { it ->
            SoftCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(feedIcon(it), fontSize = 22.sp)
                    Column(Modifier.weight(1f)) {
                        Text(it.body, style = MaterialTheme.typography.bodyLarge)
                        Text(timeAgo(it.at), color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (items.isEmpty()) CenterNote("Nothing has happened yet.")
    }
}

private class TutorialStep(val emoji: String, val title: String, val body: String)

// The steps of the Homebase tutorial, worded for the phone (the bottom bar instead of the left panel; family games instead of linked apps).
private val TUTORIAL = listOf(
    TutorialStep("🏠", "Welcome to Mellow", "Your family on a map, with messages and calls. It is honest about everything it shares, and you can always pause. This takes a minute."),
    TutorialStep("🧭", "Five sections", "The bar at the bottom has Home, Map, Plan, Chat and More. Home is today at a glance. Map is your people and places. Plan has the calendar and the meals. Chat has the family chat and friend groups. More has rewards, jobs, the family board, rules and settings."),
    TutorialStep("🗺️", "The map", "Everyone who is sharing shows up as a pin, parents too. If someone goes offline you still see where they were last, and how long ago."),
    TutorialStep("📍", "Share my location", "In More, the sharing card is yours. You can pause for 15 minutes or an hour. The family always sees when it is paused, so nobody wonders."),
    TutorialStep("💬", "Messages and calls", "In Map, tap a person to Message, Call, or ask Where are you?. They can always say not now. In a chat you can send photos, stickers and GIFs, and a call can have video."),
    TutorialStep("✅", "Check in", "Home has I'm OK and Running late, and More has SOS. Hold SOS for two seconds in an emergency and everyone is told with your location."),
    TutorialStep("🏡", "Places and Home", "Set the Home area and add places like school. Everyone is told when someone arrives or leaves, including the person it is about."),
    TutorialStep("⏱️", "Screen time, rules and locks", "Children can see their own screen time (only time spent playing or watching). Parents can read it if the child allows, set the family rules, and lock a device for a while, never more than 4 hours a day."),
    TutorialStep("📅", "Calendar and meals", "In Plan, add things to the family calendar and say who is in. Plan a meal for any day and say who is cooking: the recipe comes with it. A plan with a place has a Directions button."),
    TutorialStep("🎞️", "Stickers and friend groups", "Send stickers and GIFs in chat. Friend groups are separate from the family: friends can chat, but mild words only. Swearing and insults are censored, and a child's parents can read the group."),
    TutorialStep("🎮", "Family games", "In the family chat, tap + and choose Start a family game. Alibi opens, and everyone gets a Join the game button in the chat."),
    TutorialStep("🔒", "Everyone is in control", "Open More to switch off what parents can do, set quiet hours, or change the look. Whatever is off, the family can see that it is off. The Tutorial button in More shows this again."),
)

/** A simple step-by-step dialog. Back, Next, and Done on the last step. */
@Composable
fun TutorialDialog(onDismiss: () -> Unit) {
    var i by remember { mutableIntStateOf(0) }
    val step = TUTORIAL[i]
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text("${step.emoji}  ${step.title}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(step.body, style = MaterialTheme.typography.bodyLarge)
                Text("${i + 1} of ${TUTORIAL.size}", Modifier.padding(top = 12.dp), color = Muted, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton({ if (i == TUTORIAL.lastIndex) onDismiss() else i++ }) { Text(if (i == TUTORIAL.lastIndex) "Done" else "Next") } },
        dismissButton = { if (i > 0) TextButton({ i-- }) { Text("Back") } else TextButton(onDismiss) { Text("Close") } },
    )
}
