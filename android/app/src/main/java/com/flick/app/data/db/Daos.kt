package com.flick.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TransferDao {
    @Query("SELECT * FROM transfers ORDER BY finishedAtMs DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<TransferEntity>>

    @Insert
    suspend fun insert(transfer: TransferEntity)
}

@Dao
interface FriendDao {
    @Query("SELECT * FROM friends ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<FriendEntity>>

    @Upsert
    suspend fun upsert(friend: FriendEntity)
}

@Dao
interface FileRecordDao {
    @Query("SELECT * FROM files ORDER BY atMs DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<FileRecordEntity>>

    @Insert
    suspend fun insert(record: FileRecordEntity)

    @Insert
    suspend fun insertAll(records: List<FileRecordEntity>)

    @Query("DELETE FROM files WHERE id = :id")
    suspend fun delete(id: Long)
}
