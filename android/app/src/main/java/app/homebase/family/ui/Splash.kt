package app.homebase.family.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlinx.coroutines.delay

private fun seg(t: Float, a: Float, b: Float) = ((t - a) / (b - a)).coerceIn(0f, 1f)
private fun smooth(x: Float) = x * x * (3 - 2 * x)
private fun out(x: Float) = 1 - (1 - x) * (1 - x) * (1 - x)                         // fast start, gentle stop
private fun inOut(x: Float) = if (x < 0.5f) 4 * x * x * x else 1 - (-2 * x + 2).let { it * it * it } / 2

private val WARM = Color(0xFFF6C77A)
private val WARM_LIGHT = Color(0xFFFFE6B8)

/**
 * The opening animation, about five seconds (a tap skips it): a softly lit house, the door swings open on warm light,
 * we glide inside, someone sinks into the couch with an "ahh", and the name writes itself in.
 */
@Composable
fun SplashScreen(onDone: () -> Unit) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { p.animateTo(1f, tween(4600, easing = LinearEasing)); delay(350); onDone() }
    val t = p.value
    val dark = Look.dark
    val sky = if (dark) Color(0xFF0B0B0E) else Color(0xFFF4F2EE)
    val body = if (dark) Color(0xFF1D1D22) else Color(0xFFE4E0D8)
    val edge = if (dark) Color(0x33FFFFFF) else Color(0x22000000)
    val shade = if (dark) Color(0xFF26262C) else Color(0xFFD6D1C7)
    val person = if (dark) Color(0xFFB8B2A8) else Color(0xFF6E6A63)
    Box(Modifier.fillMaxSize().background(sky).clickable(remember { MutableInteractionSource() }, null) { onDone() }) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w / 2
            val ground = h * 0.62f
            val hw = w * 0.56f
            val wallTop = ground - hw * 0.62f
            val dw = hw * 0.2f
            val dh = hw * 0.36f
            val doorX = cx - dw / 2
            val doorTop = ground - dh
            val doorCenter = Offset(cx, doorTop + dh / 2)

            // ---- outside: the house rises out of the dark, the windows glow, the door opens
            val outside = 1f - seg(t, 0.50f, 0.60f)
            if (outside > 0f) {
                val rise = out(seg(t, 0f, 0.2f))
                val zoom = 1f + 9f * inOut(seg(t, 0.32f, 0.58f))
                withTransform({ scale(zoom, zoom, doorCenter); translate(0f, (1f - rise) * 40f) }) {
                    drawCircle(Brush.radialGradient(listOf(WARM.copy(alpha = 0.18f * rise * outside), Color.Transparent), Offset(cx, ground), w * 0.9f), w * 0.9f, Offset(cx, ground))
                    house(cx, ground, hw, wallTop, body.copy(alpha = rise * outside), edge.copy(alpha = edge.alpha * rise * outside), shade.copy(alpha = rise * outside))
                    // windows: warm, with a soft halo
                    val glow = smooth(seg(t, 0.08f, 0.22f)) * outside
                    listOf(cx - hw * 0.33f, cx + hw * 0.17f).forEach { x ->
                        val r = Size(hw * 0.16f, hw * 0.15f)
                        val o = Offset(x, wallTop + hw * 0.16f)
                        val mid = Offset(o.x + r.width / 2, o.y + r.height / 2)
                        drawCircle(Brush.radialGradient(listOf(WARM.copy(alpha = 0.35f * glow), Color.Transparent), mid, hw * 0.22f), hw * 0.22f, mid)
                        drawRoundRect(WARM.copy(alpha = 0.9f * glow), o, r, CornerRadius(6f))
                        drawLine(body.copy(alpha = glow), Offset(o.x + r.width / 2, o.y), Offset(o.x + r.width / 2, o.y + r.height), 4f)
                    }
                    // the doorway full of light, and the door swinging inwards in perspective
                    val open = inOut(seg(t, 0.14f, 0.36f))
                    drawRect(Brush.verticalGradient(listOf(WARM_LIGHT, WARM), doorTop, ground), Offset(doorX, doorTop), Size(dw, dh), alpha = rise * outside)
                    val a = open * (PI.toFloat() * 0.46f)
                    val far = doorX + dw * cos(a)
                    val tilt = dh * 0.10f * sin(a)
                    val door = Path().apply { moveTo(doorX, doorTop); lineTo(far, doorTop + tilt); lineTo(far, ground - tilt); lineTo(doorX, ground); close() }
                    drawPath(door, shade.copy(alpha = rise * outside))
                    drawPath(door, edge.copy(alpha = edge.alpha * rise * outside), style = Stroke(2f))
                    drawCircle(WARM.copy(alpha = (1f - open) * rise * outside), dw * 0.045f, Offset(doorX + (far - doorX) * 0.82f, doorTop + dh * 0.55f))
                    // light spilling down the path
                    val spill = Path().apply { moveTo(doorX, ground); lineTo(doorX + dw, ground); lineTo(doorX + dw * 2.4f, h); lineTo(doorX - dw * 1.4f, h); close() }
                    drawPath(spill, Brush.verticalGradient(listOf(WARM.copy(alpha = 0.32f * open * outside), Color.Transparent), ground, h))
                }
            }
            // ---- the warm light we walk through
            val flood = smooth(seg(t, 0.40f, 0.54f)) * (1f - smooth(seg(t, 0.56f, 0.68f)))
            if (flood > 0f) drawRect(WARM_LIGHT.copy(alpha = flood * 0.9f))

            // ---- inside: a lamp, a plant, the couch, and someone sinking into it
            val room = smooth(seg(t, 0.54f, 0.64f)) * (1f - smooth(seg(t, 0.84f, 0.96f)))
            if (room > 0f) {
                drawCircle(Brush.radialGradient(listOf(WARM.copy(alpha = 0.42f * room), Color.Transparent), Offset(w * 0.2f, h * 0.36f), w * 0.85f), w * 0.85f, Offset(w * 0.2f, h * 0.36f))
                val floor = h * 0.66f
                drawRect(shade.copy(alpha = 0.55f * room), Offset(0f, floor), Size(w, h - floor))
                drawOval(WARM.copy(alpha = 0.10f * room), Offset(w * 0.12f, floor + 10f), Size(w * 0.76f, h * 0.05f))
                // lamp
                drawLine(edge.copy(alpha = 0.9f * room), Offset(w * 0.15f, h * 0.38f), Offset(w * 0.15f, floor), 5f)
                val lamp = Path().apply { moveTo(w * 0.09f, h * 0.38f); lineTo(w * 0.21f, h * 0.38f); lineTo(w * 0.185f, h * 0.33f); lineTo(w * 0.115f, h * 0.33f); close() }
                drawPath(lamp, WARM.copy(alpha = room))
                // plant
                drawRoundRect(shade.copy(alpha = room), Offset(w * 0.82f, floor - h * 0.05f), Size(w * 0.08f, h * 0.05f), CornerRadius(8f))
                repeat(5) { i ->
                    val ang = -PI.toFloat() / 2 + (i - 2) * 0.38f
                    drawLine(Color(0xFF6E8B5E).copy(alpha = room), Offset(w * 0.86f, floor - h * 0.05f), Offset(w * 0.86f + cos(ang) * w * 0.07f, floor - h * 0.05f + sin(ang) * w * 0.09f), 6f)
                }
                // the couch
                val cw = w * 0.6f
                val ch = cw * 0.4f
                val cl = cx - cw / 2
                val ct = floor - ch
                drawRoundRect(body.copy(alpha = room), Offset(cl, ct), Size(cw, ch * 0.66f), CornerRadius(40f))
                drawRoundRect(body.copy(alpha = room), Offset(cl - cw * 0.05f, ct + ch * 0.3f), Size(cw * 0.15f, ch * 0.66f), CornerRadius(30f))
                drawRoundRect(body.copy(alpha = room), Offset(cl + cw * 0.9f, ct + ch * 0.3f), Size(cw * 0.15f, ch * 0.66f), CornerRadius(30f))
                // the person lands, the cushion gives a little, and they settle with a tiny bounce
                val land = seg(t, 0.60f, 0.72f)
                val fall = out(land)
                val settle = if (land >= 1f) exp(-(t - 0.72f) * 40f) * sin((t - 0.72f) * 90f) * 10f else 0f
                val squash = if (land >= 1f) exp(-(t - 0.72f) * 30f) * 0.12f else 0f
                val seatY = ct + ch * 0.5f
                drawRoundRect(shade.copy(alpha = room), Offset(cl + cw * 0.06f, seatY + squash * ch), Size(cw * 0.88f, ch * 0.32f), CornerRadius(26f))
                drawRoundRect(WARM.copy(alpha = 0.95f * room), Offset(cl + cw * 0.08f, ct + ch * 0.2f), Size(cw * 0.17f, ch * 0.3f), CornerRadius(20f))
                val px = cl + cw * 0.6f
                val py = seatY - (1f - fall) * h * 0.2f + settle + squash * ch
                drawRoundRect(person.copy(alpha = room), Offset(px - cw * 0.085f, py - ch * 0.55f), Size(cw * 0.17f, ch * 0.57f), CornerRadius(34f))
                drawCircle(person.copy(alpha = room), cw * 0.07f, Offset(px, py - ch * 0.7f))
                val legs = smooth(seg(t, 0.72f, 0.8f))
                drawRoundRect(person.copy(alpha = room), Offset(px - cw * 0.02f, py - ch * 0.04f), Size(cw * 0.06f + cw * 0.2f * legs, ch * 0.12f), CornerRadius(16f))
            }
        }
        // "ahh", drifting up as they settle
        val sigh = seg(t, 0.72f, 0.86f)
        if (sigh > 0f && sigh < 1f) Text("ahh", Modifier.align(Alignment.Center).graphicsLayer { alpha = sin(sigh * PI.toFloat()); translationX = 150f; translationY = -150f - sigh * 70f },
            fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontSize = 30.sp, color = WARM)
        // the name, letter by letter
        val name = seg(t, 0.82f, 1f)
        if (name > 0f) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Row {
                "Mellow".forEachIndexed { i, c ->
                    val k = smooth(seg(name, i * 0.09f, i * 0.09f + 0.4f))
                    Text(c.toString(), Modifier.graphicsLayer { alpha = k; translationY = (1f - k) * 30f }, fontFamily = FontFamily.Cursive, fontSize = 70.sp, color = Ink)
                }
            }
            Text("Stay close. Stay yourself.", Modifier.padding(top = 8.dp).graphicsLayer { alpha = smooth(seg(name, 0.55f, 1f)) }, color = Muted, fontSize = 16.sp)
        }
    }
}

/** A simple house: walls, a roof, a chimney and a soft outline. */
private fun DrawScope.house(cx: Float, ground: Float, hw: Float, wallTop: Float, body: Color, edge: Color, shade: Color) {
    val left = cx - hw / 2
    drawRoundRect(body, Offset(left, wallTop), Size(hw, ground - wallTop), CornerRadius(10f))
    drawRect(shade, Offset(cx + hw * 0.2f, wallTop - hw * 0.34f), Size(hw * 0.09f, hw * 0.22f))
    val roof = Path().apply { moveTo(left - hw * 0.08f, wallTop + 6f); lineTo(cx, wallTop - hw * 0.4f); lineTo(left + hw * 1.08f, wallTop + 6f); close() }
    drawPath(roof, shade)
    drawPath(roof, edge, style = Stroke(3f))
    drawRoundRect(edge, Offset(left, wallTop), Size(hw, ground - wallTop), CornerRadius(10f), style = Stroke(2f))
    drawLine(edge, Offset(left - hw * 0.4f, ground), Offset(left + hw * 1.4f, ground), 2f)
}
