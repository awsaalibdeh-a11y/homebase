package app.homebase.family

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper

/** Asks the phone's own GPS and network location for updates. Runs only while the person has sharing on. */
class LocationClient(private val ctx: Context, private val onFix: (Location) -> Unit) {
    private val manager = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var running = false
    private var everyMs = 20_000L
    private var metres = 15f

    /** How often to ask the GPS: fast during an SOS, slow on a low battery. Takes effect at once if it is running. */
    fun setRate(ms: Long, minMetres: Float) {
        if (ms == everyMs && minMetres == metres) return
        everyMs = ms; metres = minMetres
        if (running) { stop(); start() }
    }
    private val listener = object : LocationListener {
        override fun onLocationChanged(l: Location) { onFix(l) }
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
        @Deprecated("Deprecated in Java") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            runCatching { if (manager.isProviderEnabled(p)) manager.requestLocationUpdates(p, everyMs, metres, listener, Looper.getMainLooper()) }
        }
        runCatching { manager.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()?.let(onFix)
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { manager.removeUpdates(listener) }
    }

    /** One fresh position, for a "where are you?" answer. Falls back to the last one the phone knows. */
    @SuppressLint("MissingPermission")
    fun lastKnown(): Location? = runCatching {
        listOfNotNull(manager.getLastKnownLocation(LocationManager.GPS_PROVIDER), manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)).maxByOrNull { it.time }
    }.getOrNull()
}
