package app.homebase.family

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class Spot(val lat: Double, val lng: Double, val acc: Double = 0.0, val at: Double = 0.0)

@Serializable
data class Member(
    val id: Int,
    val name: String,
    val role: String,
    val emoji: String = "🙂",
    val battery: Int = -1,
    val share: String = "off",
    @SerialName("paused_until") val pausedUntil: Double = 0.0,
    @SerialName("last_seen") val lastSeen: Double = 0.0,
    val quiet: Boolean = false,
    val location: Spot? = null,
    val perms: Map<String, Boolean> = emptyMap(),
) {
    val isParent get() = role == "parent"
    /** Whether a parent may do [key] with this person. Everything is allowed until they turn it off. */
    fun allows(key: String) = perms[key] != false
}

@Serializable
data class Place(val id: Int, val name: String, val emoji: String = "📍", val lat: Double, val lng: Double, val radius: Int = 150)

@Serializable
data class FamilyInfo(val id: Int, val name: String)

@Serializable
data class IceServer(val urls: List<String>, val username: String? = null, val credential: String? = null)

@Serializable
data class FamilyResp(
    val family: FamilyInfo,
    val me: Int,
    val members: List<Member>,
    val places: List<Place> = emptyList(),
    @SerialName("ice_servers") val iceServers: List<IceServer> = emptyList(),
    @SerialName("version_code") val versionCode: Int = 1,
)

@Serializable
data class AuthResp(val token: String, val me: Member, val family: FamilyInfo)

@Serializable
data class InviteLook(val role: String, val family: String, @SerialName("invited_by") val invitedBy: String = "")

@Serializable
data class InviteResp(val code: String, val role: String, val expires: Double = 0.0)

@Serializable
data class Message(
    val id: Int,
    val from: Int,
    val to: Int = 0,
    val kind: String = "text",
    val body: String = "",
    val lat: Double? = null,
    val lng: Double? = null,
    val at: Double = 0.0,
)

@Serializable
data class MessagesResp(val messages: List<Message> = emptyList())

@Serializable
data class SendResp(val message: Message)

@Serializable
data class Item(val id: Int, val text: String, val done: Boolean = false, val by: Int = 0, @SerialName("done_by") val doneBy: Int = 0)

@Serializable
data class ItemsResp(val items: List<Item> = emptyList())

@Serializable
data class Event(val id: Int, val type: String, val payload: JsonObject, val at: Double = 0.0)

@Serializable
data class EventsResp(val cursor: Int = 0, val events: List<Event> = emptyList())

@Serializable
data class IdResp(val id: Int = 0)

@Serializable
data class CallStart(val id: Int, @SerialName("ice_servers") val iceServers: List<IceServer> = emptyList())

@Serializable
data class CallState(val id: Int = 0, val status: String = "", val answer: String? = null)

@Serializable
data class HistoryPoint(val lat: Double, val lng: Double, val at: Double = 0.0)

@Serializable
data class HistoryResp(val points: List<HistoryPoint> = emptyList())

@Serializable
data class QuietInfo(val from: Int = -1, val to: Int = -1)

@Serializable
data class MeResp(val me: Member, val family: FamilyInfo, val quiet: QuietInfo = QuietInfo())

@Serializable
data class FamilyConfig(@SerialName("version_code") val versionCode: Int = 1, @SerialName("apk_url") val apkUrl: String = "")
