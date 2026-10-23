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
