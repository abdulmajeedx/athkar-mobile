package com.athkar.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.athkar.core.sync.OutboxState

/**
 * Outbox pattern (delivery brief): every local write is persisted immediately and also inserted
 * into this table with a unique idempotency key (UUID v7). The sync worker drains it; the server
 * returns the same result for any replay of the same key, making retries safe.
 */
@Entity(
    tableName = "outbox",
    indices = [Index("state"), Index("entityId"), Index("createdAtMillis")],
)
data class OutboxEntity(
    @PrimaryKey val idempotencyKey: String,
    val opType: String,
    val entityId: String?,
    val payload: String,
    val createdAtMillis: Long,
    val state: String,          // OutboxState.name
    val attemptCount: Int,
    val lastError: String?,
    val nextRetryAtMillis: Long?, // null while not scheduled
) {
    val stateEnum: OutboxState get() = OutboxState.valueOf(state)
    companion object {
        fun fromDomain(e: com.athkar.core.sync.OutboxEntry): OutboxEntity = OutboxEntity(
            idempotencyKey = e.idempotencyKey,
            opType = e.opType,
            entityId = e.entityId,
            payload = e.payload,
            createdAtMillis = e.createdAtMillis,
            state = e.state.name,
            attemptCount = e.attemptCount,
            lastError = e.lastError,
            nextRetryAtMillis = null,
        )
    }
}

/** Logical-delete markers, retained for the tombstone grace period (90 days) then purged. */
@Entity(
    tableName = "tombstones",
    indices = [Index("tombstonedAtMillis")],
)
data class TombstoneEntity(
    @PrimaryKey val entityId: String,
    val tombstonedAtMillis: Long,
    val serverHlc: Long,
)

/** Single-row table holding the last successful pull cursor. */
@Entity(tableName = "sync_cursor")
data class SyncCursorEntity(
    @PrimaryKey val singletonId: Int = 1,
    val cursor: String,
    val lastSyncAtMillis: Long,
)

/** LWW settings map (CRDT). One row per key. */
@Entity(
    tableName = "settings",
    indices = [Index("hlc")],
)
data class SettingEntity(
    @PrimaryKey val key: String,
    val valueJson: String?,
    val hlc: Long,
    val writerId: String,
)

/** Internal notification center, retains the last 100 notifications with sync state. */
@Entity(
    tableName = "notifications",
    indices = [Index("isRead"), Index("receivedAtMillis"), Index("threadId")],
)
data class NotificationEntity(
    @PrimaryKey val id: String,
    val type: String,
    val title: String,
    val body: String,
    val imageUrl: String?,
    val deepLink: String?,
    val threadId: String?,
    val actionCount: Int,
    val isRead: Boolean,
    val receivedAtMillis: Long,
)
