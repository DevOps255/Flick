package com.flick.app.data

import android.content.Context
import com.flick.app.TransferService
import com.flick.app.core.FileItem
import com.flick.app.core.Peer
import com.flick.app.core.TransferEngine
import com.flick.app.core.TransferProgress
import com.flick.app.core.TransferStatus
import com.flick.app.data.db.TransferDao
import com.flick.app.data.db.TransferEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Runs the one send in progress, outside any screen.
 *
 * A screen only watches [state]. Leaving it does not stop the transfer, and
 * [TransferService] keeps the process alive while the transfer runs.
 */
@Singleton
class TransferManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: TransferEngine,
    private val transferDao: TransferDao,
    private val selection: SendSelection,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<TransferProgress?>(null)
    private var job: Job? = null

    /** Progress of the current or last send. Null when nothing was sent yet. */
    val state: StateFlow<TransferProgress?> = _state.asStateFlow()

    fun start(peer: Peer, files: List<FileItem>) {
        job?.cancel()
        _state.value = null
        TransferService.start(context)
        job = scope.launch {
            engine.send(peer, files).collect { progress ->
                _state.value = progress
                if (progress.status == TransferStatus.Completed) finish(progress)
            }
        }
    }

    /** Stops the send in progress and forgets it. */
    fun cancel() {
        job?.cancel()
        job = null
        _state.value = null
    }

    /** Forgets a finished or failed send, so the next screen does not show it. Does nothing while one runs. */
    fun reset() {
        val status = _state.value?.status
        if (status != TransferStatus.Preparing && status != TransferStatus.InProgress) _state.value = null
    }

    private suspend fun finish(done: TransferProgress) {
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
