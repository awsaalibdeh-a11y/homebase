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

val Accent = Color(0xFFE6B866)
val AccentDark = Color(0xFFF3DDAA)
val AccentSoft = Color(0xFF2B2412)
val Mint = Color(0xFF4ADE94)
val MintSoft = Color(0xFF12261F)
val Sun = Color(0xFFF2B640)
val SunSoft = Color(0xFF2A2210)
val Rose = Color(0xFFFF7B80)
val RoseSoft = Color(0xFF2A1519)
val Ink = Color(0xFFF5F5F7)
val Muted = Color(0xFFAEAEB8)
val Bg = Color(0xFF0C0C0D)
val Surface = Color(0xFF18181B)
val Surface2 = Color(0xFF222227)
val OnAccent = Color(0xFF1B1306)

private val colors = darkColorScheme(
    primary = Accent, onPrimary = OnAccent, primaryContainer = AccentSoft, onPrimaryContainer = AccentDark,
    secondary = Mint, onSecondary = OnAccent, secondaryContainer = MintSoft, onSecondaryContainer = Mint,
    tertiary = Sun, onTertiary = OnAccent, error = Rose, onError = OnAccent, errorContainer = RoseSoft, onErrorContainer = Rose,
    background = Bg, onBackground = Ink, surface = Surface, onSurface = Ink, surfaceVariant = Surface2, onSurfaceVariant = Muted,
    outline = Color(0xFF47474E), outlineVariant = Color(0xFF2E2E34), surfaceContainer = Surface, surfaceContainerHigh = Surface2, surfaceContainerHighest = Surface2,
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
