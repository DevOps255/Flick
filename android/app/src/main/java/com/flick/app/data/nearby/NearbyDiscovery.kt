package com.flick.app.data.nearby

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.flick.app.core.Peer
import com.flick.app.core.PeerDiscovery
import com.flick.app.data.NodeRepository
import com.flick.app.data.Problems
import com.flick.app.data.ShareMode
import com.flick.app.data.WifiStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import uniffi.flick_ffi.AnnouncementData
import uniffi.flick_ffi.BleFrameData
import uniffi.flick_ffi.FlickNode
import uniffi.flick_ffi.LinkPlanData
import uniffi.flick_ffi.LocalLinkData
import uniffi.flick_ffi.NearbyPhone
import uniffi.flick_ffi.PairingEngine
import uniffi.flick_ffi.PairingResult
import uniffi.flick_ffi.bleFrameDecode
import uniffi.flick_ffi.bleFrameEncode
import uniffi.flick_ffi.bleMagic
import uniffi.flick_ffi.bleManufacturerId
import uniffi.flick_ffi.planLink

/** What Bluetooth and the pairing are doing right now. Shown on the Nearby screen to find out why phones do not see each other. */
data class Diagnostics(
    /** Bluetooth state: off, ready, or the reason it cannot start. */
    val bluetooth: String = "not started",
    val advertising: Boolean = false,
    val scanning: Boolean = false,
    /** Frames received from other phones since the search started. */
    val framesHeard: Int = 0,
    /** Signal strength of the closest phone, in dBm. Closer to zero is closer. */
    val strongestRssi: Int? = null,
    /** Times this phone detected the pairing gesture. */
    val gestures: Int = 0,
    /** How long the last phone took from first seen to ready, in milliseconds. The goal is under 3000. */
    val lastReadyMs: Long? = null,
    /** Why the last gesture did not pair, when it did not. */
    val hint: String? = null,
    /** False when the phone has no address on any network. */
    val hasAddress: Boolean = false,
)

/**
 * Finds nearby phones over Bluetooth Low Energy and pairs with the one the user means.
 *
 * Every phone advertises a small frame (see `flick-proto/src/ble.rs`) and scans for the others, from
 * the moment the app opens. Bluetooth tells which phones are close and how close. It does not carry
 * files. A phone that is very near is linked ahead of time, over the same Wi-Fi or a Wi-Fi Direct
 * group, so the link is already up when the user makes the gesture. The gesture then says which
 * phone is meant: both users tilt their phones, each phone shows "gesture" in its frame, and the
 * closest phone that gestured at the same moment is paired (`flick-proto/src/pairing.rs`).
 *
 * A phone is ready once it answered over TLS with the announced certificate.
 */
