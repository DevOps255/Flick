package com.flick.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [TransferEntity::class, FriendEntity::class], version = 1, exportSchema = true)
abstract class FlickDatabase : RoomDatabase() {
    abstract fun transferDao(): TransferDao

    abstract fun friendDao(): FriendDao
}
