package com.athkar.core.sync

import com.athkar.core.clock.Hlc

/**
 * Per-replica merge state: the highest server HLC and the seat identity.
 *
 * Used to (a) generate the next local write HLC without trusting the device clock for ordering, and
 * (b) detect clock / cursor regressions. Stored encrypted on device; never derived from wall time.
 */
data class ReplicaState(
    val maxServerHlc: Hlc = Hlc.minValue(),
    val lastSyncAtMillis: Long? = null,
)

/**
 * Tombstone (logical-delete) housekeeping policy — mirrors the DB retention docs in docs/db.
 *
 * - `MAX_TOMBSTONES_AGE_DAYS`: tombstones are kept alive for at least this long so late-arriving
 *   writes from an offline device cannot resurrect a deleted entity.
 * - After the grace period, tombstones are purged by a WorkManager task / GRDB periodic job and the
 *   rows are physically removed (no tombstone survives forever).
 * - `MAX_TOMBSTONE_COUNT`: hard cap to bound table size; when exceeded, oldest tombstones are
 *   purged first (a monotonic-capacity approach).
 */
object TombstonePolicy {
    const val MAX_TOMBSTONES_AGE_DAYS: Long = 90
    const val MAX_TOMBSTONE_COUNT: Long = 10_000

    fun isExpired(tombstonedAtMillis: Long, nowMillis: Long): Boolean {
        if (nowMillis < tombstonedAtMillis) return false // clock skew guard
        return (nowMillis - tombstonedAtMillis) / 86_400_000L >= MAX_TOMBSTONES_AGE_DAYS
    }
}

/**
 * Marker interface for an outbox operation payload. Concrete serializers live in each platform's
 * data layer (Gson/Moshi on Android, Codable on iOS) against the same OpenAPI schema.
 */
interface OutboxPayload