@Singleton
class NearbyDiscovery @Inject constructor(
    @ApplicationContext private val context: Context,
    private val nodes: NodeRepository,
    private val link: WifiDirectLink,
    private val shareMode: ShareMode,
    private val wifi: WifiStatus,
    private val problems: Problems,
) : PeerDiscovery {

    private class Heard(
        var announcement: AnnouncementData,
        var proximity: Float,
        var rssi: Float,
        val firstSeenMs: Long,
    ) {
        var name: String? = null
        var ready = false
        var note: String? = null
        var plan: LinkPlanData? = null
        var linking = false
        var describing = false
        var paired = false
        var associated = false

        /** Set when this phone joined the other phone's Wi-Fi Direct group: where the other phone's server is. */
        var ownerAddress: ByteArray? = null

        /** Set when this phone created the group: the other phone's address comes from its next frame. */
        var ownsGroup = false
        var retryAfterMs = 0L
    }

    private val heard = ConcurrentHashMap<String, Heard>()
    private val state = MutableStateFlow<List<Peer>>(emptyList())
    private val _associations = MutableSharedFlow<Peer>(extraBufferCapacity = 4)
    private val _diagnostics = MutableStateFlow(Diagnostics())
    private val random = SecureRandom()
    private val nonce = ByteArray(4).also(random::nextBytes)
    private var scope: CoroutineScope? = null
    private var node: FlickNode? = null
    private var engine: PairingEngine? = null
    private var ownPrefix = ByteArray(0)
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var scanCallback: ScanCallback? = null
    private var gestureSeq = 0
    private var gestureUntilMs = 0L
    private var advertisedKey = ""
    private var framesHeard = 0
    private var gestures = 0

    override val peers: Flow<List<Peer>> = state
    override val associations: Flow<Peer> = _associations.asSharedFlow()

    /** Live state of Bluetooth and the pairing, for the diagnostics card. */
    val diagnostics: StateFlow<Diagnostics> = _diagnostics.asStateFlow()

    /** The announcement to send to. Points at the group owner's address once this phone joined its group. */
    fun announcementOf(peerId: String): AnnouncementData? = heard[peerId]?.reachable()

    override suspend fun start() {
        stop()
        if (!hasBluetoothPermission()) {
            update { it.copy(bluetooth = "permission missing") }
            return
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            update { it.copy(bluetooth = "off") }
            return
        }
        val running = nodes.node().also { node = it }
        val pairing = PairingEngine().also { engine = it }
        ownPrefix = running.announcement(LocalAddress.ipv4() ?: ByteArray(4), nonce).fingerprintPrefix
        val job = CoroutineScope(SupervisorJob() + Dispatchers.Default + problems.handler("Nearby search"))
        scope = job
        advertiser = adapter.bluetoothLeAdvertiser
        scanner = adapter.bluetoothLeScanner
        update { it.copy(bluetooth = "ready") }
        startScan(pairing)
        advertise(running, force = true)

        job.launch {
            while (isActive) {
                tick(running, pairing, job)
                delay(TICK_MS)
            }
        }
        job.launch { shareMode.multiple.collect { refreshTrust() } }
        job.launch { wifi.enabled.collect { on -> if (on) heard.keys.forEach { connect(running, it, job) } } }
    }

    override suspend fun stop() {
        scope?.cancel()
        scope = null
        stopBluetooth()
        node = null
        engine = null
        framesHeard = 0
        gestures = 0
        gestureUntilMs = 0
        advertisedKey = ""
        _diagnostics.value = Diagnostics()
        heard.clear()
        state.value = emptyList()
        link.release()
    }

    /** The user tilted this phone: tell the other phones through the next frame, and look for a phone that did the same. */
    override fun onPairingGesture() {
        val running = node ?: return
        val pairing = engine ?: return
        val now = SystemClock.elapsedRealtime()
        gestures++
        gestureSeq = (gestureSeq + 1) and 0xFF
        gestureUntilMs = now + GESTURE_SHOWN_MS
        pairing.onOwnGesture(now.toULong())
        advertise(running, force = true)
    }

    // ---------------------------------------------------------------- Bluetooth

    private fun hasBluetoothPermission(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return needed.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }

    @SuppressLint("MissingPermission")
    private fun startScan(pairing: PairingEngine) {
        val filter = ScanFilter.Builder()
            .setManufacturerData(bleManufacturerId().toInt(), byteArrayOf(bleMagic().toByte()), byteArrayOf(0xFF.toByte()))
            .build()
        // Low latency scans as fast as Bluetooth allows. Phones are heard within a fraction of a second.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0)
            .build()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val bytes = result.scanRecord?.getManufacturerSpecificData(bleManufacturerId().toInt()) ?: return
                val frame = bleFrameDecode(bytes) ?: return
                if (frame.announcement.fingerprintPrefix.contentEquals(ownPrefix)) return
                framesHeard++
                pairing.onFrame(frame, result.rssi, SystemClock.elapsedRealtime().toULong())
            }

            override fun onScanFailed(errorCode: Int) {
                update { it.copy(scanning = false, bluetooth = "scan failed, code $errorCode") }
                problems.report("Bluetooth scan", IllegalStateException("error code $errorCode"))
            }
        }
        scanCallback = callback
        scanner?.startScan(listOf(filter), settings, callback)
        update { it.copy(scanning = true) }
    }

    /**
     * Starts or restarts the advertisement. A legacy advertisement cannot be edited, so a change
     * of address or of gesture means stopping and starting it again, which takes a few milliseconds.
     */
    @SuppressLint("MissingPermission")
    private fun advertise(running: FlickNode, force: Boolean) {
        val ip = LocalAddress.ipv4() ?: ByteArray(4)
        val gesture = SystemClock.elapsedRealtime() < gestureUntilMs
        val key = "${ip.joinToString(".") { (it.toInt() and 0xFF).toString() }}/$gesture/$gestureSeq/${running.port()}"
        if (!force && key == advertisedKey) return
        advertisedKey = key
        val adv = advertiser ?: return
        val frame = BleFrameData(running.announcement(ip, nonce), gesture, gestureSeq.toUByte())
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(false)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addManufacturerData(bleManufacturerId().toInt(), bleFrameEncode(frame))
            .build()
        advertiseCallback?.let { adv.stopAdvertising(it) }
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                update { it.copy(advertising = true) }
            }

            override fun onStartFailure(errorCode: Int) {
                update { it.copy(advertising = false, bluetooth = "advertising failed, code $errorCode") }
                problems.report("Bluetooth advertising", IllegalStateException("error code $errorCode"))
            }
        }
        advertiseCallback = callback
        adv.startAdvertising(settings, data, callback)
    }

    @SuppressLint("MissingPermission")
    private fun stopBluetooth() {
        runCatching { advertiseCallback?.let { advertiser?.stopAdvertising(it) } }
        runCatching { scanCallback?.let { scanner?.stopScan(it) } }
        advertiseCallback = null
        scanCallback = null
        advertiser = null
        scanner = null
    }

    // ---------------------------------------------------------------- one step, ten times a second

    private fun tick(running: FlickNode, pairing: PairingEngine, job: CoroutineScope) {
        val now = SystemClock.elapsedRealtime()
        if (gestureUntilMs in 1..now) {
            gestureUntilMs = 0
            advertise(running, force = true)
        } else {
            advertise(running, force = false)
        }

        val phones = pairing.phones(now.toULong())
        syncHeard(phones)
        publish()
        refreshTrust()

        when (val result = pairing.poll(now.toULong())) {
            is PairingResult.Paired -> {
                val h = heard[result.phone.id]
                if (h != null) {
                    h.paired = true
                    update { it.copy(hint = null) }
                }
            }
            PairingResult.Ambiguous -> update { it.copy(hint = "Several phones are close. Move nearer to the one you mean, then tilt again.") }
            PairingResult.Pending -> Unit
        }

        for ((id, h) in heard) {
            // A phone very near is linked before the gesture, so the link is ready when it happens.
            if (h.paired || h.rssi >= PREWARM_RSSI) connect(running, id, job)
            if (h.paired && h.ready && !h.associated) {
                h.associated = true
                _associations.tryEmit(h.toPeer(id))
            }
        }
        update { it.copy(framesHeard = framesHeard, strongestRssi = phones.firstOrNull()?.rssiDbm?.toInt(), gestures = gestures, hasAddress = LocalAddress.ipv4() != null) }
    }

    private fun syncHeard(phones: List<NearbyPhone>) {
        val ids = phones.map { it.id }.toSet()
        heard.keys.retainAll(ids)
        for (p in phones) {
            val existing = heard[p.id]
            if (existing == null) {
                heard[p.id] = Heard(p.announcement, p.proximity, p.rssiDbm, SystemClock.elapsedRealtime())
            } else {
                // The address changes when the other phone joins a group, so keep the latest one.
                existing.announcement = p.announcement
                existing.proximity = p.proximity
                existing.rssi = p.rssiDbm
            }
        }
    }

    /**
     * Tells the node which phones may send here. By default only the closest one,
     * and every phone nearby when the user chose to share with several.
     */
    private fun refreshTrust() {
        val running = node ?: return
        val all = heard.values.toList()
        val allowed = if (shareMode.multiple.value) all else listOfNotNull(all.maxByOrNull { it.proximity })
        runCatching { running.setTrusted(allowed.map { it.announcement }) }
    }

    // ---------------------------------------------------------------- reaching a phone

    /** Moves a phone one step closer to ready. Called again at every step until it is. */
    private fun connect(running: FlickNode, id: String, job: CoroutineScope) {
        val h = heard[id] ?: return
        if (h.ready || h.linking || h.describing || SystemClock.elapsedRealtime() < h.retryAfterMs) return
        val plan = h.plan ?: planLink(ownPrefix, ownLinks(), h.announcement).also { h.plan = it }
        if (plan != LinkPlanData.SameNetwork && h.ownerAddress == null && !h.ownsGroup && !wifi.isEnabled()) {
            // Android does not let an app turn Wi-Fi on. The screen offers the one tap panel instead.
            h.note = WifiDirectLink.WIFI_OFF
            return
        }
        val linked = plan == LinkPlanData.SameNetwork || h.ownerAddress != null || h.ownsGroup
        if (linked) describe(running, h, job) else startLink(running, id, h, plan, job)
    }

    private fun startLink(running: FlickNode, id: String, h: Heard, plan: LinkPlanData, job: CoroutineScope) {
        h.linking = true
        h.note = "Connecting"
        job.launch(Dispatchers.IO) {
            try {
                val joined = link.establish(plan)
                h.ownsGroup = joined.isOwner
                h.ownerAddress = if (joined.isOwner) null else joined.ownerAddress
                h.note = null
            } catch (e: Exception) {
                h.note = e.message ?: "Could not connect"
                h.plan = null
                h.retryAfterMs = SystemClock.elapsedRealtime() + LINK_RETRY_MS
            } finally {
                h.linking = false
            }
            connect(running, id, job)
        }
    }

    /** The TLS answer proves the phone holds the announced certificate. */
    private fun describe(running: FlickNode, h: Heard, job: CoroutineScope) {
        h.describing = true
        job.launch(Dispatchers.IO) {
            val name = runCatching { running.describe(h.reachable()) }.getOrNull()
            if (name != null) {
                h.name = name
                h.ready = true
                h.note = null
                val took = SystemClock.elapsedRealtime() - h.firstSeenMs
                update { it.copy(lastReadyMs = took) }
            } else {
                h.retryAfterMs = SystemClock.elapsedRealtime() + DESCRIBE_RETRY_MS
            }
            h.describing = false
        }
    }

    private fun Heard.reachable(): AnnouncementData = ownerAddress?.let { announcement.copy(ipv4 = it) } ?: announcement

    private fun Heard.toPeer(id: String) = Peer(
        id = id,
        name = name ?: "Nearby phone",
        flickId = id.take(8),
        isFriend = false,
        proximity = proximity,
        ready = ready,
        note = note,
    )

    private fun publish() {
        state.value = heard.map { (id, h) -> h.toPeer(id) }.sortedByDescending { it.proximity }
    }

    private fun ownLinks() = LocalAddress.links().map { LocalLinkData(it.ip, it.prefixLength.toUByte()) }

    private fun update(change: (Diagnostics) -> Diagnostics) {
        _diagnostics.value = change(_diagnostics.value)
    }

    private companion object {
        const val TICK_MS = 100L
        const val GESTURE_SHOWN_MS = 2_500L

        /** Signal strength above which a phone is close enough, about a metre, to link it before any gesture. */
        const val PREWARM_RSSI = -62f
        const val LINK_RETRY_MS = 10_000L
        const val DESCRIBE_RETRY_MS = 1_500L
    }
}
