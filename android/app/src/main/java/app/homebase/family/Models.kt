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
    val home: Boolean = false,
    val lock: LockInfo? = null,
    val blocks: List<BlockInfo> = emptyList(),
    val avatar: String? = null,
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
data class Chore(val id: Int, val title: String = "", val emoji: String = "🧹", val points: Int = 0, val to: Int = 0, val status: String = "open", @SerialName("done_by") val doneBy: Int = 0)

@Serializable
data class ChoresResp(val chores: List<Chore> = emptyList(), val points: Map<String, Int> = emptyMap(), val week: Map<String, Int> = emptyMap())

@Serializable
data class SumTop(val label: String = "", val sec: Int = 0)

@Serializable
data class SumScreen(val total: Int = 0, val avg: Int = 0, val top: List<SumTop> = emptyList())

@Serializable
data class SumLocks(val count: Int = 0, val minutes: Int = 0)

@Serializable
data class SumChores(val done: Int = 0, val points: Int = 0)

@Serializable
data class SummaryResp(val name: String = "", val screen: SumScreen? = null, val locks: SumLocks = SumLocks(), val chores: SumChores = SumChores())

@Serializable
data class RuleKid(val id: Int, val name: String = "", val emoji: String = "🙂")

@Serializable
data class RulesResp(val text: String = "", val limits: Map<String, Int> = emptyMap(), val children: List<RuleKid> = emptyList())

@Serializable
data class BlockInfo(val id: Int = 0, val key: String = "", val label: String = "", val until: Double = 0.0, @SerialName("active_at") val activeAt: Double = 0.0)

@Serializable
data class AppInfo(val key: String = "", val label: String = "")

@Serializable
data class BlocksResp(val blocks: List<BlockInfo> = emptyList(), val available: List<AppInfo> = emptyList())

@Serializable
data class LockInfo(val id: Int = 0, val until: Double = 0.0, @SerialName("by_name") val byName: String = "a parent")

@Serializable
data class LockResp(val lock: LockInfo? = null, val left: Int = 0, val used: Int = 0, val until: Double = 0.0)

@Serializable
data class ScreenApp(val pkg: String, val label: String = "", val cat: String = "play", val sec: Int = 0)

@Serializable
data class ScreenDay(val day: String, val total: Int = 0, val apps: List<ScreenApp> = emptyList())

@Serializable
data class ScreenResp(val name: String = "", val days: List<ScreenDay> = emptyList())

@Serializable
data class QuietInfo(val from: Int = -1, val to: Int = -1)

@Serializable
data class MeResp(val me: Member, val family: FamilyInfo, val quiet: QuietInfo = QuietInfo())

@Serializable
data class FamilyConfig(@SerialName("version_code") val versionCode: Int = 1, @SerialName("apk_url") val apkUrl: String = "")
