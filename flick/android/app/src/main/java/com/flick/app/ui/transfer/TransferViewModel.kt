package com.flick.app.ui.transfer

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.flick.app.core.PeerDiscovery
import com.flick.app.core.TransferEngine
import com.flick.app.core.TransferProgress
import com.flick.app.core.TransferStatus
import com.flick.app.data.SendSelection
import com.flick.app.data.db.TransferDao
import com.flick.app.data.db.TransferEntity
import com.flick.app.ui.TransferRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class TransferUiState(
    val progress: TransferProgress? = null,
    val error: String? = null,
)

@HiltViewModel
class TransferViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    discovery: PeerDiscovery,
    engine: TransferEngine,
    private val selection: SendSelection,
    private val transferDao: TransferDao,
) : ViewModel() {

    private val peerId = savedStateHandle.toRoute<TransferRoute>().peerId
    private val _state = MutableStateFlow(TransferUiState())
    val state: StateFlow<TransferUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val peer = discovery.peers.first().firstOrNull { it.id == peerId }
            val files = selection.files.value
            when {
                peer == null -> _state.value = TransferUiState(error = "This device is no longer nearby.")
                files.isEmpty() -> _state.value = TransferUiState(error = "No file selected.")
                else -> engine.send(peer, files).collect { progress ->
                    _state.value = TransferUiState(progress = progress)
                    if (progress.status == TransferStatus.Completed) record(progress)
                }
            }
        }
    }

    private suspend fun record(done: TransferProgress) {
        transferDao.insert(
            TransferEntity(
                peerName = done.peer.name,
                peerFlickId = done.peer.flickId,
                direction = "sent",
                fileCount = done.files.size,
                totalBytes = done.totalBytes,
                finishedAtMs = System.currentTimeMillis(),
            ),
        )
        selection.clear()
    }
}
