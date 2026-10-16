package app.homebase.family.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.homebase.family.hb
import kotlinx.coroutines.launch

private data class Step(val emoji: String, val title: String, val body: String)

private val PARENT_STEPS = listOf(
    Step("🏠", "Welcome to Homebase", "Stay close, and stay honest. Here is how it works, in under a minute."),
    Step("🗺️", "See everyone on the map", "People who are sharing appear on the map, you included. If a phone goes offline you still see where it was last, and how long ago."),
    Step("👪", "Message, call, or ask", "In Family, tap Message or Call (calls really ring). \"Where?\" asks someone to share their location right now, and they can always say not now."),
    Step("✅", "Quick check-ins", "In Me: \"I'm OK\", \"I'm home\", \"Running late\", and an SOS you hold for two seconds. They reach the whole family at once."),
    Step("🏫", "Saved places", "Add places like school. Everyone gets a message when someone arrives or leaves, including the person who arrived."),
    Step("🔒", "Everyone is in control", "Anyone can pause sharing or set quiet hours, and children choose what parents can do. If something is switched off, you will see exactly what, so nobody is guessing."),
    Step("💻", "On a laptop too", "Open homebase-family.onrender.com/app in a browser to use Homebase on a computer. It even works offline."),
)
private val CHILD_STEPS = listOf(
    Step("🏠", "Welcome to Homebase", "A family app that is honest about everything it shares. Here is how it works."),
    Step("👀", "What your family sees", "Where you are while sharing is on, your battery, and the messages you send. Nothing else, and nothing in secret."),
    Step("⏸️", "You can pause", "Pause sharing any time, or set quiet hours. Your family sees that it is paused, so nobody worries."),
    Step("🎛️", "You choose what parents can do", "In Me, open What parents can do. Switch off seeing your location, calls, messages and more. Your parents see what is off."),
    Step("✅", "Let them know you are OK", "\"I'm OK\", \"I'm home\", \"Running late\", and a hold-for-two-seconds SOS are one tap away in Me."),
    Step("📞", "Messages and calls", "Chat with the family and call them any time. \"Where are you?\" is only a request: you can answer or say not now."),
)

/** A short walkthrough, different for parents and children. Shown once, and again from Me > How Homebase works. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TourScreen(nav: Nav) {
    val ctx = LocalContext.current
    val steps = remember { if (ctx.hb.store.isParent) PARENT_STEPS else CHILD_STEPS }
    val pager = rememberPagerState(pageCount = { steps.size })
    val scope = rememberCoroutineScope()
    fun done() { ctx.hb.store.tourDone = true; if (nav.stack.size > 1) nav.back() else nav.reset(Screen.Main) }
    BackHandler { done() }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton({ done() }) { Text("Skip") } }
        HorizontalPager(pager, Modifier.weight(1f)) { i ->
            val s = steps[i]
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(128.dp).clip(CircleShape).background(AccentSoft), contentAlignment = Alignment.Center) { Text(s.emoji, fontSize = 60.sp) }
                Spacer(Modifier.height(32.dp))
                Text(s.title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(14.dp))
                Text(s.body, style = MaterialTheme.typography.bodyLarge, color = Muted, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalArrangement = Arrangement.Center) {
            repeat(steps.size) { i ->
                val on = pager.currentPage == i
                Box(Modifier.padding(horizontal = 4.dp).height(8.dp).width(if (on) 24.dp else 8.dp).clip(CircleShape).background(if (on) Accent else Surface2))
            }
        }
        Button({ if (pager.currentPage == steps.lastIndex) done() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }, Modifier.fillMaxWidth().height(54.dp)) {
            Text(if (pager.currentPage == steps.lastIndex) "Got it" else "Next", fontSize = 17.sp)
        }
    }
}
