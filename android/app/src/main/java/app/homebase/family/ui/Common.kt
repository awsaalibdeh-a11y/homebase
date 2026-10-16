package app.homebase.family.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

fun timeAgo(epochSeconds: Double): String {
    if (epochSeconds <= 0) return "not yet"
    val s = (System.currentTimeMillis() / 1000.0 - epochSeconds).toLong()
    return when {
        s < 90 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86400 -> "${s / 3600} h ago"
        else -> "${s / 86400} d ago"
    }
}

fun clockText(minutes: Int): String {
    if (minutes < 0) return "Off"
    val h = minutes / 60
    val m = minutes % 60
    return "%d:%02d %s".format(if (h % 12 == 0) 12 else h % 12, m, if (h < 12) "am" else "pm")
}

@Composable
fun Avatar(emoji: String, size: Int = 44, tint: Color = AccentSoft) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.3f).dp)).background(tint), contentAlignment = Alignment.Center) { Text(emoji, fontSize = (size * 0.5).sp) }
}

@Composable
fun SoftCard(modifier: Modifier = Modifier, tint: Color = Surface, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val m = if (onClick != null) modifier.clickable { onClick() } else modifier
    Card(m.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = tint.copy(alpha = 0.93f)), border = BorderStroke(1.dp, Hairline), elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
fun BigButton(text: String, modifier: Modifier = Modifier, color: Color = Accent, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick, modifier.fillMaxWidth().height(52.dp), enabled = enabled, shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = if (color == Rose) Color.White else OnAccent)) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SoftButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick, modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Ink.copy(alpha = 0.28f)),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Surface.copy(alpha = 0.55f), contentColor = Ink)) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun Heading(title: String, sub: String? = null) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        if (sub != null) Text(sub, style = MaterialTheme.typography.bodyLarge, color = Muted, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun Chip(text: String, tint: Color = AccentSoft, ink: Color = AccentDark) {
    Text(text, Modifier.clip(RoundedCornerShape(8.dp)).background(tint).padding(horizontal = 10.dp, vertical = 4.dp), color = ink, fontSize = 12.sp)
}

@Composable
fun Gap(h: Int = 12) { Spacer(Modifier.height(h.dp)) }

@Composable
fun CenterNote(text: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Text(text, color = Muted, textAlign = TextAlign.Center) }
}

@Composable
fun RowSpread(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) { content() }
}
