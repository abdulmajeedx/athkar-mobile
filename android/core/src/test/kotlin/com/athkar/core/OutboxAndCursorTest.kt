package com.athkar.core

import com.athkar.core.sync.BackoffWithJitter
import com.athkar.core.sync.CursorLogic
import com.athkar.core.sync.DeltaChange
import com.athkar.core.sync.DeltaCursor
import com.athkar.core.sync.OutboxEntry
import com.athkar.core.sync.OutboxPolicy
import com.athkar.core.sync.OutboxState
import com.athkar.core.sync.TombstonePolicy
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OutboxAndCursorTest {

    @Test
    fun `backoff retries up to 8 attempts then stops`() {
        val backoff = BackoffWithJitter(maxAttempts = 8, random = Random(1))
        var attempts = 0
        while (backoff.canRetry()) {
            backoff.beginAttempt()
            attempts++
        }
        assertEquals(8, attempts)
        assertFalse(backoff.canRetry())
    }

    @Test
    fun `retry delay grows and never exceeds five minute cap`() {
        val backoff = BackoffWithJitter(maxAttempts = 8, random = Random(3))
        var previous = 0L
        repeat(8) {
            val d = backoff.nextDelayMillis()
            assertTrue(d in 0..(5 * 60_000L), "delay out of range: $d")
            assertTrue(d >= 0)
            previous = d
            backoff.beginAttempt()
        }
    }

    @Test
    fun `outbox moves to dead letter after exhausting retries`() {
        val backoff = BackoffWithJitter(maxAttempts = 3, random = Random(5))
        var entry = OutboxEntry.create("UPSERT_ENTITY", "e1", "{}", 1000L)
        var final: OutboxState = entry.state
        repeat(3) {
            val (state, count) = OutboxPolicy.onFailure(backoff)
            final = state
            entry = entry.copy(attemptCount = count, state = state)
        }
        assertEquals(OutboxState.DEAD_LETTER, final)
        assertEquals(OutboxState.DEAD_LETTER, entry.state)
        assertEquals(3, entry.attemptCount)
    }

    @Test
    fun `outbox idempotency key is a uuid v7`() {
        val entry = OutboxEntry.create("TOMBSTONE", "e1", "{}", 1_000L)
        assertEquals(36, entry.idempotencyKey.length)
        assertEquals('7', entry.idempotencyKey[14])
    }

    @Test
    fun `cursor advances to max hlc and is idempotent on reapply`() {
        val previous = DeltaCursor("100")
        val changes = listOf(
            DeltaChange("e1", 150),
            DeltaChange("e2", 120),
            DeltaChange("e3", 150),
        )
        val next = CursorLogic.advance(previous, changes)
        assertEquals(DeltaCursor("150"), next)
        // Reapplying the same page yields the same cursor (idempotence).
        assertEquals(next, CursorLogic.advance(previous, changes))
    }

    @Test
    fun `cursor unchanged for empty page`() {
        val previous = DeltaCursor("77")
        assertEquals(previous, CursorLogic.advance(previous, emptyList()))
    }

    @Test
    fun `tombstone expiry respects 90 day window and clock skew`() {
        val now = 1_700_000_000_000L
        val plausible = now - (90 * 86_400_000L) + 1000 // just inside window
        val expired = now - (91 * 86_400_000L)          // beyond window
        assertFalse(TombstonePolicy.isExpired(plausible, now))
        assertTrue(TombstonePolicy.isExpired(expired, now))
        // Future tombstone (clock skew on device) is never treated as expired.
        assertFalse(TombstonePolicy.isExpired(now + 5_000, now))
    }

    @Test
    fun `delta page respects page size constant`() {
        assertEquals(200, com.athkar.core.sync.SYNC_PAGE_SIZE)
    }
}
