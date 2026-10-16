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
    val screen: ScreenToday? = null,
    val devices: List<DeviceInfo> = emptyList(),
    val status: StatusInfo? = null,
    val birthday: String? = null,                                   // "MM-DD", chosen by the person
    @SerialName("app_version") val appVersion: Int = 0,               // the Android build they last used (0 = not known)
) {
    val isParent get() = role == "parent"
    /** Whether a parent may do [key] with this person. Everything is allowed until they turn it off. */
    fun allows(key: String) = perms[key] != false
}

@Serializable
data class Place(val id: Int, val name: String, val emoji: String = "📍", val lat: Double, val lng: Double, val radius: Int = 150)

@Serializable
data class FamilyInfo(val id: Int, val name: String, val notice: NoticeInfo? = null)

/** A parent's announcement for the whole family. It ends by itself. */
@Serializable
data class NoticeInfo(val text: String = "", val by: String = "", val until: Double = 0.0)

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
    val reactions: Map<String, List<Int>> = emptyMap(),
)

/** What someone says they are up to ("Studying"). They set it themselves, the family sees it, and it ends by itself. */
@Serializable
data class StatusInfo(val emoji: String = "", val text: String = "", val until: Double = 0.0)

@Serializable
data class ReactResp(val reactions: Map<String, List<Int>> = emptyMap())

/** Something on the family calendar. [going] and [out] are member ids; [mine] is my own answer ("in", "out" or none). */
@Serializable
data class Plan(
    val id: Int,
    val title: String,
    val emoji: String = "📅",
    val start: Double = 0.0,
    val note: String = "",
    @SerialName("by") val maker: Int = 0,
    @SerialName("in") val going: List<Int> = emptyList(),
    val out: List<Int> = emptyList(),
    val mine: String? = null,
    val lat: Double? = null,                                       // a plan with a place gets a Directions button
    val lng: Double? = null,
    val via: String = "",                                          // "scout" when it came from a linked app
)

@Serializable
data class PlansResp(val plans: List<Plan> = emptyList())

@Serializable
data class PlanResp(val plan: Plan)

/** A question for the family. [votes] holds, for each answer, the ids of the people who chose it. */
@Serializable
data class Poll(
    val id: Int,
    val question: String,
    val options: List<String> = emptyList(),
    val votes: List<List<Int>> = emptyList(),
    val mine: Int? = null,
    @SerialName("by") val maker: Int = 0,
    val closed: Boolean = false,
    val at: Double = 0.0,
)

@Serializable
data class PollsResp(val polls: List<Poll> = emptyList())

@Serializable
data class PollResp(val poll: Poll)

/** Something chore points can buy. A [Redemption] is a child asking for one, and a parent's answer. */
@Serializable
data class Reward(val id: Int, val title: String, val emoji: String = "🎁", val cost: Int = 0, val minutes: Int = 0)

@Serializable
data class Redemption(
    val id: Int,
    val member: Int = 0,
    val title: String,
    val emoji: String = "🎁",
    val cost: Int = 0,
    val minutes: Int = 0,
    val status: String = "pending",
    val at: Double = 0.0,
    @SerialName("decided_at") val decidedAt: Double = 0.0,
)

/** [balance] is points earned minus points spent or held, by member id. */
@Serializable
data class RewardsResp(
    val rewards: List<Reward> = emptyList(),
    val requests: List<Redemption> = emptyList(),
    val balance: Map<String, Int> = emptyMap(),
    val streaks: Map<String, Int> = emptyMap(),
    val bonus: Map<String, Int> = emptyMap(),                       // extra screen-time minutes earned today, by member id
)

/** A note on the family board (Wi-Fi, the doctor, allergies). */
@Serializable
data class BoardItem(val id: Int, val title: String, val emoji: String = "📌", val body: String = "", val at: Double = 0.0)

@Serializable
data class BoardResp(val items: List<BoardItem> = emptyList())

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
data class Chore(
    val id: Int, val title: String = "", val emoji: String = "🧹", val points: Int = 0, val to: Int = 0, val status: String = "open", @SerialName("done_by") val doneBy: Int = 0,
    val repeats: String = "", @SerialName("opens_at") val opensAt: Double = 0.0,
)

@Serializable
data class ChoresResp(val chores: List<Chore> = emptyList(), val points: Map<String, Int> = emptyMap(), val week: Map<String, Int> = emptyMap(), val streaks: Map<String, Int> = emptyMap())

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
data class ScreenToday(val hidden: Boolean = false, val today: Int = 0, val top: String = "")

