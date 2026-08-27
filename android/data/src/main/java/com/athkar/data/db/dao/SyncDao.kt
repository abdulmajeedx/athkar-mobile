package com.athkar.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.athkar.data.db.entity.OutboxEntity
import com.athkar.data.db.entity.SettingEntity
import com.athkar.data.db.entity.SyncCursorEntity
import com.athkar.data.db.entity.TombstoneEntity
import com.athkar.core.sync.OutboxState
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncDao {

    // ---- Outbox ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOutbox(entity: OutboxEntity)

    @Query("SELECT * FROM outbox WHERE state = :state ORDER BY createdAtMillis ASC LIMIT :limit")
    suspend fun nextOutbox(state: String, limit: Int): List<OutboxEntity>

    @Query("SELECT * FROM outbox WHERE state = :state AND (nextRetryAtMillis IS NULL OR nextRetryAtMillis <= :now) ORDER BY createdAtMillis ASC LIMIT :limit")
    suspend fun dueOutbox(state: String, now: Long, limit: Int): List<OutboxEntity>

    @Query("UPDATE outbox SET state = :state, attemptCount = :attempts, lastError = :error, nextRetryAtMillis = :nextRetryAtMillis WHERE idempotencyKey = :key")
    suspend fun updateOutbox(key: String, state: String, attempts: Int, error: String?, nextRetryAtMillis: Long?)

    @Query("DELETE FROM outbox WHERE idempotencyKey = :key")
    suspend fun deleteOutbox(key: String)

    @Query("SELECT COUNT(*) FROM outbox WHERE state = :state")
    suspend fun countOutbox(state: String): Int

    @Query("SELECT COUNT(*) FROM outbox WHERE state IN ('PENDING','IN_FLIGHT','FAILED')")
    suspend fun countPending(): Int

    // ---- Tombstones ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTombstone(t: TombstoneEntity)

    @Query("DELETE FROM tombstones WHERE tombstonedAtMillis < :beforeEpochMillis")
    suspend fun purgeTombstones(beforeEpochMillis: Long): Int

    // ---- Sync cursor (single row) ----
    @Query("SELECT * FROM sync_cursor WHERE singletonId = 1 LIMIT 1")
    suspend fun getCursor(): SyncCursorEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCursor(cursor: SyncCursorEntity)

    // ---- Settings (LWW map) ----
    @Query("SELECT * FROM settings")
    fun observeSettings(): Flow<List<SettingEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSetting(s: SettingEntity)

    @Query("SELECT * FROM settings")
    suspend fun allSettings(): List<SettingEntity>
}
