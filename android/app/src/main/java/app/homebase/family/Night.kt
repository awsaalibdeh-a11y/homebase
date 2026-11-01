package app.homebase.family

import java.util.Calendar

/** Mellow rests so the free server can sleep: from midnight until the hour the server gives (7 am, phone time), and while the server
 *  asks for a rest because the month's free hours are running ahead. Every reply carries X-Rest: "<hour>;<rest until, unix seconds>".
 *  Anything the user does on purpose still goes through, and the background stays on while the app is open on screen. */
object Night {
    @Volatile var appOpen = false
    @Volatile var restTo = 7
    @Volatile var restUntil = 0L

    fun quiet(): Boolean = !appOpen && (Calendar.getInstance().get(Calendar.HOUR_OF_DAY) < restTo || System.currentTimeMillis() / 1000 < restUntil)

    /** Reads the server's X-Rest header; anything odd is ignored. */
    fun read(header: String?) {
        val p = header?.split(";") ?: return
        if (p.size != 2) return
        val to = p[0].trim().toIntOrNull() ?: return
        val until = p[1].trim().toLongOrNull() ?: return
        if (to in 0..12 && until >= 0) { restTo = to; restUntil = until }
    }
}
