package app.homebase.family

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class ApiError(message: String, val code: Int, val extra: JsonObject? = null) : Exception(message) {
    val quiet get() = extra?.get("quiet")?.jsonPrimitive?.contentOrNull == "true"
}

val Net = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true }

/** Everything Homebase says to its server. Plain HTTPS and JSON, nothing hidden. */
class Api(private val store: Store) {
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val poller = client.newBuilder().readTimeout(45, TimeUnit.SECONDS).build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun raw(method: String, path: String, body: JsonObject? = null, auth: Boolean = true, long: Boolean = false): String = withContext(Dispatchers.IO) {
        val rb = Request.Builder().url(store.server + path)
        if (auth && store.token.isNotEmpty()) rb.header("Authorization", "Bearer ${store.token}")
        rb.method(method, if (method == "GET") null else (body ?: buildJsonObject { }).toString().toRequestBody(jsonType))
        (if (long) poller else client).newCall(rb.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val obj = runCatching { Net.parseToJsonElement(text).jsonObject }.getOrNull()
                throw ApiError(obj?.get("error")?.jsonPrimitive?.contentOrNull ?: "Something went wrong (${r.code}).", r.code, obj)
            }
            text
        }
    }

    private suspend inline fun <reified T> ask(method: String, path: String, body: JsonObject? = null, auth: Boolean = true, long: Boolean = false): T =
        Net.decodeFromString(raw(method, path, body, auth, long))

    private fun obj(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject(build)

    // sign in
    suspend fun createFamily(family: String, name: String, emoji: String): AuthResp =
        ask("POST", "/api/family", obj { put("family", family); put("name", name); put("emoji", emoji) }, auth = false)
    suspend fun lookInvite(code: String): InviteLook = ask("GET", "/api/invite/${code.trim()}", auth = false)
    suspend fun join(code: String, name: String, emoji: String): AuthResp =
        ask("POST", "/api/join", obj { put("code", code.trim()); put("name", name); put("emoji", emoji) }, auth = false)
    suspend fun invite(role: String): InviteResp = ask("POST", "/api/invite", obj { put("role", role) })

    // family and me
    suspend fun family(): FamilyResp = ask("GET", "/api/family")
    suspend fun me(): MeResp = ask("GET", "/api/me")
    suspend fun patchMe(vararg pairs: Pair<String, Any>) {
        raw("PATCH", "/api/me", obj { pairs.forEach { (k, v) -> if (v is Number) put(k, v) else put(k, v.toString()) } })
    }
    suspend fun setPerms(perms: Map<String, Boolean>) {
        raw("PATCH", "/api/me", kotlinx.serialization.json.buildJsonObject {
            put("perms", kotlinx.serialization.json.buildJsonObject { perms.forEach { (k, v) -> put(k, kotlinx.serialization.json.JsonPrimitive(v)) } })
        })
    }
    suspend fun leave() { raw("DELETE", "/api/me") }

    // location
    suspend fun postLocation(lat: Double, lng: Double, acc: Float, speed: Float, battery: Int) {
        raw("POST", "/api/location", obj { put("lat", lat); put("lng", lng); put("acc", acc.toDouble()); put("speed", speed.toDouble()); put("battery", battery) })
    }
    suspend fun sharing(state: String, minutes: Int = 60) { raw("POST", "/api/sharing", obj { put("state", state); put("minutes", minutes) }) }
    suspend fun history(member: Int, hours: Int = 12): HistoryResp = ask("GET", "/api/history?member=$member&hours=$hours")

    suspend fun deleteHistory() { raw("DELETE", "/api/history") }
    suspend fun config(): FamilyConfig = ask("GET", "/api/config", auth = false)

    // messages
    suspend fun messages(with: Int, after: Int = 0): List<Message> = ask<MessagesResp>("GET", "/api/messages?with=$with&after=$after").messages
    suspend fun send(kind: String, body: String, to: Int, lat: Double? = null, lng: Double? = null): Message =
        ask<SendResp>("POST", "/api/messages", obj {
            put("kind", kind); put("body", body); put("to", to)
            if (lat != null && lng != null) { put("lat", lat); put("lng", lng) }
        }).message

    // "where are you?"
    suspend fun ping(to: Int): Int = ask<IdResp>("POST", "/api/ping", obj { put("to", to) }).id
    suspend fun answerPing(id: Int, accept: Boolean, lat: Double? = null, lng: Double? = null) {
        raw("POST", "/api/ping/$id", obj { put("accept", accept); if (lat != null && lng != null) { put("lat", lat); put("lng", lng) } })
    }

    // places and the list
    suspend fun addPlace(name: String, emoji: String, lat: Double, lng: Double, radius: Int) {
        raw("POST", "/api/places", obj { put("name", name); put("emoji", emoji); put("lat", lat); put("lng", lng); put("radius", radius) })
    }
    suspend fun removePlace(id: Int) { raw("DELETE", "/api/places/$id") }
    suspend fun items(): List<Item> = ask<ItemsResp>("GET", "/api/list").items
    suspend fun addItem(text: String) { raw("POST", "/api/list", obj { put("text", text) }) }
    suspend fun tick(id: Int, done: Boolean) { raw("POST", "/api/list/$id", obj { put("done", done) }) }
    suspend fun dropItem(id: Int) { raw("DELETE", "/api/list/$id") }

    // the event stream: held open by the service, it wakes the app when something happens
    suspend fun events(after: Int, wait: Int = 25): EventsResp = ask("GET", "/api/events?after=$after&wait=$wait", long = true)

    // calls
    suspend fun startCall(to: Int, offer: String, urgent: Boolean): CallStart =
        ask("POST", "/api/calls", obj { put("to", to); put("offer", offer); put("urgent", urgent) })
    suspend fun callState(id: Int): CallState = ask("GET", "/api/calls/$id")
    suspend fun answerCall(id: Int, answer: String) { raw("POST", "/api/calls/$id/answer", obj { put("answer", answer) }) }
    suspend fun declineCall(id: Int) { raw("POST", "/api/calls/$id/decline") }
    suspend fun endCall(id: Int) { raw("POST", "/api/calls/$id/end") }
    suspend fun sendIce(id: Int, candidate: JsonObject) { raw("POST", "/api/calls/$id/ice", obj { put("candidate", candidate as JsonElement) }) }
}
