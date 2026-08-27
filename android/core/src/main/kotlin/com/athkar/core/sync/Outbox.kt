package com.athkar.core.sync

import com.athkar.core.crdt.UuidV7
import kotlin.random.Random

/**
 * Exponential backoff with full jitter (per AWS Architecture Blog), retrying 1,2,4,8,16s ... capped
 * at 5 minutes, up to [maxAttempts] attempts. Exceeding that moves the outbox row to the Dead Letter
 * queue (see [OutboxPolicy]).
 */
class BackoffWithJitter(
    private val baseMillis: Long = 1_000L,
    private val multiplier: Double = 2.0,
    private val capMillis: Long = 5 * 60_000L,
    private val maxAttempts: Int = 8,
    private val random: Random = Random.Default,
) {
    private var attempt = 0

    fun reset() { attempt = 0 }

    fun attempts(): Int = attempt

    /** @return true when the operation is allowed to retry again; false => send to dead letter. */
    fun canRetry(): Boolean = attempt < maxAttempts

    fun beginAttempt() { attempt++ }

    /** Full jitter: delay uniformly in [0, min(cap, base * multiplier^attempt)). */
    fun nextDelayMillis(): Long {
        val exponent = (attempt - 1).coerceAtLeast(0)
        val bound = (baseMillis.toDouble() * Math.pow(multiplier, exponent.toDouble())).toLong()
            .coerceAtMost(capMillis)
        return random.nextLong(0, bound + 1)
    }
}

/**
 * Outbox row state machine. A row starts PENDING, moves to IN_FLIGHT while an HTTP call is pending,
 * then DONE after an idempotent ack, or FAILED (counts a retry) until it overflows to DEAD_LETTER.
 */
enum class OutboxState { PENDING, IN_FLIGHT, FAILED, DONE, DEAD_LETTER }

/**
 * A single outbox entry carrying a replayable operation.
 *
 * @property idempotencyKey UUID v7. The server returns the *same result* for any replay of the same
 *                          key (see OpenAPI contract /sync endpoints), making retries safe.
 * @property attemptCount monotonically increasing; when >= [BackoffWithJitter.maxAttempts] the row
 *                          is parked in the dead-letter queue and the user is notified in-app.
 */
data class OutboxEntry(
    val idempotencyKey: String,
    val opType: String,               // e.g. UPSERT_ENTITY, TOMBSTONE, LWW_MAP_SET
    val entityId: String?,
    val payload: String,              // JSON payload (schema defined by opType)
    val createdAtMillis: Long,
    val state: OutboxState = OutboxState.PENDING,
    val attemptCount: Int = 0,
    val lastError: String? = null,
) {
    companion object {
        fun create(opType: String, entityId: String?, payload: String, nowMillis: Long): OutboxEntry =
            OutboxEntry(
                idempotencyKey = UuidV7.random(nowMillis),
                opType = opType,
                entityId = entityId,
                payload = payload,
                createdAtMillis = nowMillis,
            )
    }
}

/**
 * Pure policy for deciding whether to retry or move to dead letter. Kept free of IO so it is unit
 * testable without a database.
 */
object OutboxPolicy {
    /** Record a failed attempt. @return new state: FAILED if retriable, DEAD_LETTER otherwise. */
    fun onFailure(backoff: BackoffWithJitter): Pair<OutboxState, Int> {
        backoff.beginAttempt()
        return if (backoff.canRetry()) {
            OutboxState.FAILED to backoff.attempts()
        } else {
            OutboxState.DEAD_LETTER to backoff.attempts()
        }
    }
}
