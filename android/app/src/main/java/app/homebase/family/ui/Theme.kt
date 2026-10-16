package app.homebase.family.ui

import android.app.Activity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import app.homebase.family.R

/** Mellow has two looks on the same contour-line background: White (the main one) and Dark. Flipping [dark] repaints everything. */
object Look { var dark by mutableStateOf(false) }

private fun pick(light: Long, dark: Long): Color = Color(if (Look.dark) dark else light)

// Black and white, with warm paper greys in between. Red stays red for SOS and mistakes, so it is never missed.
val Accent: Color get() = pick(0xFF141414, 0xFFF2F2EF)
val AccentDark: Color get() = pick(0xFF000000, 0xFFFFFFFF)
val AccentSoft: Color get() = pick(0xFFE9E8E4, 0xFF2A2A29)
val Mint: Color get() = pick(0xFF2B2B2A, 0xFFDDDDD9)
val MintSoft: Color get() = pick(0xFFE6E5E1, 0xFF222221)
val Sun: Color get() = pick(0xFF5B5954, 0xFFC9C7C1)
val SunSoft: Color get() = pick(0xFFEFEEEA, 0xFF1F1F1E)
val Rose: Color get() = pick(0xFFD4303A, 0xFFE5484F)
val RoseSoft: Color get() = pick(0xFFF8E3E4, 0xFF3A1D1F)
val Ink: Color get() = pick(0xFF121212, 0xFFF2F2EF)
val Muted: Color get() = pick(0xFF6A6863, 0xFFA3A19C)
val Bg: Color get() = pick(0xFFFBFBF9, 0xFF050505)
val Surface: Color get() = pick(0xFFFFFFFF, 0xFF161616)
val Surface2: Color get() = pick(0xFFECEBE7, 0xFF272726)
val OnAccent: Color get() = pick(0xFFFFFFFF, 0xFF0B0B0B)
val Hairline: Color get() = pick(0x24000000, 0x2EFFFFFF)
val BarBg: Color get() = pick(0xFF111111, 0xFF1A1A1A)
val BarOn = Color(0xFFF4F4F1)
val BarOff = Color(0xFF9C9A95)

private fun scheme(dark: Boolean) = if (dark) darkColorScheme(
    primary = Accent, onPrimary = OnAccent, primaryContainer = AccentSoft, onPrimaryContainer = AccentDark,
    secondary = Mint, onSecondary = OnAccent, secondaryContainer = MintSoft, onSecondaryContainer = Mint,
    tertiary = Sun, onTertiary = OnAccent, error = Rose, onError = Color.White, errorContainer = RoseSoft, onErrorContainer = Rose,
    background = Bg, onBackground = Ink, surface = Surface, onSurface = Ink, surfaceVariant = Surface2, onSurfaceVariant = Muted,
    outline = Color(0xFF55554F), outlineVariant = Color(0xFF353533), surfaceContainer = Surface, surfaceContainerHigh = Surface, surfaceContainerHighest = Surface2,
) else lightColorScheme(
    primary = Accent, onPrimary = OnAccent, primaryContainer = AccentSoft, onPrimaryContainer = AccentDark,
    secondary = Mint, onSecondary = OnAccent, secondaryContainer = MintSoft, onSecondaryContainer = Mint,
    tertiary = Sun, onTertiary = OnAccent, error = Rose, onError = Color.White, errorContainer = RoseSoft, onErrorContainer = Rose,
    background = Bg, onBackground = Ink, surface = Surface, onSurface = Ink, surfaceVariant = Surface2, onSurfaceVariant = Muted,
    outline = Color(0xFFB9B7B1), outlineVariant = Color(0xFFDDDBD6), surfaceContainer = Surface, surfaceContainerHigh = Surface, surfaceContainerHighest = Surface2,
)

// Serif headlines on a plain sans body: calm and a little editorial, nothing like the old rounded look
private val type = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 34.sp, fontWeight = FontWeight.Medium, lineHeight = 38.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontSize = 28.sp, fontWeight = FontWeight.Medium, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 21.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
)

@Suppress("DEPRECATION")
private fun paintBars(window: android.view.Window, color: Int) {
    window.statusBarColor = color
    window.navigationBarColor = color
}

@Composable
fun HomebaseTheme(content: @Composable () -> Unit) {
    val dark = Look.dark
    val view = LocalView.current
    val barColor = Bg.toArgb()
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        paintBars(window, barColor)
        WindowCompat.getInsetsController(window, view).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark }
    }
    MaterialTheme(colorScheme = scheme(dark), typography = type, shapes = Shapes(small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(20.dp))) {
        // the contour-line picture sits under every screen; the white one is the main look
        Box(Modifier.fillMaxSize().background(Bg)) {
            Image(painterResource(if (dark) R.drawable.bg_dark else R.drawable.bg_light), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = Color.Transparent, contentColor = Ink, content = content)
        }
    }
}
