package app.homebase.family

import android.content.Context
import android.content.SharedPreferences

/** The little bit of the phone's memory Homebase needs: who you are, which server, and where the event stream stopped. */
class Store(context: Context) {
    private val p: SharedPreferences = context.applicationContext.getSharedPreferences("homebase", Context.MODE_PRIVATE)

    var server: String
        get() = p.getString("server", null) ?: BuildConfig.DEFAULT_SERVER
        set(v) = p.edit().putString("server", v.trim().trimEnd('/')).apply()
    var token: String
        get() = p.getString("token", "") ?: ""
        set(v) = p.edit().putString("token", v).apply()
    var myId: Int
        get() = p.getInt("myId", 0)
        set(v) = p.edit().putInt("myId", v).apply()
    var role: String
        get() = p.getString("role", "") ?: ""
        set(v) = p.edit().putString("role", v).apply()
    var myName: String
        get() = p.getString("myName", "") ?: ""
        set(v) = p.edit().putString("myName", v).apply()
    var familyName: String
        get() = p.getString("familyName", "") ?: ""
        set(v) = p.edit().putString("familyName", v).apply()
    var familyCache: String
        get() = p.getString("familyCache", "") ?: ""
        set(v) = p.edit().putString("familyCache", v).apply()
    var cursor: Int
        get() = p.getInt("cursor", -1)
        set(v) = p.edit().putInt("cursor", v).apply()
    var tourDone: Boolean
        get() = p.getBoolean("tourDone", false)
        set(v) = p.edit().putBoolean("tourDone", v).apply()
    var onboarded: Boolean
        get() = p.getBoolean("onboarded", false)
        set(v) = p.edit().putBoolean("onboarded", v).apply()
    var lastSentAt: Long
        get() = p.getLong("lastSentAt", 0L)
        set(v) = p.edit().putLong("lastSentAt", v).apply()
    var lastLat: Float
        get() = p.getFloat("lastLat", 0f)
        set(v) = p.edit().putFloat("lastLat", v).apply()
    var lastLng: Float
        get() = p.getFloat("lastLng", 0f)
        set(v) = p.edit().putFloat("lastLng", v).apply()

    val signedIn get() = token.isNotEmpty() && myId != 0
    val isParent get() = role == "parent"

    fun signOut() {
        val keepServer = server
        p.edit().clear().apply()
        server = keepServer
    }
}
