package com.athkar.data.repository

import com.athkar.core.domain.AdhkarReminder
import com.athkar.core.sync.OutboxEntry
import com.athkar.core.sync.OutboxState
import com.athkar.data.db.dao.AdhkarDao
import com.athkar.data.db.dao.SyncDao
import com.athkar.data.db.entity.AdhkarEntity
import com.athkar.data.db.entity.OutboxEntity
import com.athkar.domain.AdhkarRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Local-first repository. Every write is persisted immediately (single source of truth) and mirrored
 * into the outbox with a UUID v7 idempotency key; the sync worker flushes the outbox to the server.
 * The UI observes only local flows and never a network response.
 */
class AdhkarRepositoryImpl @Inject constructor(
    private val adhkarDao: AdhkarDao,
    private val syncDao: SyncDao,
    private val clock: () -> Long,
) : AdhkarRepository {

    override fun observeAll(): Flow<List<AdhkarReminder>> =
        adhkarDao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeById(id: String): Flow<AdhkarReminder?> =
        adhkarDao.observeById(id).map { it?.toDomain() }

    override suspend fun getById(id: String): AdhkarReminder? =
        adhkarDao.getById(id)?.toDomain()

    override suspend fun upsert(reminder: AdhkarReminder) {
        val now = clock()
        adhkarDao.upsert(AdhkarEntity.fromDomain(reminder, now))
        syncDao.insertOutbox(
            OutboxEntity.fromDomain(
                OutboxEntry.create(
                    opType = "UPSERT_ENTITY",
                    entityId = reminder.id,
                    payload = Serde.toJson(reminder),
                    nowMillis = now,
                )
            )
        )
    }

    override suspend fun delete(id: String) {
        val now = clock()
        val existing = adhkarDao.getById(id)?.toDomain() ?: return
        val tomb = existing.copy(tombstoned = true)
        adhkarDao.markTombstoned(id, tomb.hlc.value, now)
        syncDao.insertOutbox(
            OutboxEntity.fromDomain(
                OutboxEntry.create(
                    opType = "TOMBSTONE",
                    entityId = id,
                    payload = Serde.toJson(mapOf("entityId" to id, "hlc" to tomb.hlc.value)),
                    nowMillis = now,
                )
            )
        )
    }
}
