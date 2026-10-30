package com.flick.app.ui.nearby

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flick.app.core.FileItem
import com.flick.app.core.Peer
import com.flick.app.core.PeerDiscovery
import com.flick.app.data.BluetoothStatus
import com.flick.app.data.DiscoveryController
import com.flick.app.data.SendSelection
import com.flick.app.data.SensorFlickSource
import com.flick.app.data.ShareMode
import com.flick.app.data.WifiStatus
import com.flick.app.data.nearby.NearbyDiscovery
import com.flick.app.data.db.FriendDao
import com.flick.app.data.db.FriendEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class NearbyUiState(
    val enabled: Boolean = true,
    val permissionsGranted: Boolean = false,
    val peers: List<Peer> = emptyList(),
    val files: List<FileItem> = emptyList(),
    val multiple: Boolean = false,
    val wifiOn: Boolean = true,
    val bluetoothOn: Boolean = true,
) {
    /** The phones a throw goes to: every ready phone when sharing with several, otherwise only the closest. */
    fun targets(chosenId: String?): List<Peer> =
        if (multiple) peers.filter { it.ready } else listOfNotNull(target(chosenId))

    /** The phone a throw goes to: the closest one that is ready, unless the user picked another. */
    fun target(chosenId: String?): Peer? =
        peers.firstOrNull { it.id == chosenId && it.ready } ?: peers.filter { it.ready }.maxByOrNull { it.proximity }
}

@HiltViewModel
class NearbyViewModel @Inject constructor(
    discovery: PeerDiscovery,
    private val controller: DiscoveryController,
    private val shareMode: ShareMode,
    wifi: WifiStatus,
    bluetooth: BluetoothStatus,
    nearby: NearbyDiscovery,
    private val friendDao: FriendDao,
    private val selection: SendSelection,
    flickSource: SensorFlickSource,
) : ViewModel() {

    /** Bluetooth and pairing state, for the diagnostics card. */
    val diagnostics = nearby.diagnostics

    /** Emits when the user paired with a phone and the link to it is up. */
    val associations = discovery.associations

    /** One item per Flick. The accelerometer runs only while the screen collects this. */
    val flicks = flickSource.flicks()

    val state: StateFlow<NearbyUiState> = combine(
        controller.enabled,
        controller.permissionsGranted,
        discovery.peers,
        friendDao.observeAll(),
        selection.files,
        shareMode.multiple,
        wifi.enabled,
        bluetooth.enabled,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val peers = values[2] as List<Peer>
        @Suppress("UNCHECKED_CAST")
        val friends = values[3] as List<FriendEntity>
        @Suppress("UNCHECKED_CAST")
        val files = values[4] as List<FileItem>
        val on = values[0] as Boolean
        val mic = values[1] as Boolean
        val friendIds = friends.mapTo(HashSet()) { it.flickId }
        NearbyUiState(
            enabled = on,
            permissionsGranted = mic,
            peers = peers.map { it.copy(isFriend = it.isFriend || it.flickId in friendIds) },
            files = files,
            multiple = values[5] as Boolean,
            wifiOn = values[6] as Boolean,
            bluetoothOn = values[7] as Boolean,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NearbyUiState())

    fun setEnabled(value: Boolean) {
        controller.setEnabled(value)
    }

    fun setMultiple(value: Boolean) = shareMode.setMultiple(value)

    fun setPermissionsGranted(value: Boolean) {
        controller.setPermissionsGranted(value)
    }

    fun addFriend(peer: Peer) {
        viewModelScope.launch { friendDao.upsert(FriendEntity(peer.flickId, peer.name)) }
    }

    fun onFilesPicked(uris: List<Uri>) = selection.select(uris)
}
