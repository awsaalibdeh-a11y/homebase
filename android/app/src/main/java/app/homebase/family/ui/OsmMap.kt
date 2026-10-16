package app.homebase.family.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import app.homebase.family.HistoryPoint
import app.homebase.family.Member
import app.homebase.family.Place
import app.homebase.family.Spot
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline

private fun bubble(ctx: Context, emoji: String, name: String, mine: Boolean, stale: Boolean): BitmapDrawable {
    val d = ctx.resources.displayMetrics.density
    val w = (96 * d).toInt()
    val h = (92 * d).toInt()
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val cx = w / 2f
    p.color = if (mine) 0xFF1FB88A.toInt() else 0xFFE6B866.toInt()
    p.alpha = if (stale) 150 else 255
    c.drawCircle(cx, 34 * d, 30 * d, p)
    p.color = 0xFFFFFFFF.toInt()
    c.drawCircle(cx, 34 * d, 26 * d, p)
    p.textAlign = Paint.Align.CENTER
    p.textSize = 30 * d
    c.drawText(emoji, cx, 45 * d, p)
    p.textSize = 13 * d
    p.isFakeBoldText = true
    p.color = 0xFF16213A.toInt()
    c.drawText(if (name.length > 10) name.take(9) + "…" else name, cx, 82 * d, p)
    return BitmapDrawable(ctx.resources, bmp)
}

/** The family on an OpenStreetMap. In pick mode a tap chooses a spot, for adding a place. */
@Composable
fun OsmMap(
    modifier: Modifier,
    members: List<Member>,
    myId: Int,
    places: List<Place> = emptyList(),
    trail: List<HistoryPoint> = emptyList(),
    focus: Spot? = null,
    pick: ((Double, Double) -> Unit)? = null,
    picked: Pair<Double, Double>? = null,
) {
    val ctx = LocalContext.current
    val fitted = remember { booleanArrayOf(false) }
    val map = remember {
        Configuration.getInstance().userAgentValue = ctx.packageName
        Configuration.getInstance().load(ctx, ctx.getSharedPreferences("osm", Context.MODE_PRIVATE))
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            // matte dark like the rest of the app: invert the light tiles, mute the colour and dim them a little
            val dark = android.graphics.ColorMatrix(floatArrayOf(-0.8f, 0f, 0f, 0f, 205f, 0f, -0.8f, 0f, 0f, 205f, 0f, 0f, -0.8f, 0f, 205f, 0f, 0f, 0f, 1f, 0f))
            dark.postConcat(android.graphics.ColorMatrix().apply { setSaturation(0.3f) })
            overlayManager.tilesOverlay.setColorFilter(android.graphics.ColorMatrixColorFilter(dark))
            overlayManager.tilesOverlay.loadingBackgroundColor = android.graphics.Color.parseColor("#0A0A0B")
            overlayManager.tilesOverlay.loadingLineColor = android.graphics.Color.parseColor("#1B1B1F")
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(14.0)
            controller.setCenter(GeoPoint(30.0, 31.0))
        }
    }
    DisposableEffect(Unit) {
        map.onResume()
        onDispose { map.onPause(); map.onDetach() }
    }
    LaunchedEffect(focus) {
        focus?.let { map.controller.animateTo(GeoPoint(it.lat, it.lng), 17.0, 600L) }
    }
    AndroidView(factory = { map }, modifier = modifier, update = { m ->
        m.overlays.clear()
        if (pick != null) {
            m.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint): Boolean { pick(p.latitude, p.longitude); return true }
                override fun longPressHelper(p: GeoPoint): Boolean { pick(p.latitude, p.longitude); return true }
            }))
        }
        for (pl in places) {
            m.overlays.add(Polygon(m).apply {
                points = Polygon.pointsAsCircle(GeoPoint(pl.lat, pl.lng), pl.radius.toDouble())
                fillPaint.color = 0x33E6B866
                outlinePaint.color = 0xFFE6B866.toInt()
                outlinePaint.strokeWidth = 3f
                title = pl.name
            })
            m.overlays.add(Marker(m).apply {
                position = GeoPoint(pl.lat, pl.lng); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = bubble(ctx, pl.emoji, pl.name, false, false); title = pl.name
            })
        }
        if (trail.size > 1) {
            m.overlays.add(Polyline(m).apply {
                setPoints(trail.map { GeoPoint(it.lat, it.lng) })
                outlinePaint.color = 0xAAE6B866.toInt(); outlinePaint.strokeWidth = 8f
            })
        }
        val pts = ArrayList<GeoPoint>()
        for (mem in members) {
            val l = mem.location ?: continue
            val g = GeoPoint(l.lat, l.lng)
            pts.add(g)
            m.overlays.add(Marker(m).apply {
                position = g; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = bubble(ctx, mem.emoji, mem.name, mem.id == myId, System.currentTimeMillis() / 1000.0 - l.at > 900)
                title = "${mem.name} · ${timeAgo(l.at)}"
            })
        }
        picked?.let { (la, lo) ->
            m.overlays.add(Marker(m).apply { position = GeoPoint(la, lo); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM); title = "New place"; icon = bubble(ctx, "📍", "New place", false, false) })
        }
        if (!fitted[0] && pts.isNotEmpty()) {
            fitted[0] = true
            if (pts.size == 1) { m.controller.setZoom(16.0); m.controller.setCenter(pts[0]) }
            else m.post { runCatching { m.zoomToBoundingBox(BoundingBox.fromGeoPoints(pts), true, 140) } }
        }
        m.invalidate()
    })
}
