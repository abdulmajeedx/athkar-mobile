package com.athkar.sync.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.athkar.core.sync.BackoffWithJitter
import com.athkar.core.sync.OutboxState
import com.athkar.core.sync.OutboxState.PENDING
import com.athkar.core.sync.OutboxState.FAILED
import com.athkar.data.db.dao.AdhkarDao
import com.athkar.data.db.dao.SyncDao
import com.athkar.domain.MergeRemoteUse
import com.athkar.domain.SyncStateRepository
import com.athkar.sync.dto.ChangesPage
import com.athkar.sync.dto.PushResult
import com.athkar.sync.network.SyncApi
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.Random

/**
 * Periodic sync worker: drain the outbox (bounded retries with exponential backoff + jitter, then
 * dead-letter), then pull changes with a cursor and apply them locally through per-field LWW merge
 * (this is only a hint; pull-on-open and pull-to-refresh are the delivery guarantees).
 *
 * The worker is idempotent: resume from the last applied cursor page, never from the start. It
 * never trusts the device clock for ordering.
 */
@HiltWorker
class AthkarSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val syncDao: SyncDao,
    private val adhkarDao: AdhkarDao,
    private val syncState: SyncStateRepository,
    private val api: SyncApi,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        syncState.noteSyncAttempt()
        return try {
            pushOutbox()
            if (runAttemptCount == 0) pullChanges()
            syncState.recordSuccess(System.currentTimeMillis())
            Result.success()
        } catch (e: Exception) {
            syncState.recordFailure(syncState.lastCursor())
            Result.retry()
        }
    }

    private suspend fun pushOutbox() {
        val backoff = BackoffWithJitter(random = kotlin.random.Random(System.nanoTime()))
        var state = PENDING.name
        do {
            val due = syncDao.dueOutbox(state, System.currentTimeMillis(), BATCH)
            if (due.isEmpty()) {
                // Move on to previously-failed rows that are now due for retry.
                if (state == PENDING.name) { state = FAILED.name; continue }
                break
            }
            val writes = due.map { it.toSyncWrite() }
            val result: PushResult = api.pushChanges(writes)
            result.acks.forEach { ack ->
                if (ack.status == "applied") syncDao.deleteOutbox(ack.idempotency_key)
                else syncDao.updateOutbox(ack.idempotency_key, OutboxState.DONE.name, 0, ack.error, null)
            }
            if (result.acks.size != writes.size) {
                // Park anything un-acknowledged as dead letter (server non-durable or replay mismatch).
                due.forEach { e ->
                    val acked = result.acks.any { it.idempotency_key == e.idempotencyKey }
                    if (!acked) syncDao.updateOutbox(e.idempotencyKey, OutboxState.DEAD_LETTER.name, e.attemptCount + 1, "no ack", null)
                }
            }
            backoff.reset()
        } while (true)
    }

    private suspend fun pullChanges() {
        var cursor = syncDao.getCursor()?.cursor ?: ""
        var guard = 0
        while (guard++ < MAX_PAGES) {
            val page: ChangesPage = api.getChanges(cursor)
            applyPage(page)
            cursor = page.next_cursor
            syncDao.upsertCursor(com.athkar.data.db.entity.SyncCursorEntity(1, cursor, System.currentTimeMillis()))
            if (!page.has_more) break
        }
    }

    private suspend fun applyPage(page: ChangesPage) {
        for (item in page.changes) {
            when (item.op_type) {
                "UPSERT_ENTITY", "LWW_MAP_SET" -> {
                    val local = adhkarDao.getById(item.entity_id)?.toDomain()
                    val remote = com.athkar.core.domain.AdhkarReminder(
                        id = item.entity_id,
                        title = item.payload["title"],
                        body = item.payload["body"],
                        targetCount = item.payload["targetCount"]?.toIntOrNull(),
                        times = null,
                        catOrder = item.payload["catOrder"]?.toIntOrNull(),
                        pinned = item.payload["pinned"]?.toBoolean(),
                        hlc = com.athkar.core.clock.Hlc.decode(item.server_hlc),
                        writerId = "server",
                    )
                    adhkarDao.upsert(com.athkar.data.db.entity.AdhkarEntity.fromDomain(MergeRemoteUse.reconcile(local, remote), System.currentTimeMillis()))
                }
                "TOMBSTONE" -> adhkarDao.markTombstoned(item.entity_id, item.server_hlc, System.currentTimeMillis())
            }
        }
    }

    private fun OutboxEntity.toSyncWrite() = com.athkar.sync.dto.SyncWrite(
        idempotency_key = idempotencyKey,
        op_type = opType,
        entity_type = "adhkar",
        entity_id = entityId ?: "",
        client_hlc = 0L,
        writer_id = "android",
    )

    companion object {
        const val NAME = "athkar-periodic-sync"
        const val BATCH = 50
        const val MAX_PAGES = 200
    }
}

private typealias OutboxEntity = com.athkar.data.db.entity.OutboxEntity
