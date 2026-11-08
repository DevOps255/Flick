package com.flick.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A finished transfer, kept for the activity list on the home screen. */
@Entity(tableName = "transfers")
data class TransferEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val peerName: String,
    val peerFlickId: String,
    val direction: String,
    val fileCount: Int,
    val totalBytes: Long,
    val finishedAtMs: Long,
)

@Entity(tableName = "friends")
data class FriendEntity(
    @PrimaryKey val flickId: String,
    val name: String,
)

/**
 * A file that was sent or received, kept for the recent files list.
 *
 * [uri] is where the file can be opened now: a media store URI for a received file, and the URI the
 * user picked for a sent one. Removing a sent file from the list never touches the original.
 */
@Entity(tableName = "files")
data class FileRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sizeBytes: Long,
    /** "sent" or "received". */
    val direction: String,
    val peerName: String,
    val uri: String,
    val mime: String,
    val atMs: Long,
) {
    val received: Boolean get() = direction == DIRECTION_RECEIVED

    companion object {
        const val DIRECTION_SENT = "sent"
        const val DIRECTION_RECEIVED = "received"
    }
}
