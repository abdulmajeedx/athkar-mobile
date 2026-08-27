package com.athkar.core

import com.athkar.core.domain.AdhkarReminder
import com.athkar.core.domain.EntityConflictResolver
import com.athkar.core.domain.Field
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Property-based tests proving the per-field LWW merge is:
 *   - Associative:   merge(merge(a,b),c) == merge(a,merge(b,c))
 *   - Commutative:   merge(a,b) == merge(b,a)
 *   - Idempotent:    merge(a,a) == a
 *
 * Coverage target: this is the synchronization conflict-resolution core, which the brief requires
 * have a mutation score >= 50% and 1000+ randomized property cases.
 */
class ConflictResolverPropertyTest {

    private val seed = 42L
    private val random = Random(seed)
    private val baseWall = 1_700_000_000_000L
    private val writers = listOf("dev-A", "dev-B", "dev-C", "dev-D", "dev-E")

    private fun randReminder(i: Int) = TestData.randomReminder(
        random = random,
        seed = i.toLong(),
        writer = writers[(Random(i).nextInt(writers.size))],
        baseWall = baseWall,
    )

    /** Three devices, each starting from a common base then diverging with independent writes. */
    private fun diverge(iter: Int, mutations: Int): Triple<AdhkarReminder, AdhkarReminder, AdhkarReminder> {
        val base = randReminder(iter)
        var a = base; var b = base; var c = base
        val rA = Random(iter * 1000); val rB = Random(iter * 2000); val rC = Random(iter * 3000)
        repeat(mutations) {
            a = TestData.varyField(a, rA, "dev-A", baseWall)
            b = TestData.varyField(b, rB, "dev-B", baseWall)
            c = TestData.varyField(c, rC, "dev-C", baseWall)
        }
        return Triple(a, b, c)
    }

    @Test
    fun `merge is associative`() {
        repeat(1000) { i ->
            val (a, b, c) = diverge(i, mutations = 5)
            val lhs = EntityConflictResolver.merge(
                EntityConflictResolver.merge(a, b), c
            )
            val rhs = EntityConflictResolver.merge(
                a, EntityConflictResolver.merge(b, c)
            )
            assertEquals(lhs, rhs, "associativity failed at iteration $i")
        }
    }

    @Test
    fun `merge is commutative`() {
        repeat(1000) { i ->
            val (a, b, _) = diverge(i, mutations = 5)
            assertEquals(
                EntityConflictResolver.merge(a, b),
                EntityConflictResolver.merge(b, a),
                "commutativity failed at iteration $i",
            )
        }
    }

    @Test
    fun `merge is idempotent`() {
        repeat(1000) { i ->
            val (a, _, _) = diverge(i, mutations = 5)
            val once = EntityConflictResolver.merge(a, a)
            val twice = EntityConflictResolver.merge(a, once)
            assertEquals(a, once, "idempotence failed at iteration $i")
            assertEquals(once, twice)
        }
    }

    @Test
    fun `three devices converge on identical final state regardless of merge order`() {
        repeat(1000) { i ->
            val (a, b, c) = diverge(i, mutations = 5)

            // Six distinct pairwise-fold orders, all must converge to the same result.
            val results = listOf(
                EntityConflictResolver.merge(listOf(a, b, c)),
                EntityConflictResolver.merge(listOf(a, c, b)),
                EntityConflictResolver.merge(listOf(b, a, c)),
                EntityConflictResolver.merge(listOf(b, c, a)),
                EntityConflictResolver.merge(listOf(c, a, b)),
                EntityConflictResolver.merge(listOf(c, b, a)),
            )
            val first = results.first()
            results.forEach { assertEquals(first, it, "3-device convergence failed at iteration $i") }
        }
    }

    @Test
    fun `tombstone defeats all writes regardless of arrival order`() {
        repeat(500) { i ->
            val plain = randReminder(i)
            val tomb = plain.copy(tombstoned = true, hlc = com.athkar.core.clock.Hlc
                .fromWallMillis(baseWall + 500_000))
            val laterWrite = TestData.varyField(plain, Random(i), "dev-X", baseWall + 2_000_000)

            // Tombstone arrives before or after the concurrent write -> tombstone always wins.
            assertEquals(tomb, EntityConflictResolver.merge(tomb, laterWrite))
            assertEquals(tomb, EntityConflictResolver.merge(laterWrite, tomb))
        }
    }

    @Test
    fun `field-level merge does not resurrect fields that only one side updated`() {
        val a = randReminder(9).copy(pinned = null)
        val b = randReminder(9).copy(
            targetCount = null,
            hlc = com.athkar.core.clock.Hlc.fromWallMillis(baseWall + 10),
            writerId = "dev-B",
            title = "title-9",
        )
        val merged = EntityConflictResolver.merge(a, b)
        assertTrue(merged.pinned == null)
        assertEquals("title-9", merged.title)
    }
}
