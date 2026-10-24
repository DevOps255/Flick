package com.flick.app.data.simulated

import com.flick.app.core.FileItem
import com.flick.app.core.Peer
import com.flick.app.core.TransferEngine
import com.flick.app.core.TransferProgress
import com.flick.app.core.TransferStatus
import com.flick.app.data.acoustic.spread
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Emits believable progress at about 20 MB/s without touching the network. */
class SimulatedTransferEngine @Inject constructor() : TransferEngine {

    override fun send(peer: Peer, files: List<FileItem>): Flow<TransferProgress> = flow {
        val total = files.sumOf { it.sizeBytes }
        emit(TransferProgress(peer, files, 0, TransferStatus.Preparing))
        delay(PREPARE_MS)

        var sent = 0L
        while (sent < total) {
            delay(TICK_MS)
            sent = minOf(total, sent + BYTES_PER_TICK)
            emit(TransferProgress(peer, spread(files, sent), BYTES_PER_TICK * 1000 / TICK_MS, TransferStatus.InProgress))
        }
        emit(TransferProgress(peer, spread(files, total), 0, TransferStatus.Completed))
    }

    private companion object {
        const val PREPARE_MS = 600L
        const val TICK_MS = 100L
        const val BYTES_PER_TICK = 2_000_000L
    }
}
