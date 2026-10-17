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
        val rb = Request.Builder().url(store.server + path).header("X-Homebase-Version", BuildConfig.VERSION_CODE.toString())
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
    suspend fun postLocation(lat: Double, lng: Double, acc: Float, speed: Float, battery: Int): LocResp =
        ask("POST", "/api/location", obj { put("lat", lat); put("lng", lng); put("acc", acc.toDouble()); put("speed", speed.toDouble()); put("battery", battery) })
    suspend fun sharing(state: String, minutes: Int = 60) { raw("POST", "/api/sharing", obj { put("state", state); put("minutes", minutes) }) }
    suspend fun postScreenTime(day: String, apps: kotlinx.serialization.json.JsonArray) { raw("POST", "/api/screentime", obj { put("day", day); put("apps", apps) }) }
    suspend fun screenTime(member: Int, days: Int = 7): ScreenResp = ask("GET", "/api/screentime?member=$member&days=$days")
    suspend fun deleteScreenTime() { raw("DELETE", "/api/screentime") }
    suspend fun setHome(lat: Double, lng: Double, radius: Int) { raw("POST", "/api/home", obj { put("lat", lat); put("lng", lng); put("radius", radius) }) }
    suspend fun lockState(member: Int): LockResp = ask("GET", "/api/lock?member=$member")
    suspend fun lock(member: Int, minutes: Int): LockResp = ask("POST", "/api/lock", obj { put("member", member); put("minutes", minutes) })
    suspend fun releaseLock(member: Int) { raw("POST", "/api/lock/$member/release") }
    suspend fun askUnlock() { raw("POST", "/api/lock/ask") }
    suspend fun rules(): RulesResp = ask("GET", "/api/rules")
    suspend fun saveRules(text: String, limits: Map<Int, Int>) { raw("POST", "/api/rules", obj { put("text", text); put("limits", buildJsonObject { limits.forEach { (k, v) -> put(k.toString(), v) } }) }) }
    suspend fun chores(): ChoresResp = ask("GET", "/api/chores")
    suspend fun addChore(title: String, points: Int, to: Int, repeat: String = "") { raw("POST", "/api/chores", obj { put("title", title); put("points", points); put("to", to); put("repeat", repeat) }) }
    suspend fun choreDone(id: Int) { raw("POST", "/api/chores/$id/done") }
    suspend fun choreApprove(id: Int, ok: Boolean) { raw("POST", "/api/chores/$id/approve", obj { put("ok", ok) }) }
    suspend fun choreDelete(id: Int) { raw("DELETE", "/api/chores/$id") }
    suspend fun summary(member: Int): SummaryResp = ask("GET", "/api/summary?member=$member")
    suspend fun blocks(member: Int): BlocksResp = ask("GET", "/api/blocks?member=$member")
    suspend fun ground(member: Int, minutes: Int, apps: List<String>) {
        raw("POST", "/api/blocks", obj { put("member", member); put("minutes", minutes); put("apps", kotlinx.serialization.json.buildJsonArray { apps.forEach { a -> add(buildJsonObject { put("key", a.trim().lowercase()); put("label", a.trim()) }) } }) })
    }
    suspend fun liftBlock(id: Int) { raw("DELETE", "/api/blocks/$id") }
    suspend fun avatarBytes(path: String): ByteArray? = withContext(Dispatchers.IO) {
        val rb = okhttp3.Request.Builder().url(store.server + path)
        if (store.token.isNotEmpty()) rb.header("Authorization", "Bearer ${store.token}")
        client.newCall(rb.build()).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
    }
    suspend fun setAvatar(dataUrl: String) { raw("POST", "/api/avatar", obj { put("image", dataUrl) }) }
    suspend fun clearAvatar() { raw("DELETE", "/api/avatar") }
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

    suspend fun sendFull(kind: String, body: String, to: Int, lat: Double? = null, lng: Double? = null): SendResp =
        ask("POST", "/api/messages", obj {
            put("kind", kind); put("body", body); put("to", to)
            if (lat != null && lng != null) { put("lat", lat); put("lng", lng) }
        })

    // safety
    suspend fun risk(): List<RiskAlert> = ask<RiskResp>("GET", "/api/risk").alerts
    suspend fun riskSeen(id: Int) { raw("POST", "/api/risk/$id/seen") }
    suspend fun critical(member: Int) { raw("POST", "/api/members/$member/critical") }
    suspend fun criticalAck() { raw("POST", "/api/critical/ack") }
    suspend fun sosClear() { raw("POST", "/api/sos/clear") }
    suspend fun approvePlace(id: Int, ok: Boolean) { raw("POST", "/api/places/$id/approve", obj { put("ok", ok) }) }

    suspend fun react(id: Int, emoji: String): Map<String, List<Int>> = ask<ReactResp>("POST", "/api/messages/$id/react", obj { put("emoji", emoji) }).reactions

    // "what I am up to"
    suspend fun setStatus(emoji: String, text: String, hours: Int) { raw("POST", "/api/status", obj { put("emoji", emoji); put("text", text); put("hours", hours) }) }
    suspend fun clearStatus() { raw("DELETE", "/api/status") }

    // the family calendar and family votes
    suspend fun plans(): List<Plan> = ask<PlansResp>("GET", "/api/plans").plans
    suspend fun addPlan(title: String, emoji: String, start: Double, note: String) { raw("POST", "/api/plans", obj { put("title", title); put("emoji", emoji); put("start", start); put("note", note) }) }
    suspend fun rsvp(id: Int, answer: String): Plan = ask<PlanResp>("POST", "/api/plans/$id/rsvp", obj { put("answer", answer) }).plan
    suspend fun dropPlan(id: Int) { raw("DELETE", "/api/plans/$id") }
    suspend fun plansBetween(from: Long, to: Long): List<Plan> = ask<PlansResp>("GET", "/api/plans?from=$from&to=$to").plans
    suspend fun askUpdate(memberId: Int) { raw("POST", "/api/members/$memberId/update-nudge") }
    suspend fun setNotice(text: String, hours: Int) { raw("POST", "/api/notice", obj { put("text", text); put("hours", hours) }) }
    suspend fun clearNotice() { raw("DELETE", "/api/notice") }
    suspend fun polls(): List<Poll> = ask<PollsResp>("GET", "/api/polls").polls
    suspend fun askPoll(question: String, options: List<String>) {
        raw("POST", "/api/polls", obj { put("question", question); put("options", kotlinx.serialization.json.JsonArray(options.map { JsonPrimitive(it) })) })
    }
    suspend fun vote(id: Int, option: Int): Poll = ask<PollResp>("POST", "/api/polls/$id/vote", obj { put("option", option) }).poll
    suspend fun closePoll(id: Int) { raw("POST", "/api/polls/$id/close") }
    suspend fun dropPoll(id: Int) { raw("DELETE", "/api/polls/$id") }

    // rewards (what chore points can buy) and the family board
    suspend fun rewards(): RewardsResp = ask<RewardsResp>("GET", "/api/rewards")
    suspend fun addReward(title: String, cost: Int, minutes: Int = 0) { raw("POST", "/api/rewards", obj { put("title", title); put("cost", cost); put("minutes", minutes) }) }
    suspend fun setBirthday(month: Int, day: Int) { raw("POST", "/api/birthday", obj { put("month", month); put("day", day) }) }
    suspend fun dropReward(id: Int) { raw("DELETE", "/api/rewards/$id") }
    suspend fun redeem(id: Int) { raw("POST", "/api/rewards/$id/redeem") }
    suspend fun decide(id: Int, ok: Boolean) { raw("POST", "/api/redemptions/$id/decide", obj { put("ok", ok) }) }
    suspend fun cancelRedemption(id: Int) { raw("DELETE", "/api/redemptions/$id") }
    suspend fun board(): List<BoardItem> = ask<BoardResp>("GET", "/api/board").items
    suspend fun saveNote(id: Int?, title: String, emoji: String, body: String) {
        raw("POST", "/api/board", obj { if (id != null) put("id", id); put("title", title); put("emoji", emoji); put("body", body) })
    }
    suspend fun dropNote(id: Int) { raw("DELETE", "/api/board/$id") }

    // the meal plan
    suspend fun meals(from: String? = null, to: String? = null): List<Meal> =
        ask<MealsResp>("GET", "/api/meals" + (if (from != null && to != null) "?from=$from&to=$to" else "")).meals
    suspend fun addMeal(title: String, day: String, slot: String) { raw("POST", "/api/meals", obj { put("title", title); put("day", day); put("slot", slot) }) }
    suspend fun dropMeal(id: Int) { raw("DELETE", "/api/meals/$id") }
    suspend fun setCook(id: Int, cook: Int) { raw("POST", "/api/meals/$id/cook", obj { put("cook", cook) }) }
    suspend fun mealToList(id: Int): Int = ask<AddedResp>("POST", "/api/meals/$id/to-list").added

    suspend fun recipes(q: String = "", tag: String? = null, random: Boolean = false): RecipesResp =
        ask("GET", "/api/recipes?q=" + java.net.URLEncoder.encode(q, "UTF-8") + (if (tag != null) "&tag=" + java.net.URLEncoder.encode(tag, "UTF-8") else "") + (if (random) "&random=1" else ""))
    /** Puts a library recipe on the plan. Returns true when it was only a suggestion (a child asked), false when it was planned. */
    suspend fun addRecipeMeal(recipe: String, day: String, slot: String): Boolean =
        Net.parseToJsonElement(raw("POST", "/api/meals", obj { put("recipe", recipe); put("day", day); put("slot", slot) })).jsonObject["suggested"]?.jsonPrimitive?.contentOrNull == "true"
    suspend fun approveMeal(id: Int, ok: Boolean) { raw("POST", "/api/meals/$id/approve", obj { put("ok", ok) }) }

    // friend groups
    suspend fun groups(): GroupsResp = ask("GET", "/api/groups")
    suspend fun makeGroup(name: String, emoji: String): MakeGroupResp = ask("POST", "/api/groups", obj { put("name", name); put("emoji", emoji) })
    suspend fun joinGroup(code: String): JoinGroupResp = ask("POST", "/api/groups/join", obj { put("code", code) })
    suspend fun approveGroup(gid: Int, member: Int, ok: Boolean) { raw("POST", "/api/groups/$gid/approve", obj { put("member", member); put("ok", ok) }) }
    suspend fun leaveGroup(gid: Int) { raw("POST", "/api/groups/$gid/leave") }
    suspend fun removeFromGroup(gid: Int, member: Int) { raw("DELETE", "/api/groups/$gid/members/$member") }
    suspend fun groupMessages(gid: Int, after: Int = 0): GroupMessagesResp = ask("GET", "/api/groups/$gid/messages" + (if (after > 0) "?after=$after" else ""))
    suspend fun sendGroup(gid: Int, kind: String, body: String): GroupSendResp = ask("POST", "/api/groups/$gid/messages", obj { put("kind", kind); put("body", body) })

    // stickers, the activity feed, family games
    suspend fun uploadImage(dataUrl: String): String = Net.parseToJsonElement(raw("POST", "/api/images", obj { put("image", dataUrl) })).jsonObject["url"]?.jsonPrimitive?.contentOrNull ?: error("The photo did not upload.")
    /** Names of the apps already linked (a parent can ask). */
    suspend fun linkedApps(): List<String> =
        (Net.parseToJsonElement(raw("GET", "/api/integrations")).jsonObject["integrations"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
    /** Makes a private key for Platter or Scout; it goes in the link that connects the app. */
    suspend fun linkApp(name: String): String =
        Net.parseToJsonElement(raw("POST", "/api/integrations", obj { put("name", name) })).jsonObject["key"]?.jsonPrimitive?.contentOrNull ?: error("Could not make the link.")
    // ---- Mellow 5.0
    suspend fun album(): List<AlbumPhoto> = ask<AlbumResp>("GET", "/api/album").photos
    suspend fun addToAlbum(url: String, caption: String) { raw("POST", "/api/album", obj { put("url", url); put("caption", caption) }) }
    suspend fun dropPhoto(id: Int) { raw("DELETE", "/api/album/$id") }
    suspend fun uploadAudio(dataUrl: String): String = ask<UrlResp>("POST", "/api/audio", obj { put("audio", dataUrl) }).url
    suspend fun wallet(): List<WalletInfo> = ask<WalletResp>("GET", "/api/wallet").wallets
    suspend fun walletAdd(member: Int, amount: Double, note: String) { raw("POST", "/api/wallet", obj { put("member", member); put("amount", amount); put("note", note) }) }
    suspend fun walletRequest(amount: Double, note: String) { raw("POST", "/api/wallet/request", obj { put("amount", amount); put("note", note) }) }
    suspend fun walletAnswer(id: Int, ok: Boolean) { raw("POST", "/api/wallet/$id/answer", obj { put("ok", ok) }) }
    suspend fun addGoal(title: String, emoji: String, target: Double) { raw("POST", "/api/goals", obj { put("title", title); put("emoji", emoji); put("target", target) }) }
    suspend fun saveToGoal(id: Int, amount: Double) { raw("POST", "/api/goals/$id/save", obj { put("amount", amount) }) }
    suspend fun dropGoal(id: Int) { raw("DELETE", "/api/goals/$id") }
    suspend fun postMood(mood: Int, note: String, private: Boolean) { raw("POST", "/api/mood", obj { put("mood", mood); put("note", note); put("private", private) }) }
    suspend fun moods(member: Int? = null): MoodsResp = ask("GET", "/api/moods" + (if (member != null) "?member=$member" else ""))
    suspend fun startWalk(place: Int, minutes: Int) { raw("POST", "/api/walk", obj { put("place", place); put("minutes", minutes) }) }
    suspend fun stopWalk() { raw("POST", "/api/walk/stop") }
    suspend fun expect(member: Int, place: Int, minutes: Int) { raw("POST", "/api/expect", obj { put("member", member); put("place", place); put("minutes", minutes) }) }
    suspend fun speedLimit(member: Int, limit: Int) { raw("POST", "/api/members/$member/speed", obj { put("limit", limit) }) }
    suspend fun ring(member: Int) { raw("POST", "/api/members/$member/ring") }
    suspend fun weather(): WeatherResp = ask("GET", "/api/weather")
    suspend fun timeline(member: Int): TimelineResp = ask("GET", "/api/timeline?member=$member")
    suspend fun makeTrip(hours: Int): String = ask<UrlResp>("POST", "/api/trip", obj { put("hours", hours) }).url
    suspend fun stopTrip() { raw("DELETE", "/api/trip") }
    suspend fun giveKudos(to: Int, emoji: String, reason: String) { raw("POST", "/api/kudos", obj { put("to", to); put("emoji", emoji); put("reason", reason) }) }
    suspend fun kudos(): KudosResp = ask("GET", "/api/kudos")
    suspend fun badges(member: Int? = null): BadgesResp = ask("GET", "/api/badges" + (if (member != null) "?member=$member" else ""))
    suspend fun countdowns(): List<Countdown> = ask<CountdownsResp>("GET", "/api/countdowns").countdowns
    suspend fun addCountdown(title: String, emoji: String, at: Double) { raw("POST", "/api/countdowns", obj { put("title", title); put("emoji", emoji); put("at", at) }) }
    suspend fun dropCountdown(id: Int) { raw("DELETE", "/api/countdowns/$id") }
    suspend fun trusted(): List<Trusted> = ask<TrustedResp>("GET", "/api/trusted").people
    suspend fun addTrusted(name: String, phone: String, relation: String) { raw("POST", "/api/trusted", obj { put("name", name); put("phone", phone); put("relation", relation) }) }
    suspend fun dropTrusted(id: Int) { raw("DELETE", "/api/trusted/$id") }
    suspend fun homework(): List<Homework> = ask<HomeworkResp>("GET", "/api/homework").items
    suspend fun addHomework(title: String, subject: String, due: String, member: Int? = null) { raw("POST", "/api/homework", obj { put("title", title); put("subject", subject); put("due", due); if (member != null) put("member", member) }) }
    suspend fun tickHomework(id: Int, done: Boolean) { raw("POST", "/api/homework/$id", obj { put("done", done) }) }
    suspend fun dropHomework(id: Int) { raw("DELETE", "/api/homework/$id") }
    suspend fun setBedtime(member: Int, from: Int, to: Int) { raw("POST", "/api/members/$member/bedtime", obj { put("from", from); put("to", to) }) }
    suspend fun postLocations(points: kotlinx.serialization.json.JsonArray) { raw("POST", "/api/locations", obj { put("points", points) }) }

    suspend fun stickers(): List<Sticker> = ask<StickersResp>("GET", "/api/stickers").stickers
    suspend fun feed(): List<FeedItem> = ask<FeedResp>("GET", "/api/feed").items
    suspend fun startGame(game: String): GameStartResp = ask("POST", "/api/games/start", obj { put("game", game) })

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
    suspend fun clearDone() { raw("POST", "/api/list/clear") }

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
