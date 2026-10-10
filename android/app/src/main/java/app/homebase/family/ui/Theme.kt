package app.homebase.family.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Blue = Color(0xFF5B93FF)
val BlueDark = Color(0xFFB8D0FF)
val BlueSoft = Color(0xFF1A2438)
val Mint = Color(0xFF3DD49B)
val MintSoft = Color(0xFF12261F)
val Sun = Color(0xFFF0B429)
val SunSoft = Color(0xFF2A2210)
val Rose = Color(0xFFFF6B74)
val RoseSoft = Color(0xFF2A1519)
val Ink = Color(0xFFECECF0)
val Muted = Color(0xFF8D8D96)
val Bg = Color(0xFF0A0A0B)
val Surface = Color(0xFF121214)
val Surface2 = Color(0xFF1B1B1F)
val OnAccent = Color(0xFF08101F)

private val colors = darkColorScheme(
    primary = Blue, onPrimary = OnAccent, primaryContainer = BlueSoft, onPrimaryContainer = BlueDark,
    secondary = Mint, onSecondary = OnAccent, secondaryContainer = MintSoft, onSecondaryContainer = Mint,
    tertiary = Sun, onTertiary = OnAccent, error = Rose, onError = OnAccent, errorContainer = RoseSoft, onErrorContainer = Rose,
    background = Bg, onBackground = Ink, surface = Surface, onSurface = Ink, surfaceVariant = Surface2, onSurfaceVariant = Muted,
    outline = Color(0xFF3A3A40), outlineVariant = Color(0xFF26262B), surfaceContainer = Surface, surfaceContainerHigh = Surface2, surfaceContainerHighest = Surface2,
)

private val type = Typography(
    headlineLarge = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun HomebaseTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = type, shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(26.dp))) {
        // everything sits on the matte background with silver text by default, so plain Text() is readable on every screen
        androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = Bg, contentColor = Ink, content = content)
    }
}
