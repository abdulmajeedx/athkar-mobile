package com.athkar.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.athkar.data.db.entity.AdhkarEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AdhkarDao {
    @Query(
        """
        SELECT * FROM adhkar_entities
        WHERE tombstoned = 0
        ORDER BY pinned DESC, catOrder ASC, id ASC
        """
    )
    fun observeAll(): Flow<List<AdhkarEntity>>

    @Query("SELECT * FROM adhkar_entities WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<AdhkarEntity?>

    @Query("SELECT * FROM adhkar_entities WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): AdhkarEntity?

    @Query("SELECT * FROM adhkar_entities WHERE serverHlc > :cursor ORDER BY serverHlc ASC LIMIT :limit")
    suspend fun since(cursor: Long, limit: Int): List<AdhkarEntity>

    @Query("SELECT * FROM adhkar_entities ORDER BY serverHlc ASC LIMIT :limit")
    suspend fun allByHlc(limit: Int): List<AdhkarEntity>

    @Query("SELECT COUNT(*) FROM adhkar_entities")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AdhkarEntity)

    /** Bulk path for seeding; a single transaction instead of one write per row. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entities: List<AdhkarEntity>)

    @Query("UPDATE adhkar_entities SET tombstoned = 1, serverHlc = :serverHlc, updatedAtMillis = :now WHERE id = :id")
    suspend fun markTombstoned(id: String, serverHlc: Long, now: Long)

    @Query("DELETE FROM adhkar_entities WHERE id = :id")
    suspend fun deletePhysically(id: String)

    /** Clears one writer's rows; used to replace bundled content when a newer set ships. */
    @Query("DELETE FROM adhkar_entities WHERE writerId = :writerId")
    suspend fun deleteByWriter(writerId: String): Int

    /** Read before a content replacement so the user's favourites survive it. */
    @Query("SELECT id FROM adhkar_entities WHERE pinned = 1")
    suspend fun pinnedIds(): List<String>

    @Query("DELETE FROM adhkar_entities WHERE tombstoned = 1 AND updatedAtMillis < :beforeEpochMillis")
    suspend fun purgeExpiredTombstones(beforeEpochMillis: Long): Int
}
