package com.flick.app.ui.nearby

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flick.app.core.Peer
import com.flick.app.core.PeerDiscovery
import com.flick.app.data.SendSelection
import com.flick.app.data.db.FriendDao
import com.flick.app.data.db.FriendEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class NearbyUiState(
    val enabled: Boolean = true,
    val peers: List<Peer> = emptyList(),
    val hasSelection: Boolean = false,
)

@HiltViewModel
class NearbyViewModel @Inject constructor(
    private val discovery: PeerDiscovery,
    private val friendDao: FriendDao,
    private val selection: SendSelection,
) : ViewModel() {

    private val enabled = MutableStateFlow(true)

    val state: StateFlow<NearbyUiState> = combine(
        enabled,
        discovery.peers,
        friendDao.observeAll(),
        selection.files,
    ) { on, peers, friends, files ->
        val friendIds = friends.mapTo(HashSet()) { it.flickId }
        NearbyUiState(
            enabled = on,
            peers = peers.map { it.copy(isFriend = it.isFriend || it.flickId in friendIds) },
            hasSelection = files.isNotEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NearbyUiState())

    init {
        // Discovery runs only while the toggle is on and this screen's state exists.
        viewModelScope.launch {
            enabled.collectLatest { on ->
                if (on) {
                    discovery.start()
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) { discovery.stop() }
                    }
                }
            }
        }
    }

    fun setEnabled(value: Boolean) {
        enabled.value = value
    }

    fun addFriend(peer: Peer) {
        viewModelScope.launch { friendDao.upsert(FriendEntity(peer.flickId, peer.name)) }
    }

    fun onFilesPicked(uris: List<Uri>) = selection.select(uris)
}
