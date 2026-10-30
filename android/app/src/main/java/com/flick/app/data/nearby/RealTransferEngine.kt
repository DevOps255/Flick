package com.flick.app.data.nearby

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.flick.app.core.FileItem
import com.flick.app.core.Peer
import com.flick.app.core.TransferEngine
import com.flick.app.core.TransferProgress
import com.flick.app.core.TransferStatus
import com.flick.app.data.NodeRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import uniffi.flick_ffi.FlickNode
import uniffi.flick_ffi.OutgoingFileData
import uniffi.flick_ffi.SendObserver

/**
 * Sends files through the Rust node.
 *
 * Picked files are Android content URIs without a path. Each one is opened as a
 * file descriptor and handed to Rust, so nothing is copied and no path is ever reopened.
 */
class RealTransferEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val discovery: NearbyDiscovery,
    private val nodes: NodeRepository,
) : TransferEngine {

    override fun send(peer: Peer, files: List<FileItem>): Flow<TransferProgress> = callbackFlow {
        fun snapshot(done: Long, speed: Long, status: TransferStatus, failure: String? = null) =
            TransferProgress(peer, spread(files, done), speed, status, failure)

        val announcement = discovery.announcementOf(peer.id)
        if (announcement == null) {
            trySend(snapshot(0, 0, TransferStatus.Failed, "This phone is no longer nearby."))
            close()
            return@callbackFlow
        }
        trySend(snapshot(0, 0, TransferStatus.Preparing))

        var node: FlickNode? = null
        val job = CoroutineScope(Dispatchers.IO).launch {
            try {
                val outgoing = files.map { file ->
                    val pfd = requireNotNull(context.contentResolver.openFileDescriptor(Uri.parse(file.uri), "r")) {
                        "Cannot open ${file.name}"
                    }
                    // detachFd hands the descriptor to Rust, which reads from it and closes it.
                    // Reopening the file by path would be refused: Android only lets this app read it through this descriptor.
                    OutgoingFileData(pfd.detachFd(), file.name, file.sizeBytes.toULong())
                }
                val running = nodes.node().also { node = it }
                var lastMs = SystemClock.elapsedRealtime()
                var lastBytes = 0L
                var speed = 0L
                running.send(
                    announcement,
                    outgoing,
                    object : SendObserver {
                        override fun onProgress(doneBytes: ULong, totalBytes: ULong) {
                            val now = SystemClock.elapsedRealtime()
                            val done = doneBytes.toLong()
                            if (now - lastMs >= SPEED_WINDOW_MS) {
                                val instant = (done - lastBytes) * 1000 / (now - lastMs)
                                speed = if (speed == 0L) instant else (speed * 7 + instant * 3) / 10
                                lastMs = now
                                lastBytes = done
                            }
                            trySend(snapshot(done, speed, TransferStatus.InProgress))
                        }
                    },
                )
                trySend(snapshot(files.sumOf { it.sizeBytes }, 0, TransferStatus.Completed))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Throwable on purpose: a missing native library is an Error, not an Exception.
                trySend(snapshot(0, 0, TransferStatus.Failed, e.message ?: "Transfer failed"))
            } finally {
                close()
            }
        }
        awaitClose {
            node?.cancelSend()
            job.cancel()
        }
    }

    private companion object {
        /** Speed is measured over at least this long, then smoothed, so the number does not flicker. */
        const val SPEED_WINDOW_MS = 250L
    }
}

/** Fills files in order: the first is finished before the second starts. */
internal fun spread(files: List<FileItem>, sent: Long): List<FileItem> {
    var left = sent
    return files.map { file ->
        val done = minOf(left, file.sizeBytes)
        left -= done
        file.copy(transferredBytes = done)
    }
}
