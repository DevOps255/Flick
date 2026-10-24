package com.flick.app.ui.transfer

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.flick.app.core.PeerDiscovery
import com.flick.app.core.TransferProgress
import com.flick.app.data.SendSelection
import com.flick.app.data.TransferManager
import com.flick.app.ui.TransferRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TransferUiState(
    val progress: TransferProgress? = null,
    val error: String? = null,
)

/**
 * Starts the send and shows it. The send itself runs in [TransferManager],
 * so leaving the screen does not stop it. [cancel] does.
 */
@HiltViewModel
class TransferViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    discovery: PeerDiscovery,
    private val manager: TransferManager,
    selection: SendSelection,
) : ViewModel() {

    private val peerId = savedStateHandle.toRoute<TransferRoute>().peerId
    private val error = MutableStateFlow<String?>(null)

    val state: StateFlow<TransferUiState> = combine(manager.state, error) { progress, message ->
        TransferUiState(progress, message)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransferUiState())

    init {
        manager.reset()
        viewModelScope.launch {
            val peer = discovery.peers.first().firstOrNull { it.id == peerId }
            val files = selection.files.value
            when {
                peer == null -> error.value = "This device is no longer nearby."
                files.isEmpty() -> error.value = "No file selected."
                else -> manager.start(peer, files)
            }
        }
    }

    fun cancel() = manager.cancel()
}
