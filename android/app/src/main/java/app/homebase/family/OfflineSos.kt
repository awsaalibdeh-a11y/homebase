package app.homebase.family

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat

/**
 * An SOS with no internet: GPS still works, so Mellow texts the location to the numbers on the emergency card and the trusted adults list.
 * Only when the person allowed texting (Android asks), and only when the SOS could not reach the server. Normal text charges apply.
 */
object OfflineSos {
    fun allowed(ctx: Context) = ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    fun numbers(ctx: Context): List<String> {
        val s = ctx.hb.store
        val card = runCatching { Net.decodeFromString<Map<String, String>>(s.text("emergency")) }.getOrDefault(emptyMap())
        val fromCard = listOfNotNull(card["c1Phone"], card["c2Phone"], card["doctorPhone"]?.takeIf { false })
        val trusted = s.text("trusted").lines().filter { it.contains("|") }.map { it.substringAfter("|") }
        return (fromCard + trusted).map { it.filter { c -> c.isDigit() || c == '+' } }.filter { it.length >= 5 }.distinct().take(5)
    }

    /** Returns how many people were texted. */
    fun send(ctx: Context, lat: Double?, lng: Double?): Int {
        if (!allowed(ctx)) return 0
        val to = numbers(ctx)
        if (to.isEmpty()) return 0
        val name = ctx.hb.store.myName.ifBlank { "Someone" }
        val where = if (lat != null && lng != null) "https://maps.google.com/?q=%.5f,%.5f".format(lat, lng) else "location not known"
        val text = "SOS from Mellow: $name needs help. Location: $where (sent by text because there was no internet)."
        val sms = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
        var sent = 0
        to.forEach { n -> if (runCatching { sms.sendMultipartTextMessage(n, null, sms.divideMessage(text), null, null) }.isSuccess) sent++ }
        return sent
    }
}
