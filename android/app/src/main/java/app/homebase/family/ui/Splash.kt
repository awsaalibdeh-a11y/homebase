package app.homebase.family.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private fun seg(t: Float, a: Float, b: Float) = ((t - a) / (b - a)).coerceIn(0f, 1f)
private fun ease(x: Float) = x * x * (3 - 2 * x)

/**
 * The opening animation: a house, its door swings open on a warm light, we walk in, someone flops onto the couch,
 * and it all melts into "Mellow". About four seconds; a tap skips it.
 */
@Composable
fun SplashScreen(onDone: () -> Unit) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { p.animateTo(1f, tween(4000, easing = LinearEasing)); delay(300); onDone() }
    val t = p.value
    val ink = Ink
    val soft = Muted
    val warm = Color(0xFFF3C877)
    Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { onDone() }) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w / 2
            val cy = h * 0.46f
            // ---- 1. the house, and the door opening (0 to 0.66, zooming into the doorway from 0.30)
            val doorScene = 1f - seg(t, 0.56f, 0.68f)
            if (doorScene > 0f) {
                val zoom = 1f + 5f * ease(seg(t, 0.30f, 0.66f))
                withTransform({ scale(zoom, zoom, Offset(cx, cy + w * 0.05f)) }) {
                    val hw = w * 0.62f
                    val wallTop = cy - hw * 0.35f
                    val wallBottom = cy + hw * 0.62f
                    val left = cx - hw / 2
                    val draw = ease(seg(t, 0f, 0.16f))                                         // the outline draws itself in
                    val line = Stroke(width = 5f)
                    drawRect(ink.copy(alpha = doorScene * draw), Offset(left, wallTop), Size(hw, wallBottom - wallTop), style = line)
                    val roof = Path().apply { moveTo(left - hw * 0.08f, wallTop); lineTo(cx, wallTop - hw * 0.42f); lineTo(left + hw * 1.08f, wallTop) }
                    drawPath(roof, ink.copy(alpha = doorScene * draw), style = Stroke(width = 6f))
                    drawRect(ink.copy(alpha = doorScene * draw * 0.9f), Offset(cx + hw * 0.18f, wallTop - hw * 0.36f), Size(hw * 0.08f, hw * 0.2f))     // chimney
                    // windows with a soft light
                    listOf(cx - hw * 0.34f, cx + hw * 0.18f).forEach { x ->
                        drawRect(warm.copy(alpha = doorScene * draw * 0.55f), Offset(x, wallTop + hw * 0.12f), Size(hw * 0.16f, hw * 0.16f))
                        drawRect(ink.copy(alpha = doorScene * draw), Offset(x, wallTop + hw * 0.12f), Size(hw * 0.16f, hw * 0.16f), style = Stroke(width = 4f))
                    }
                    // the door
                    val dw = hw * 0.22f
                    val dh = hw * 0.42f
                    val dl = cx - dw / 2
                    val dt = wallBottom - dh
                    drawRect(Brush.verticalGradient(listOf(warm, Color(0xFFFFE7B3)), dt, dt + dh), Offset(dl, dt), Size(dw, dh), alpha = doorScene)
                    val open = ease(seg(t, 0.12f, 0.34f))
                    val pw = dw * (1f - 0.88f * open)
                    drawRect(ink.copy(alpha = doorScene), Offset(dl, dt), Size(pw, dh))
                    drawCircle(warm.copy(alpha = doorScene), radius = dw * 0.05f, center = Offset(dl + pw - dw * 0.14f * (1f - open), dt + dh * 0.55f))
                    drawRect(ink.copy(alpha = doorScene), Offset(dl - 4f, dt - 4f), Size(dw + 8f, dh + 4f), style = Stroke(width = 4f))
                    // light spilling out onto the path
                    val spill = Path().apply { moveTo(dl, wallBottom); lineTo(dl + dw, wallBottom); lineTo(dl + dw * 1.9f, wallBottom + hw * 0.4f); lineTo(dl - dw * 0.9f, wallBottom + hw * 0.4f); close() }
                    drawPath(spill, warm.copy(alpha = doorScene * open * 0.35f))
                }
            }
            // ---- 2. the living room: a lamp, the couch, and someone sitting down with a sigh (0.56 to 0.9)
            val room = seg(t, 0.56f, 0.68f) * (1f - seg(t, 0.84f, 0.95f))
            if (room > 0f) {
                drawCircle(Brush.radialGradient(listOf(warm.copy(alpha = 0.55f * room), Color.Transparent), Offset(w * 0.22f, h * 0.28f), w * 0.8f), w * 0.8f, Offset(w * 0.22f, h * 0.28f))
                // lamp
                drawLine(ink.copy(alpha = room), Offset(w * 0.16f, h * 0.30f), Offset(w * 0.16f, cy + w * 0.2f), strokeWidth = 5f)
                val shade = Path().apply { moveTo(w * 0.10f, h * 0.30f); lineTo(w * 0.22f, h * 0.30f); lineTo(w * 0.19f, h * 0.25f); lineTo(w * 0.13f, h * 0.25f); close() }
                drawPath(shade, ink.copy(alpha = room))
                // couch
                val cw = w * 0.62f
                val ch = cw * 0.42f
                val cl = cx - cw / 2 + w * 0.06f
                val ct = cy
                drawRoundRect(ink.copy(alpha = room), Offset(cl, ct), Size(cw, ch * 0.62f), CornerRadius(34f))
                drawRoundRect(soft.copy(alpha = room), Offset(cl + cw * 0.06f, ct + ch * 0.44f), Size(cw * 0.88f, ch * 0.34f), CornerRadius(22f))
                drawRoundRect(ink.copy(alpha = room), Offset(cl - cw * 0.05f, ct + ch * 0.26f), Size(cw * 0.15f, ch * 0.66f), CornerRadius(28f))
                drawRoundRect(ink.copy(alpha = room), Offset(cl + cw * 0.9f, ct + ch * 0.26f), Size(cw * 0.15f, ch * 0.66f), CornerRadius(28f))
                drawRect(ink.copy(alpha = room), Offset(cl + cw * 0.04f, ct + ch * 0.9f), Size(cw * 0.05f, ch * 0.14f))
                drawRect(ink.copy(alpha = room), Offset(cl + cw * 0.91f, ct + ch * 0.9f), Size(cw * 0.05f, ch * 0.14f))
                // a cushion and the person dropping onto the seat
                drawRoundRect(warm.copy(alpha = room * 0.9f), Offset(cl + cw * 0.08f, ct + ch * 0.18f), Size(cw * 0.18f, ch * 0.3f), CornerRadius(20f))
                val sit = ease(seg(t, 0.62f, 0.8f))
                val bounce = if (sit >= 1f) 0f else kotlin.math.sin(sit * Math.PI.toFloat()) * 0f
                val px = cl + cw * 0.58f
                val seatY = ct + ch * 0.46f
                val py = seatY - (1f - sit) * h * 0.22f + bounce
                drawRoundRect(soft.copy(alpha = room), Offset(px - cw * 0.09f, py - ch * 0.5f), Size(cw * 0.18f, ch * 0.52f), CornerRadius(30f))      // body
                drawCircle(soft.copy(alpha = room), radius = cw * 0.075f, center = Offset(px, py - ch * 0.64f))                                     // head
                drawRoundRect(soft.copy(alpha = room), Offset(px - cw * 0.02f, py - ch * 0.02f), Size(cw * 0.26f * sit + cw * 0.04f, ch * 0.12f), CornerRadius(14f))   // legs stretch out
            }
        }
        // ---- 3. "Mellow", in a relaxed hand
        val title = seg(t, 0.8f, 0.97f)
        Column(Modifier.align(Alignment.Center).graphicsLayer { alpha = title; translationY = (1f - title) * 60f }, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Mellow", fontFamily = FontFamily.Cursive, fontSize = 72.sp, color = Ink, letterSpacing = (10f * (1f - title)).sp)
            Text("Stay close. Stay yourself.", Modifier.padding(top = 6.dp), color = Muted, fontSize = 16.sp)
        }
    }
}
