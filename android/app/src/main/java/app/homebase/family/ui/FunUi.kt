package app.homebase.family.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.homebase.family.hb
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val WOULD_YOU_RATHER = listOf(
    "Be able to fly, or be invisible?", "Always have pizza, or always have tacos?", "Have a pet dragon, or a pet unicorn?", "Live by the sea, or in the mountains?",
    "Never have homework, or never have chores?", "Talk to animals, or speak every language?", "Have a rewind button, or a pause button for life?", "Be a famous singer, or a famous chef?",
    "Eat breakfast for every meal, or never eat breakfast again?", "Have a treehouse, or a secret room?", "Be super fast, or super strong?", "Always be 10 minutes early, or 10 minutes late?",
    "Have a robot helper, or a time machine?", "Camp in the woods, or stay in a fancy hotel?", "Only whisper, or only shout?", "Have ice cream rain, or chocolate snow?",
    "Explore space, or explore the deep sea?", "Have a super nose, or super ears?", "Dance everywhere you go, or sing everything you say?", "Invent a new colour, or a new food?",
    "Be the smallest in a giant world, or the biggest in a tiny one?", "Have a free movie night every week, or a free game night?", "Lose your left sock forever, or your right one?", "Cook the family dinner, or choose the dessert?",
)
private val COMPLIMENTS = listOf(
    "You make our home better just by being in it ❤️", "Thanks for being you. You are great at this family thing 🌟", "You always know how to make me smile 😊", "I am proud of you today 🏅",
    "You are kind, and I notice 💛", "Best teammate in the family 🙌", "You made my day a little brighter ☀️", "Thank you for helping. It really mattered 🙏",
    "You are really good at making people laugh 😂", "I love how curious you are 🔭", "You are brave, even when it is hard 🦁", "Having you here makes everything more fun 🎉",
)

/** Little games and tools for the family. Everything here works with no signal, except sending a compliment. */
@Composable
fun FunScreen(nav: Nav) {
    val ctx = LocalContext.current
    val fam by ctx.hb.state.family.collectAsState()
    val me = ctx.hb.store.myId
    val scope = rememberCoroutineScope()
    val people = fam?.members.orEmpty()
    var turn by remember { mutableStateOf("Tap Spin") }
    var spinning by remember { mutableStateOf(false) }
    var coin by remember { mutableStateOf("🪙") }
    var dice by remember { mutableStateOf("🎲  –") }
    var rather by remember { mutableIntStateOf((0 until WOULD_YOU_RATHER.size).random()) }
    var target by remember { mutableIntStateOf(people.firstOrNull { it.id != me }?.id ?: 0) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { TextButton({ nav.back() }) { Text("Back") } }
        Heading("Fun", "Little games for the family. No signal needed.")
        SoftCard(tint = AccentSoft) {
            Text("🎡 Whose turn is it?", style = MaterialTheme.typography.titleMedium)
            Text("Dishes, the front seat, picking the film.", color = Muted)
            Text(turn, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.headlineMedium)
            BigButton(if (spinning) "Spinning…" else "Spin", enabled = !spinning && people.isNotEmpty()) {
                spinning = true
                scope.launch {
                    repeat(18) { turn = people.random().let { "${it.emoji} ${it.name}" }; delay(70L + it * 8) }
                    turn = people.random().let { "${it.emoji} ${it.name}" }
                    buzz(ctx)
                    spinning = false
                }
            }
        }
        TwoUp({
            SoftCard {
                Text("🪙 Coin", style = MaterialTheme.typography.titleMedium)
                Text(coin, Modifier.padding(vertical = 6.dp), fontSize = 28.sp)
                SoftButton("Flip") { scope.launch { repeat(8) { coin = listOf("Heads", "Tails").random(); delay(80) }; coin = listOf("Heads", "Tails").random(); buzz(ctx) } }
            }
        }, {
            SoftCard {
                Text("🎲 Dice", style = MaterialTheme.typography.titleMedium)
                Text(dice, Modifier.padding(vertical = 6.dp), fontSize = 28.sp)
                SoftButton("Roll") { scope.launch { repeat(8) { dice = "🎲  ${(1..6).random()}  ${(1..6).random()}"; delay(80) }; dice = "🎲  ${(1..6).random()}  ${(1..6).random()}"; buzz(ctx) } }
            }
        })
        SoftCard {
            Text("🤔 Would you rather", style = MaterialTheme.typography.titleMedium)
            Text(WOULD_YOU_RATHER[rather], Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyLarge)
            SoftButton("Next question") { rather = (rather + 1 + (0 until WOULD_YOU_RATHER.size - 1).random()) % WOULD_YOU_RATHER.size }
        }
        SoftCard {
            Text("💌 Send a compliment", style = MaterialTheme.typography.titleMedium)
            Text("A random kind message to someone in the family.", color = Muted)
            Row(Modifier.padding(vertical = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                people.filter { it.id != me }.forEach { p -> Pill("${p.emoji} ${p.name}", target == p.id) { target = p.id } }
            }
            BigButton("Send one", enabled = target != 0) { sendQuick(ctx, "text", COMPLIMENTS.random(), target, false, "Sent 💌") }
        }
        SoftButton("🍽️ Surprise dinner: random recipes") { nav.go(Screen.Recipes(true)) }
        Gap(8)
    }
}
