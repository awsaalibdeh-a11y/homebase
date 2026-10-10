package app.homebase.family

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

private open class Sdp : SdpObserver {
    override fun onCreateSuccess(d: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(e: String?) {}
    override fun onSetFailure(e: String?) {}
}

/** Rings the phone for an incoming call: the ringtone and a buzz, until someone answers or it stops. */
class Ringer(private val ctx: Context) {
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    fun start() {
        stop()
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(ctx, uri)?.apply {
                audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                if (Build.VERSION.SDK_INT >= 28) isLooping = true
                play()
            }
            vibrator = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java).defaultVibrator else @Suppress("DEPRECATION") (ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 600), 0))
        }
    }

    fun stop() {
        runCatching { ringtone?.stop() }
        runCatching { vibrator?.cancel() }
        ringtone = null
    }
}

/** One voice call at a time. The phones agree on how to connect through the server, then the audio goes straight between them. */
class CallManager(private val ctx: Context, private val api: Api, private val state: AppState, private val scope: CoroutineScope) {
    private val ringer = Ringer(ctx)
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var track: AudioTrack? = null
    private var pendingOffer: String? = null
    private var haveRemote = false
    private val remoteQueue = mutableListOf<IceCandidate>()
    private val localQueue = mutableListOf<IceCandidate>()
    private var poller: Job? = null
    private var timeout: Job? = null
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun ensureFactory(): PeerConnectionFactory {
        factory?.let { return it }
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(ctx.applicationContext).createInitializationOptions())
        return PeerConnectionFactory.builder().setAudioDeviceModule(JavaAudioDeviceModule.builder(ctx.applicationContext).createAudioDeviceModule()).createPeerConnectionFactory().also { factory = it }
    }

    private fun servers(): List<PeerConnection.IceServer> {
        val list = state.family.value?.iceServers.orEmpty().map { s ->
            PeerConnection.IceServer.builder(s.urls).apply { if (!s.username.isNullOrEmpty()) setUsername(s.username); if (!s.credential.isNullOrEmpty()) setPassword(s.credential) }.createIceServer()
        }
        return list.ifEmpty { listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()) }
    }

    private fun build(): PeerConnection {
        val f = ensureFactory()
        val conn = f.createPeerConnection(PeerConnection.RTCConfiguration(servers()).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }, object : PeerConnection.Observer {
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
                scope.launch {
                    when (s) {
                        PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> connected()
                        PeerConnection.IceConnectionState.FAILED -> finish("ended", "The connection dropped.")
                        else -> {}
                    }
                }
            }
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(c: IceCandidate?) { c?.let { scope.launch { shipCandidate(it) } } }
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
            override fun onAddStream(s: MediaStream?) {}
            override fun onRemoveStream(s: MediaStream?) {}
            override fun onDataChannel(d: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(r: RtpReceiver?, s: Array<out MediaStream>?) {}
        })!!
        val source = f.createAudioSource(MediaConstraints().apply { mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true")) })
        track = f.createAudioTrack("mic", source).also { conn.addTrack(it, listOf("homebase")) }
        pc = conn
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        audio.isSpeakerphoneOn = state.call.value?.speaker == true
        return conn
    }

    private suspend fun PeerConnection.offer(): SessionDescription = suspendCoroutine { k ->
        createOffer(object : Sdp() {
            override fun onCreateSuccess(d: SessionDescription?) { k.resume(d!!) }
            override fun onCreateFailure(e: String?) { k.resumeWithException(RuntimeException(e)) }
        }, MediaConstraints().apply { mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true")) })
    }

    private suspend fun PeerConnection.answer(): SessionDescription = suspendCoroutine { k ->
        createAnswer(object : Sdp() {
            override fun onCreateSuccess(d: SessionDescription?) { k.resume(d!!) }
            override fun onCreateFailure(e: String?) { k.resumeWithException(RuntimeException(e)) }
        }, MediaConstraints())
    }

    private suspend fun PeerConnection.setLocal(d: SessionDescription) = suspendCoroutine { k ->
        setLocalDescription(object : Sdp() {
            override fun onSetSuccess() { k.resume(Unit) }
            override fun onSetFailure(e: String?) { k.resumeWithException(RuntimeException(e)) }
        }, d)
    }

    private suspend fun PeerConnection.setRemote(d: SessionDescription) = suspendCoroutine { k ->
        setRemoteDescription(object : Sdp() {
            override fun onSetSuccess() {
                haveRemote = true
                remoteQueue.forEach { addIceCandidate(it) }
                remoteQueue.clear()
                k.resume(Unit)
            }
            override fun onSetFailure(e: String?) { k.resumeWithException(RuntimeException(e)) }
        }, d)
    }

    // ---- cleaning up
    private fun closePeer() {
        runCatching { pc?.close() }
        runCatching { pc?.dispose() }
        runCatching { track?.dispose() }
        pc = null; track = null; haveRemote = false; pendingOffer = null
        remoteQueue.clear(); localQueue.clear()
        runCatching { audio.mode = AudioManager.MODE_NORMAL; audio.isSpeakerphoneOn = false }
    }

    private fun finish(status: String, note: String = "") {
        ringer.stop(); poller?.cancel(); timeout?.cancel()
        closePeer()
        val c = state.call.value ?: return
        state.call.value = c.copy(status = status, note = note)
        scope.launch { delay(2500); if (state.call.value?.status == status) state.call.value = null }
    }

    private fun connected() {
        timeout?.cancel(); ringer.stop()
        state.call.update { c -> c?.copy(status = "active", startedAt = if (c.startedAt == 0L) System.currentTimeMillis() else c.startedAt) }
    }

    private suspend fun shipCandidate(c: IceCandidate) {
        val id = state.call.value?.id ?: 0
        if (id == 0) { localQueue += c; return }
        runCatching { api.sendIce(id, buildJsonObject { put("sdpMid", c.sdpMid ?: ""); put("sdpMLineIndex", c.sdpMLineIndex); put("sdp", c.sdp) }) }
    }

    /** The caller's phone checks now and then, in case the "answered" news arrived late. */
    private fun watch(id: Int) {
        poller?.cancel()
        poller = scope.launch {
            while (state.call.value?.id == id) {
                delay(2000)
                val s = runCatching { api.callState(id) }.getOrNull() ?: continue
                val c = state.call.value
                if (s.status == "active" && s.answer != null && c != null && !c.incoming && !haveRemote) applyAnswer(s.answer)
                if (s.status in listOf("declined", "missed", "ended") && state.call.value?.id == id) finish(s.status)
            }
        }
    }

    private suspend fun applyAnswer(sdp: String) {
        state.call.update { it?.copy(status = "connecting") }
        runCatching { pc?.setRemote(SessionDescription(SessionDescription.Type.ANSWER, sdp)) }.onFailure { finish("ended", "Could not connect the call.") }
    }

    // ---- ringing someone
    fun start(peer: Member, urgent: Boolean = false) {
        if (state.call.value != null) return
        state.call.value = CallUi(peerId = peer.id, peerName = peer.name, peerEmoji = peer.emoji, incoming = false, urgent = urgent, status = "dialing")
        scope.launch {
            try {
                val conn = build()
                val offer = conn.offer()
                conn.setLocal(offer)
                val r = api.startCall(peer.id, offer.description, urgent)
                state.call.update { it?.copy(id = r.id, status = "ringing") }
                localQueue.toList().forEach { shipCandidate(it) }
                localQueue.clear()
                watch(r.id)
                timeout = scope.launch { delay(50_000); if (state.call.value?.status == "ringing") hangUp() }
            } catch (e: ApiError) {
                finish(if (e.quiet) "quiet" else "busy", e.message ?: "")
            } catch (e: Throwable) {
                finish("ended", "Could not start the call.")
            }
        }
    }

    // ---- being rung
    fun onIncoming(id: Int, fromId: Int, name: String, emoji: String, offer: String, urgent: Boolean) {
        if (state.call.value != null) { scope.launch { runCatching { api.declineCall(id) } }; return }
        pendingOffer = offer
        state.call.value = CallUi(id = id, peerId = fromId, peerName = name, peerEmoji = emoji, incoming = true, urgent = urgent, status = "ringing")
        ringer.start()
        timeout = scope.launch { delay(46_000); if (state.call.value?.id == id && state.call.value?.status == "ringing") finish("missed") }
    }

    fun accept() {
        val c = state.call.value ?: return
        if (!c.incoming || c.status != "ringing") return
        ringer.stop(); timeout?.cancel()
        state.call.update { it?.copy(status = "connecting") }
        scope.launch {
            try {
                val conn = build()
                conn.setRemote(SessionDescription(SessionDescription.Type.OFFER, pendingOffer ?: ""))
                val ans = conn.answer()
                conn.setLocal(ans)
                api.answerCall(c.id, ans.description)
                localQueue.toList().forEach { shipCandidate(it) }
                localQueue.clear()
                watch(c.id)
            } catch (e: Throwable) {
                finish("ended", "Could not connect the call.")
            }
        }
    }

    fun decline() {
        val c = state.call.value ?: return
        ringer.stop(); timeout?.cancel()
        scope.launch { runCatching { api.declineCall(c.id) } }
        closePeer(); poller?.cancel()
        state.call.value = null
    }

    fun hangUp() {
        val c = state.call.value ?: return
        if (c.id != 0) scope.launch { runCatching { api.endCall(c.id) } }
        ringer.stop(); poller?.cancel(); timeout?.cancel()
        closePeer()
        state.call.value = null
    }

    fun mute(on: Boolean) { track?.setEnabled(!on); state.call.update { it?.copy(muted = on) } }
    fun speaker(on: Boolean) { runCatching { audio.isSpeakerphoneOn = on }; state.call.update { it?.copy(speaker = on) } }

    // ---- news from the server
    fun onUpdate(id: Int, status: String, answer: String?) {
        val c = state.call.value ?: return
        if (c.id != id) return
        when (status) {
            "active" -> if (answer != null && !c.incoming && !haveRemote) scope.launch { applyAnswer(answer) }
            "declined", "missed", "ended" -> finish(status)
        }
    }

    fun onIce(id: Int, cand: JsonObject) {
        if (state.call.value?.id != id) return
        val ic = IceCandidate(cand["sdpMid"]?.jsonPrimitive?.contentOrNull ?: "", cand["sdpMLineIndex"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0, cand["sdp"]?.jsonPrimitive?.contentOrNull ?: return)
        if (haveRemote) pc?.addIceCandidate(ic) else remoteQueue += ic
    }
}
