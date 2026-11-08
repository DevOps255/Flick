package com.flick.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [TransferEntity::class, FriendEntity::class, FileRecordEntity::class], version = 2, exportSchema = true)
abstract class FlickDatabase : RoomDatabase() {
    abstract fun transferDao(): TransferDao

    abstract fun friendDao(): FriendDao

    abstract fun fileRecordDao(): FileRecordDao

    companion object {
        /** Version 2 adds the list of sent and received files. Transfers and friends are kept. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `files` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`sizeBytes` INTEGER NOT NULL, " +
                        "`direction` TEXT NOT NULL, " +
                        "`peerName` TEXT NOT NULL, " +
                        "`uri` TEXT NOT NULL, " +
                        "`mime` TEXT NOT NULL, " +
                        "`atMs` INTEGER NOT NULL)",
                )
            }
        }
    }
}
