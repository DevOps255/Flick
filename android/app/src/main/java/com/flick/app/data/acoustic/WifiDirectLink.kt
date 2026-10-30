package com.flick.app.data.acoustic

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import uniffi.flick_ffi.LinkPlanData

/**
 * Forms a Wi-Fi Direct group between two phones without any prompt.
 *
 * Both phones already agreed on the group name, the password and who creates
 * the group (see `flick-core/src/link.rs`), so no negotiation happens over the
 * air. The creator opens the group with that name and password. The other
 * phone joins it with the same values.
 *
 * Android only allows this with the nearby devices permission (Android 13 and
 * later) or the location permission (older versions).
 */
@Singleton
class WifiDirectLink @Inject constructor(@ApplicationContext private val context: Context) {

    /** The group this phone is now part of. */
    class Joined(
        /** True when this phone created the group. */
        val isOwner: Boolean,
        /** IPv4 address of the group owner. The other phone reaches the owner's server there. */
        val ownerAddress: ByteArray,
    )

    private val manager: WifiP2pManager? = context.getSystemService(WifiP2pManager::class.java)
    private var channel: WifiP2pManager.Channel? = null
    private val lock = Mutex()

    fun hasPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Creates or joins the group described by [plan]. Returns once the group exists.
     * Throws with a message fit for the user when it cannot.
     */
    @SuppressLint("MissingPermission")
    suspend fun establish(plan: LinkPlanData): Joined = lock.withLock {
        val (ssid, passphrase, create) = when (plan) {
            is LinkPlanData.CreateGroup -> Triple(plan.ssid, plan.passphrase, true)
            is LinkPlanData.JoinGroup -> Triple(plan.ssid, plan.passphrase, false)
            LinkPlanData.SameNetwork -> error("Both phones are already on the same network.")
        }
        check(hasPermission()) { "Allow nearby devices so Flick can connect the phones." }
        val p2p = checkNotNull(manager) { "Wi-Fi Direct is not available on this phone." }
        val ch = channel ?: p2p.initialize(context, Looper.getMainLooper(), null).also { channel = it }

        // A group left over from an earlier session would make createGroup and connect fail as busy.
        runCatching { await { p2p.removeGroup(ch, it) } }

        val config = WifiP2pConfig.Builder().setNetworkName(ssid).setPassphrase(passphrase).build()
        await { if (create) p2p.createGroup(ch, config, it) else p2p.connect(ch, config, it) }
        withTimeout(GROUP_TIMEOUT_MS) { waitForGroup(p2p, ch) }
    }

    /** Leaves the group. Safe to call when there is none. */
    fun release() {
        val ch = channel ?: return
        channel = null
        manager?.removeGroup(ch, null)
        ch.close()
    }

    private suspend fun waitForGroup(p2p: WifiP2pManager, ch: WifiP2pManager.Channel): Joined {
        while (true) {
            val info = connectionInfo(p2p, ch)
            val owner = info.groupOwnerAddress?.address
            if (info.groupFormed && owner != null && owner.size == IPV4_BYTES) return Joined(info.isGroupOwner, owner)
            delay(POLL_MS)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectionInfo(p2p: WifiP2pManager, ch: WifiP2pManager.Channel): WifiP2pInfo =
        suspendCancellableCoroutine { cont -> p2p.requestConnectionInfo(ch) { cont.resume(it) } }

    private suspend fun await(start: (WifiP2pManager.ActionListener) -> Unit) = suspendCancellableCoroutine { cont ->
        start(object : WifiP2pManager.ActionListener {
            override fun onSuccess() = cont.resume(Unit)

            override fun onFailure(reason: Int) = cont.resumeWithException(IllegalStateException(explain(reason)))
        })
    }

    private fun explain(reason: Int) = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct is not supported on this phone."
        WifiP2pManager.BUSY -> "Wi-Fi Direct is busy. Turn Wi-Fi off and on, then try again."
        else -> "Wi-Fi Direct failed. Check that Wi-Fi is on."
    }

    private companion object {
        const val GROUP_TIMEOUT_MS = 25_000L
        const val POLL_MS = 500L
        const val IPV4_BYTES = 4
    }
}
