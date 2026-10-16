package app.homebase.family.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// White: neutral greys with one warm amber accent (no blue, no cream backgrounds)
val Accent = Color(0xFFA96A0E)
val AccentDark = Color(0xFF7A4A06)
val AccentSoft = Color(0xFFF5EDE2)
val Mint = Color(0xFF18A46A)
val MintSoft = Color(0xFFE2F4EB)
val Sun = Color(0xFFC98A09)
val SunSoft = Color(0xFFF8EFDC)
val Rose = Color(0xFFE0414A)
val RoseSoft = Color(0xFFFCE7E8)
val Ink = Color(0xFF17171A)
val Muted = Color(0xFF62626B)
val Bg = Color(0xFFF6F6F7)
val Surface = Color(0xFFFFFFFF)
val Surface2 = Color(0xFFEFEFF2)
val OnAccent = Color(0xFFFFFFFF)

private val colors = lightColorScheme(
    primary = Accent, onPrimary = OnAccent, primaryContainer = AccentSoft, onPrimaryContainer = AccentDark,
    secondary = Mint, onSecondary = OnAccent, secondaryContainer = MintSoft, onSecondaryContainer = Mint,
    tertiary = Sun, onTertiary = OnAccent, error = Rose, onError = OnAccent, errorContainer = RoseSoft, onErrorContainer = Rose,
    background = Bg, onBackground = Ink, surface = Surface, onSurface = Ink, surfaceVariant = Surface2, onSurfaceVariant = Muted,
    outline = Color(0xFFC9C9D0), outlineVariant = Color(0xFFE1E1E6), surfaceContainer = Surface, surfaceContainerHigh = Surface2, surfaceContainerHighest = Surface2,
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
        // everything sits on the white background with near-black text by default, so plain Text() is readable on every screen
        androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = Bg, contentColor = Ink, content = content)
    }
}
