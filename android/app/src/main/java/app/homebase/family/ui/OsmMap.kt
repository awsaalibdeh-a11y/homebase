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

private fun bubble(ctx: Context, emoji: String, name: String, mine: Boolean, stale: Boolean, sos: Boolean = false): BitmapDrawable {
    val d = ctx.resources.displayMetrics.density
    val w = (96 * d).toInt()
    val h = (92 * d).toInt()
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val cx = w / 2f
    p.color = if (sos) 0xFFD4303A.toInt() else if (mine) 0xFF141414.toInt() else 0xFF5B5954.toInt()
    p.alpha = if (stale) 150 else 255
    c.drawCircle(cx, 34 * d, 30 * d, p)
    p.color = 0xFFFFFFFF.toInt()
    c.drawCircle(cx, 34 * d, 26 * d, p)
    p.textAlign = Paint.Align.CENTER
    p.textSize = 30 * d
    c.drawText(emoji, cx, 45 * d, p)
    p.textSize = 13 * d
    p.isFakeBoldText = true
    val label = if (name.length > 10) name.take(9) + "…" else name
    p.style = Paint.Style.STROKE; p.strokeWidth = 4 * d; p.color = 0xFFFFFFFF.toInt()          // a white halo keeps the name readable on any map colour
    c.drawText(label, cx, 82 * d, p)
    p.style = Paint.Style.FILL; p.color = 0xFF17171A.toInt()
    c.drawText(label, cx, 82 * d, p)
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
            // OpenStreetMap's own tiles (free, no key; they ask apps to send their name, which osmdroid does). CARTO now wants a paid key.
            // In the dark look the tiles are turned into a soft night map with a colour filter, so nothing extra is downloaded.
            setTileSource(TileSourceFactory.MAPNIK)
            overlays.add(org.osmdroid.views.overlay.CopyrightOverlay(ctx))
            if (Look.dark) {
                val night = android.graphics.ColorMatrix(floatArrayOf(-0.85f, 0f, 0f, 0f, 235f, 0f, -0.85f, 0f, 0f, 235f, 0f, 0f, -0.85f, 0f, 240f, 0f, 0f, 0f, 1f, 0f))
                night.postConcat(android.graphics.ColorMatrix().apply { setSaturation(0.25f) })
                overlayManager.tilesOverlay.setColorFilter(android.graphics.ColorMatrixColorFilter(night))
                overlayManager.tilesOverlay.loadingBackgroundColor = android.graphics.Color.parseColor("#16161A")
                overlayManager.tilesOverlay.loadingLineColor = android.graphics.Color.parseColor("#26262C")
            } else {
                overlayManager.tilesOverlay.setColorFilter(android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(0.6f) }))
                overlayManager.tilesOverlay.loadingBackgroundColor = android.graphics.Color.parseColor("#F1F1F3")
                overlayManager.tilesOverlay.loadingLineColor = android.graphics.Color.parseColor("#E1E1E6")
            }
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            // Open straight on the family. Starting somewhere fixed and sliding over made the map queue hundreds of tiles along the way,
            // so the tiles people actually look at waited behind them and the map stayed blank.
            val first = (members.firstOrNull { it.id == myId }?.location ?: members.firstNotNullOfOrNull { it.location })
            controller.setZoom(if (first != null) 15.0 else 3.0)
            controller.setCenter(if (first != null) GeoPoint(first.lat, first.lng) else GeoPoint(30.0, 10.0))
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
        m.overlays.removeAll { it !is org.osmdroid.views.overlay.CopyrightOverlay }
        if (pick != null) {
            m.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint): Boolean { pick(p.latitude, p.longitude); return true }
                override fun longPressHelper(p: GeoPoint): Boolean { pick(p.latitude, p.longitude); return true }
            }))
        }
        for (pl in places) {
            m.overlays.add(Polygon(m).apply {
                points = Polygon.pointsAsCircle(GeoPoint(pl.lat, pl.lng), pl.radius.toDouble())
                fillPaint.color = if (pl.pending) 0x14000000 else 0x2A141414
                outlinePaint.color = if (pl.pending) 0x88000000.toInt() else 0xFF141414.toInt()
                outlinePaint.strokeWidth = 4f
                if (pl.pending) outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(18f, 12f), 0f)
                title = pl.name
            })
            m.overlays.add(Marker(m).apply {
                position = GeoPoint(pl.lat, pl.lng); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = bubble(ctx, pl.emoji, pl.name, false, pl.pending); title = if (pl.pending) "${pl.name} (waiting for a parent)" else pl.name
            })
        }
        if (trail.size > 1) {
            m.overlays.add(Polyline(m).apply {
                setPoints(trail.map { GeoPoint(it.lat, it.lng) })
                outlinePaint.color = 0xCC141414.toInt(); outlinePaint.strokeWidth = 8f
            })
        }
        val pts = ArrayList<GeoPoint>()
        for (mem in members) {
            val l = mem.location ?: continue
            val g = GeoPoint(l.lat, l.lng)
            pts.add(g)
            m.overlays.add(Marker(m).apply {
                position = g; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = bubble(ctx, mem.emoji, mem.name, mem.id == myId, System.currentTimeMillis() / 1000.0 - l.at > 900 && !mem.sos, mem.sos)
                title = (if (mem.sos) "🚨 SOS · " else "") + "${mem.name} · ${timeAgo(l.at)}" + (mem.eta?.let { " · about ${it.min} min from home" } ?: "")
            })
        }
        picked?.let { (la, lo) ->
            m.overlays.add(Marker(m).apply { position = GeoPoint(la, lo); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM); title = "New place"; icon = bubble(ctx, "📍", "New place", false, false) })
        }
        if (!fitted[0] && pts.isNotEmpty()) {
            fitted[0] = true
            if (pts.size == 1) { m.controller.setZoom(16.0); m.controller.setCenter(pts[0]) }
            else m.post { runCatching { m.zoomToBoundingBox(BoundingBox.fromGeoPoints(pts), false, 140); if (m.zoomLevelDouble > 17.0) m.controller.setZoom(17.0) } }
        }
        m.invalidate()
    })
}
