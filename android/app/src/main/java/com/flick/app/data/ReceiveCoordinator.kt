package com.flick.app.data

import android.content.Context
import android.os.SystemClock
import com.flick.app.TransferService
import com.flick.app.data.db.TransferDao
import com.flick.app.data.db.TransferEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.flick_ffi.TransferEvent

/** A transfer arriving on this phone, as the receive overlay shows it. */
data class IncomingTransfer(
    val sessionId: String,
    val sender: String,
    val fileNames: List<String>,
    val totalBytes: Long,
    val doneBytes: Long = 0,
    val bytesPerSecond: Long = 0,
    val savedPaths: List<String> = emptyList(),
    val finished: Boolean = false,
    val ok: Boolean = true,
) {
    val progress: Float get() = if (totalBytes == 0L) 0f else (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f)

    /** Seconds left at the current speed, or null while the speed is unknown. */
    val secondsLeft: Long? get() = bytesPerSecond.takeIf { it > 0 }?.let { (totalBytes - doneBytes) / it }
}

/**
 * Turns the node's receive events into one [IncomingTransfer] the UI can show.
 * The numbers are the ones Rust reports, nothing is estimated ahead of the bytes.
 */
@Singleton
class ReceiveCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    nodes: NodeRepository,
    private val transferDao: TransferDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _incoming = MutableStateFlow<IncomingTransfer?>(null)
    val incoming: StateFlow<IncomingTransfer?> = _incoming.asStateFlow()

    private var lastMs = 0L
    private var lastBytes = 0L

    init {
        scope.launch { nodes.events.collect(::reduce) }
    }

    fun dismiss() {
        _incoming.value = null
    }

    private suspend fun reduce(event: TransferEvent) {
        when (event) {
            is TransferEvent.IncomingStarted -> {
                TransferService.start(context)
                lastMs = SystemClock.elapsedRealtime()
                lastBytes = 0
                _incoming.value = IncomingTransfer(
                    sessionId = event.sessionId,
                    sender = event.sender,
                    fileNames = event.fileNames,
                    totalBytes = event.totalBytes.toLong(),
                )
            }
            is TransferEvent.Progress -> _incoming.update { current ->
                current?.takeIf { it.sessionId == event.sessionId }
                    ?.copy(doneBytes = event.doneBytes.toLong(), bytesPerSecond = measureSpeed(current.bytesPerSecond, event.doneBytes.toLong()))
                    ?: current
            }
            is TransferEvent.FileReceived -> _incoming.update { current ->
                current?.takeIf { it.sessionId == event.sessionId }?.copy(savedPaths = current.savedPaths + event.path) ?: current
            }
            is TransferEvent.Finished -> {
                _incoming.update { current ->
                    current?.takeIf { it.sessionId == event.sessionId }
                        ?.copy(finished = true, ok = event.ok, doneBytes = if (event.ok) current.totalBytes else current.doneBytes)
                        ?: current
                }
                _incoming.value?.takeIf { it.sessionId == event.sessionId && event.ok }?.let(::record)
            }
            is TransferEvent.Declined -> Unit
        }
    }

    /** Speed over at least 250 ms, smoothed so the number does not flicker. */
    private fun measureSpeed(previous: Long, done: Long): Long {
        val now = SystemClock.elapsedRealtime()
        if (now - lastMs < 250) return previous
        val instant = (done - lastBytes) * 1000 / (now - lastMs)
        lastMs = now
        lastBytes = done
        return if (previous == 0L) instant else (previous * 7 + instant * 3) / 10
    }

    private fun record(done: IncomingTransfer) {
        scope.launch {
            transferDao.insert(
                TransferEntity(
                    peerName = done.sender,
                    peerFlickId = "",
                    direction = "received",
                    fileCount = done.fileNames.size,
                    totalBytes = done.totalBytes,
                    finishedAtMs = System.currentTimeMillis(),
                ),
            )
        }
    }
}
