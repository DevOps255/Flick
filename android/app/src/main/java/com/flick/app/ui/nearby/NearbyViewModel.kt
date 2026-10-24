package com.flick.app.ui.nearby

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flick.app.core.FileItem
import com.flick.app.core.Peer
import com.flick.app.core.PeerDiscovery
import com.flick.app.data.DiscoveryController
import com.flick.app.data.SendSelection
import com.flick.app.data.SensorFlickSource
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
    val micGranted: Boolean = false,
    val peers: List<Peer> = emptyList(),
    val files: List<FileItem> = emptyList(),
) {
    /** The phone a throw goes to: the closest one that is ready, unless the user picked another. */
    fun target(chosenId: String?): Peer? =
        peers.firstOrNull { it.id == chosenId && it.ready } ?: peers.filter { it.ready }.maxByOrNull { it.proximity }
}

@HiltViewModel
class NearbyViewModel @Inject constructor(
    discovery: PeerDiscovery,
    private val controller: DiscoveryController,
    private val friendDao: FriendDao,
    private val selection: SendSelection,
    flickSource: SensorFlickSource,
) : ViewModel() {

    /** One item per Flick. The accelerometer runs only while the screen collects this. */
    val flicks = flickSource.flicks()

    val state: StateFlow<NearbyUiState> = combine(
        controller.enabled,
        controller.micGranted,
        discovery.peers,
        friendDao.observeAll(),
        selection.files,
    ) { on, mic, peers, friends, files ->
        val friendIds = friends.mapTo(HashSet()) { it.flickId }
        NearbyUiState(
            enabled = on,
            micGranted = mic,
            peers = peers.map { it.copy(isFriend = it.isFriend || it.flickId in friendIds) },
            files = files,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NearbyUiState())

    fun setEnabled(value: Boolean) {
        controller.setEnabled(value)
    }

    fun setMicGranted(value: Boolean) {
        controller.setMicGranted(value)
    }

    fun addFriend(peer: Peer) {
        viewModelScope.launch { friendDao.upsert(FriendEntity(peer.flickId, peer.name)) }
    }

    fun onFilesPicked(uris: List<Uri>) = selection.select(uris)
}
