package app.homebase.family.ui

import androidx.compose.foundation.shape.RoundedCornerShape
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

val Blue = Color(0xFF2F7DF6)
val BlueDark = Color(0xFF1B57C4)
val BlueSoft = Color(0xFFE3EEFF)
val Mint = Color(0xFF1FB88A)
val MintSoft = Color(0xFFDDF6EC)
val Sun = Color(0xFFFFC857)
val SunSoft = Color(0xFFFFF3D6)
val Rose = Color(0xFFF2557A)
val RoseSoft = Color(0xFFFFE3EA)
val Ink = Color(0xFF16213A)
val Muted = Color(0xFF5F6B86)
val Bg = Color(0xFFF2F7FF)

private val colors = lightColorScheme(
    primary = Blue, onPrimary = Color.White, primaryContainer = BlueSoft, onPrimaryContainer = BlueDark,
    secondary = Mint, onSecondary = Color.White, secondaryContainer = MintSoft,
    tertiary = Sun, error = Rose, errorContainer = RoseSoft,
    background = Bg, onBackground = Ink, surface = Color.White, onSurface = Ink, surfaceVariant = Color(0xFFE9EEF8), onSurfaceVariant = Muted,
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
    MaterialTheme(colorScheme = colors, typography = type, shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(26.dp)), content = content)
}
