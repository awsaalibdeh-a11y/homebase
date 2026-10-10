package app.homebase.family

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

data class PingAsk(val id: Int, val fromId: Int, val name: String, val emoji: String)

/** What the call screen shows. `status`: dialing, ringing, connecting, active, ended, declined, missed, busy, quiet. */
data class CallUi(
    val id: Int = 0,
    val peerId: Int,
    val peerName: String,
    val peerEmoji: String,
    val incoming: Boolean,
    val urgent: Boolean = false,
    val status: String,
    val startedAt: Long = 0,
    val muted: Boolean = false,
    val speaker: Boolean = false,
    val note: String = "",
)

/** Whether the phone could reach the server the last time it tried. Read by the screens to say "Last known" instead of "Live". */
object Connectivity { @Volatile var online = true }

/** The family as this phone knows it right now. The service and the screens both read and write it. */
class AppState(val store: Store, val api: Api) {
    private val cacheJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val family = MutableStateFlow<FamilyResp?>(runCatching { store.familyCache.takeIf { it.isNotEmpty() }?.let { cacheJson.decodeFromString<FamilyResp>(it) } }.getOrNull())   // what we saw last, so the map is never empty offline
    val online = MutableStateFlow(true)
    val threads = MutableStateFlow<Map<Int, List<Message>>>(emptyMap())
    val items = MutableStateFlow<List<Item>>(emptyList())
    val call = MutableStateFlow<CallUi?>(null)
    val pingAsk = MutableStateFlow<PingAsk?>(null)
    val openThread = MutableStateFlow<Int?>(null)
    val flash = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val showOnMap = MutableSharedFlow<Spot>(extraBufferCapacity = 4)

    val me: Member? get() = family.value?.members?.firstOrNull { it.id == store.myId }
    fun member(id: Int): Member? = family.value?.members?.firstOrNull { it.id == id }

    suspend fun refreshFamily(): Boolean {
        val r = runCatching { api.family() }
        Connectivity.online = r.isSuccess
        online.value = r.isSuccess
        r.onSuccess {
            family.value = it
            store.familyName = it.family.name
            runCatching { store.familyCache = cacheJson.encodeToString(FamilyResp.serializer(), it) }
        }
        return r.isSuccess
    }

    suspend fun loadThread(with: Int) {
        runCatching { api.messages(with, 0) }.onSuccess { list -> threads.update { it + (with to list) } }
    }

    /** Which conversation a message belongs to: 0 is the whole family, otherwise the other person. */
    fun threadOf(m: Message): Int = if (m.to == 0) 0 else if (m.from == store.myId) m.to else m.from

    fun add(m: Message) {
        val key = threadOf(m)
        threads.update { all ->
            val have = all[key].orEmpty()
            if (have.any { it.id == m.id }) all else all + (key to (have + m).sortedBy { it.id })
        }
    }

    suspend fun refreshItems() {
        runCatching { api.items() }.onSuccess { items.value = it }
    }

    fun updateLocation(memberId: Int, spot: Spot, battery: Int) {
        val f = family.value ?: return
        family.value = f.copy(members = f.members.map { if (it.id == memberId) it.copy(location = spot, battery = if (battery >= 0) battery else it.battery, share = "on") else it })
    }
}

class HomebaseApp : Application() {
    lateinit var store: Store
    lateinit var api: Api
    lateinit var state: AppState
    lateinit var calls: CallManager
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        api = Api(store)
        state = AppState(store, api)
        calls = CallManager(this, api, state, scope)
        Notifications.createChannels(this)
    }
}

val Context.hb: HomebaseApp get() = applicationContext as HomebaseApp
