package com.flick.app.data.acoustic

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.flick.app.core.Peer
import com.flick.app.core.PeerDiscovery
import com.flick.app.data.NodeRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import uniffi.flick_ffi.AnnouncementData
import uniffi.flick_ffi.FlickNode
import uniffi.flick_ffi.LinkPlanData
import uniffi.flick_ffi.LocalLinkData
import uniffi.flick_ffi.SoundListener
import uniffi.flick_ffi.announcementToSound
import uniffi.flick_ffi.planLink
import uniffi.flick_ffi.soundSampleRate

/**
 * Finds nearby phones by sound and connects to them.
 *
 * Each phone plays its announcement every few seconds and listens for the
 * others. A phone counts as nearby while its announcement was heard in the
 * last [FORGET_AFTER_MS].
 *
 * Reaching a phone takes two steps. First the link: nothing when both phones
 * share a network, otherwise a Wi-Fi Direct group (see [WifiDirectLink]).
 * Then the check: the phone must answer over TLS with the announced
 * certificate. Only then is it ready.
 */
@Singleton
class AcousticDiscovery @Inject constructor(
    @ApplicationContext private val context: Context,
    private val nodes: NodeRepository,
    private val link: WifiDirectLink,
) : PeerDiscovery {

    private class Heard(
        var announcement: AnnouncementData,
        var lastHeardMs: Long,
        var proximity: Float,
    ) {
        var name: String? = null
        var ready = false
        var note: String? = null
        var plan: LinkPlanData? = null
        var linking = false
        var describing = false

        /** Set when this phone joined the other phone's group: where the other phone's server is. */
        var ownerAddress: ByteArray? = null

        /** Set when this phone created the group: the other phone's address is read from its next announcement. */
        var ownsGroup = false
        var retryAfterMs = 0L
    }

    private val heard = ConcurrentHashMap<String, Heard>()
    private val state = MutableStateFlow<List<Peer>>(emptyList())
    private var scope: CoroutineScope? = null
    private var ownPrefix = ByteArray(0)
    private val random = SecureRandom()

    override val peers: Flow<List<Peer>> = state

    /** The announcement to send to. Points at the group owner's address once this phone joined its group. */
    fun announcementOf(peerId: String): AnnouncementData? = heard[peerId]?.reachable()

    override suspend fun start() {
        stop()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val node = nodes.node()
        ownPrefix = node.announcement(ownAddress(), randomNonce()).fingerprintPrefix
        val job = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = job
        job.launch(Dispatchers.IO) { listen(node, job) }
        job.launch(Dispatchers.IO) { announce(node) }
        job.launch {
            while (isActive) {
                delay(PRUNE_EVERY_MS)
                val limit = System.currentTimeMillis() - FORGET_AFTER_MS
                if (heard.entries.removeIf { it.value.lastHeardMs < limit }) publish()
            }
        }
    }

    override suspend fun stop() {
        scope?.cancel()
        scope = null
        heard.clear()
        state.value = emptyList()
        link.release()
    }

    @SuppressLint("MissingPermission")
    private suspend fun listen(node: FlickNode, job: CoroutineScope) {
        val rate = soundSampleRate().toInt()
        val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val record = AudioRecord(
            MediaRecorder.AudioSource.UNPROCESSED,
            rate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
            max(minBuffer, CHUNK * 4 * 4),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return
        }
        val listener = SoundListener()
        val chunk = FloatArray(CHUNK)
        val recentPeaks = ArrayDeque<Float>()
        record.startRecording()
        try {
            while (job.isActive) {
                val n = record.read(chunk, 0, CHUNK, AudioRecord.READ_BLOCKING)
                if (n <= 0) continue
                recentPeaks.addLast(chunk.take(n).maxOf { abs(it) })
                if (recentPeaks.size > PEAK_WINDOW) recentPeaks.removeFirst()
                val a = listener.push(chunk.take(n)) ?: continue
                if (a.fingerprintPrefix.contentEquals(ownPrefix)) continue
                onHeard(node, a, proximityFromLoudness(recentPeaks.max()), job)
            }
        } finally {
            record.stop()
            record.release()
        }
    }

    private fun onHeard(node: FlickNode, a: AnnouncementData, proximity: Float, job: CoroutineScope) {
        val id = idOf(a)
        val now = System.currentTimeMillis()
        val existing = heard[id]
        if (existing == null) {
            node.trust(a)
            heard[id] = Heard(a, now, proximity)
        } else {
            // The address changes when the other phone joins a group, so keep the latest one.
            existing.announcement = a
            existing.lastHeardMs = now
            existing.proximity = proximity
        }
        publish()
        connect(node, id, job)
    }

    /** Moves a phone one step closer to ready. Called again on every announcement until it is. */
    private fun connect(node: FlickNode, id: String, job: CoroutineScope) {
        val h = heard[id] ?: return
        if (h.ready || h.linking || h.describing || System.currentTimeMillis() < h.retryAfterMs) return
        val plan = h.plan ?: planLink(ownPrefix, ownLinks(), h.announcement).also { h.plan = it }
        val linked = plan == LinkPlanData.SameNetwork || h.ownerAddress != null || h.ownsGroup
        if (linked) describe(node, id, h, job) else startLink(node, id, h, plan, job)
    }

    private fun startLink(node: FlickNode, id: String, h: Heard, plan: LinkPlanData, job: CoroutineScope) {
        h.linking = true
        h.note = "Connecting"
        publish()
        job.launch(Dispatchers.IO) {
            try {
                val joined = link.establish(plan)
                h.ownsGroup = joined.isOwner
                h.ownerAddress = if (joined.isOwner) null else joined.ownerAddress
                h.note = null
            } catch (e: Exception) {
                h.note = e.message ?: "Could not connect"
                h.plan = null
                h.retryAfterMs = System.currentTimeMillis() + LINK_RETRY_MS
            } finally {
                h.linking = false
                publish()
            }
            connect(node, id, job)
        }
    }

    /** The TLS answer proves the phone holds the announced certificate. */
    private fun describe(node: FlickNode, id: String, h: Heard, job: CoroutineScope) {
        h.describing = true
        job.launch(Dispatchers.IO) {
            val name = runCatching { node.describe(h.reachable()) }.getOrNull()
            if (name != null) {
                h.name = name
                h.ready = true
                h.note = null
            } else {
                h.retryAfterMs = System.currentTimeMillis() + DESCRIBE_RETRY_MS
            }
            h.describing = false
            publish()
        }
    }

    private fun Heard.reachable(): AnnouncementData =
        ownerAddress?.let { announcement.copy(ipv4 = it) } ?: announcement

    private suspend fun announce(node: FlickNode) {
        while (scope?.isActive == true) {
            val ip = LocalAddress.ipv4()
            if (ip != null) {
                play(announcementToSound(node.announcement(ip, randomNonce())).toFloatArray())
            }
            delay(2_000L + random.nextInt(2_000))
        }
    }

    private suspend fun play(sound: FloatArray) {
        val rate = soundSampleRate().toInt()
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(sound.size * Float.SIZE_BYTES)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        try {
            track.write(sound, 0, sound.size, AudioTrack.WRITE_BLOCKING)
            track.play()
            delay(sound.size * 1000L / rate + 150)
        } finally {
            track.release()
        }
    }

    private fun publish() {
        state.value = heard.map { (id, h) ->
            Peer(
                id = id,
                name = h.name ?: "Nearby phone",
                flickId = id.take(8),
                isFriend = false,
                proximity = h.proximity,
                ready = h.ready,
                note = h.note,
            )
        }.sortedByDescending { it.proximity }
    }

    private fun idOf(a: AnnouncementData) = a.fingerprintPrefix.joinToString("") { "%02X".format(it.toInt() and 0xFF) }

    private fun ownLinks() = LocalAddress.links().map { LocalLinkData(it.ip, it.prefixLength.toUByte()) }

    private fun ownAddress() = LocalAddress.ipv4() ?: ByteArray(4)

    private fun randomNonce() = ByteArray(4).also(random::nextBytes)

    /**
     * A rough estimate, not a measurement. Louder means closer, mapped on a log scale
     * from a faint signal (0.005) to a very close one (0.3). Real ranging comes later.
     */
    private fun proximityFromLoudness(peak: Float): Float {
        val low = ln(0.005f)
        val high = ln(0.3f)
        return ((ln(max(peak, 0.0001f)) - low) / (high - low)).coerceIn(0f, 1f)
    }

    private companion object {
        const val CHUNK = 2048
        const val PEAK_WINDOW = 40
        const val FORGET_AFTER_MS = 12_000L
        const val PRUNE_EVERY_MS = 1_000L
        const val LINK_RETRY_MS = 10_000L
        const val DESCRIBE_RETRY_MS = 2_000L
    }
}
