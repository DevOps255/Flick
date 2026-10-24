package com.flick.app.core

/**
 * A device found nearby. [proximity] runs from 0 (far) to 1 (touching).
 * [ready] is true once the device answered over TLS with the announced
 * certificate, which means files can be sent to it right now.
 */
data class Peer(
    val id: String,
    val name: String,
    val flickId: String,
    val isFriend: Boolean,
    val proximity: Float,
    val ready: Boolean = true,
    /** Why the phone is not ready yet, when something went wrong. Shown instead of "Connecting". */
    val note: String? = null,
)

/** The local user, shown on the home card and announced to peers. */
data class Profile(val name: String, val flickId: String)

/** A file queued for sending or being received. */
data class FileItem(
    val uri: String,
    val name: String,
    val sizeBytes: Long,
    val transferredBytes: Long = 0,
) {
    val progress: Float
        get() = if (sizeBytes == 0L) 1f else (transferredBytes.toFloat() / sizeBytes).coerceIn(0f, 1f)
}

enum class TransferStatus { Preparing, InProgress, Completed, Failed }

/** Snapshot of a transfer, emitted every time something changes. */
data class TransferProgress(
    val peer: Peer,
    val files: List<FileItem>,
    val bytesPerSecond: Long,
    val status: TransferStatus,
    val failure: String? = null,
) {
    val totalBytes: Long get() = files.sumOf { it.sizeBytes }
    val doneBytes: Long get() = files.sumOf { it.transferredBytes }
    val progress: Float
        get() = if (totalBytes == 0L) 0f else (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
}
