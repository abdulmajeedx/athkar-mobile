package com.athkar.domain

import com.athkar.core.domain.AdhkarReminder
import com.athkar.core.clock.Hlc
import com.athkar.core.crdt.LwwMap
import com.athkar.core.sync.ReplicaState
import kotlinx.coroutines.flow.Flow

/**
 * Port (interface) for the local single-source-of-truth store consumed by the UI and the use cases.
 * The implementation lives in :data (Room). The UI only ever observes local reactive flows and
 * never reads a network response directly.
 */
interface AdhkarRepository {
    /** Reactive local list. Must return local-first updates well under the 16 ms frame budget. */
    fun observeAll(): Flow<List<AdhkarReminder>>
    fun observeById(id: String): Flow<AdhkarReminder?>

    /** Local-first write: persist immediately, queue to outbox; the sync engine flushes it. */
    suspend fun upsert(reminder: AdhkarReminder)
    suspend fun delete(id: String)
    suspend fun getById(id: String): AdhkarReminder?
}

interface SettingsRepository {
    fun observe(): Flow<LwwMap<String, Any?>>
    suspend fun set(key: String, value: Any?, nowMillis: Long)
}

interface SyncStateRepository {
    fun observeState(): Flow<SyncUiState>
    suspend fun noteSyncAttempt()
    suspend fun recordSuccess(nowMillis: Long)
    suspend fun recordFailure(cursor: String)
    suspend fun lastCursor(): String
}

/** Small, immutable UI-facing adhesion type for the last-sync banner. */
data class SyncUiState(
    val lastSyncAtMillis: Long?,
    val isSyncing: Boolean = false,
    val outboxPending: Int = 0,
    val deadLetters: Int = 0,
    val connectivity: Connectivity = Connectivity.ONLINE,
)

enum class Connectivity { ONLINE, OFFLINE }
