package com.athkar.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.athkar.data.db.entity.NotificationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationDao {
    @Query("SELECT * FROM notifications ORDER BY receivedAtMillis DESC LIMIT 100")
    fun observeRecent(): Flow<List<NotificationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(n: NotificationEntity)

    @Query("UPDATE notifications SET isRead = 1 WHERE id = :id")
    suspend fun markRead(id: String)

    @Query("DELETE FROM notifications WHERE id NOT IN (SELECT id FROM notifications ORDER BY receivedAtMillis DESC LIMIT 100)")
    suspend fun trimTo100()
}
