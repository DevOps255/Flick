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
        /** The profile photo of the phone, a small JPEG, or null when it has none. */
        val avatar: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Peer) return false
        return id == other.id &&
                name == other.name &&
                flickId == other.flickId &&
                isFriend == other.isFriend &&
                proximity == other.proximity &&
                ready == other.ready &&
                note == other.note &&
                avatar.contentEquals(other.avatar)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + flickId.hashCode()
        result = 31 * result + isFriend.hashCode()
        result = 31 * result + proximity.hashCode()
        result = 31 * result + ready.hashCode()
        result = 31 * result + (note?.hashCode() ?: 0)
        result = 31 * result + (avatar?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * The local user, shown on the profile and announced to peers.
 * [photoPath] is a file inside the app's private storage, or null while the user has no photo.
 */
data class Profile(val name: String, val flickId: String, val photoPath: String? = null)

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

/** The kinds of file the send screen offers, in the order of its cards. */
enum class FileCategory { Documents, Downloads, Videos, Audio, Apps, Images }

/** A file found on the phone, ready to be picked. [uri] is a content or file URI that can be opened for reading. */
data class DeviceFile(
        val uri: String,
        val name: String,
        val sizeBytes: Long,
        val modifiedMs: Long,
        val mime: String,
        val category: FileCategory,
        /** Package name, only for [FileCategory.Apps]. The icon is read from the package manager. */
        val packageName: String? = null,
) {
    fun toItem() = FileItem(uri = uri, name = name, sizeBytes = sizeBytes)
}

enum class SortKey { Name, Date, Size }

/** How the file list is ordered. [descending] reverses it. */
data class FileSort(val key: SortKey = SortKey.Date, val descending: Boolean = true)