@Serializable
data class DeviceInfo(val id: Int = 0, val name: String = "", val kind: String = "windows", @SerialName("last_seen") val lastSeen: Double = 0.0, val status: String = "", val version: String = "")

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


/** A meal on the family meal plan. [via] is "homebase" or the app it came from ("platter"); [cook] is a member id, 0 for nobody yet. */
@Serializable
data class Meal(
    val id: Int,
    val day: String = "",
    val slot: String = "dinner",
    val title: String = "",
    val photo: String = "",
    val source: String = "",
    val servings: Int = 0,
    val ingredients: List<String> = emptyList(),
    val steps: List<String> = emptyList(),
    val via: String = "homebase",
    val by: Int = 0,
    val ext: String = "",
    val cook: Int = 0,
    val suggested: Boolean = false,                                  // a child put it forward and a parent has not said yes yet
)

@Serializable
data class MealsResp(val meals: List<Meal> = emptyList())

@Serializable
data class AddedResp(val added: Int = 0)

/** Friend groups are separate from the family. A child's parents can read them, and a mild-words filter hides rude messages. */
@Serializable
data class GroupPerson(val id: Int = 0, val name: String = "", val emoji: String = "🙂", val role: String = "child")

@Serializable
data class GroupLast(val body: String = "", val at: Double = 0.0, val name: String = "")

@Serializable
data class FriendGroup(
    val id: Int,
    val name: String = "",
    val emoji: String = "👥",
    val status: String? = null,                                     // "active", "pending", or null when a parent is only overseeing
    val code: String? = null,
    val overseeing: List<String> = emptyList(),
    val members: List<GroupPerson> = emptyList(),
    val last: GroupLast? = null,
)

@Serializable
data class GroupAsk(val group: Int = 0, @SerialName("group_name") val groupName: String = "", val member: Int = 0, val name: String = "", val emoji: String = "🙂")

@Serializable
data class GroupsResp(val groups: List<FriendGroup> = emptyList(), val requests: List<GroupAsk> = emptyList())

@Serializable
data class GroupFlag(val word: String = "", val cat: String = "")

@Serializable
data class GroupMessage(
    val id: Int,
    val from: Int = 0,
    val name: String = "",
    val emoji: String = "",
    val kind: String = "text",
    val body: String = "",
    val at: Double = 0.0,
    val flagged: Boolean = false,
    val cats: List<String> = emptyList(),
    val flags: List<GroupFlag> = emptyList(),                       // only a child's own parents get the words and what was really typed
    val original: String = "",
)

@Serializable
data class GroupHead(val id: Int = 0, val name: String = "", val emoji: String = "👥", val members: List<GroupPerson> = emptyList())

@Serializable
data class GroupMessagesResp(val role: String = "", val group: GroupHead = GroupHead(), val messages: List<GroupMessage> = emptyList())

@Serializable
data class GroupSendResp(val id: Int = 0, val body: String = "", val flagged: Boolean = false, val note: String? = null)

@Serializable
data class MakeGroupResp(val id: Int = 0, val code: String = "", val status: String = "")

@Serializable
data class JoinGroupResp(val id: Int = 0, val status: String = "", val name: String = "")

@Serializable
data class Sticker(val name: String = "", val url: String = "")

@Serializable
data class StickersResp(val stickers: List<Sticker> = emptyList())

/** One line of what has been happening in the family. */
@Serializable
data class FeedItem(val id: Int, val kind: String = "", val body: String = "", val at: Double = 0.0, val from: Int = 0, val name: String = "", val emoji: String = "")

@Serializable
data class FeedResp(val items: List<FeedItem> = emptyList())

@Serializable
data class GameStartResp(val url: String = "", val game: String = "")

/** A recipe from the library. [platter] opens it in Platter. */
@Serializable
data class Recipe(
    val id: String,
    val title: String = "",
    val tag: String = "",
    val area: String = "",
    val img: String = "",
    val min: Int = 0,
    val serves: Int = 0,
    val ing: List<String> = emptyList(),
    val steps: List<String> = emptyList(),
    val platter: String = "",
)

@Serializable
data class RecipeTag(val id: String, val name: String = "")

@Serializable
data class RecipesResp(val recipes: List<Recipe> = emptyList(), val tags: List<RecipeTag> = emptyList())
