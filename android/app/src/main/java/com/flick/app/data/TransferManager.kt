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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Runs the sends in progress, outside any screen. There is one send per recipient,
 * and they run at the same time.
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
    problems: Problems,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + problems.handler("Sending"))
    private val _state = MutableStateFlow<List<TransferProgress>>(emptyList())
    private var jobs = emptyList<Job>()

    /** One entry per recipient, in the order they were started. Empty when nothing was sent yet. */
    val state: StateFlow<List<TransferProgress>> = _state.asStateFlow()

    fun start(peers: List<Peer>, files: List<FileItem>) {
        stopJobs()
        _state.value = emptyList()
        TransferService.start(context)
        jobs = peers.map { peer ->
            scope.launch {
                engine.send(peer, files).collect { progress ->
                    _state.update { replaceOrAdd(it, progress) }
                    if (progress.status == TransferStatus.Completed) finish(progress, expected = peers.size)
                }
            }
        }
    }

    /** Stops every send in progress and forgets them. */
    fun cancel() {
        stopJobs()
        _state.value = emptyList()
    }

    /** Forgets finished or failed sends, so the next screen does not show them. Does nothing while one runs. */
    fun reset() {
        if (_state.value.none { it.isRunning() }) _state.value = emptyList()
    }

    private fun stopJobs() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
    }

    private fun replaceOrAdd(list: List<TransferProgress>, progress: TransferProgress): List<TransferProgress> {
        val index = list.indexOfFirst { it.peer.id == progress.peer.id }
        return if (index < 0) list + progress else list.toMutableList().also { it[index] = progress }
    }

    private suspend fun finish(done: TransferProgress, expected: Int) {
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
        val all = _state.value
        if (all.size == expected && all.all { it.status == TransferStatus.Completed }) selection.clear()
    }
}

/** True while this send is connecting or moving bytes. */
fun TransferProgress.isRunning(): Boolean = status == TransferStatus.Preparing || status == TransferStatus.InProgress
