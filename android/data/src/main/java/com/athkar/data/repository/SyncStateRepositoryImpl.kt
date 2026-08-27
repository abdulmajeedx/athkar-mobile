package com.athkar.data.repository

import com.athkar.core.sync.OutboxState
import com.athkar.data.db.dao.SyncDao
import com.athkar.data.db.entity.SyncCursorEntity
import com.athkar.domain.Connectivity
import com.athkar.domain.SyncStateRepository
import com.athkar.domain.SyncUiState
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks sync-related UI state: last successful sync time, outbox backlog, dead letters, and
 * connectivity. Cheap, reactor-level; the "last sync at" banner reads from here.
 */
class SyncStateRepositoryImpl @Inject constructor(
    private val syncDao: SyncDao,
    initialConnectivity: Connectivity,
) : SyncStateRepository {

    private val _state = MutableStateFlow(SyncUiState(lastSyncAtMillis = null, connectivity = initialConnectivity))
    override fun observeState(): StateFlow<SyncUiState> = _state.asStateFlow()

    override suspend fun noteSyncAttempt() {
        _state.value = _state.value.copy(isSyncing = true, outboxPending = syncDao.countPending())
    }

    override suspend fun recordSuccess(nowMillis: Long) {
        syncDao.upsertCursor(SyncCursorEntity(1, "", nowMillis))
        _state.value = _state.value.copy(
            isSyncing = false,
            lastSyncAtMillis = nowMillis,
            outboxPending = syncDao.countPending(),
            deadLetters = syncDao.countOutbox(OutboxState.DEAD_LETTER.name),
        )
    }

    override suspend fun recordFailure(cursor: String) {
        _state.value = _state.value.copy(isSyncing = false, deadLetters = syncDao.countOutbox(OutboxState.DEAD_LETTER.name))
    }

    override suspend fun lastCursor(): String = syncDao.getCursor()?.cursor ?: ""
}
