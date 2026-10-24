package app.homebase.family

import java.util.Calendar

/** Mellow rests from midnight to 6 am (phone time) so the free server can sleep. Anything the user does
 *  on purpose still goes through, and the background stays on while the app is open on screen. */
object Night {
    @Volatile var appOpen = false
    fun quiet(): Boolean = !appOpen && Calendar.getInstance().get(Calendar.HOUR_OF_DAY) < 6
}
