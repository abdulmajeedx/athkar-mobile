package com.athkar.core

import com.athkar.core.clock.Hlc
import com.athkar.core.domain.AdhkarReminder
import com.athkar.core.domain.EntityConflictResolver
import com.athkar.core.domain.Field
import com.athkar.core.domain.FieldWrite
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Property tests for the LWW-map CRDT (settings sync) and HLC clock invariants. */
class CrdtMapPropertyTest {

    private val random = Random(7)

    @Test
    fun `lww map merges are commutative and idempotent`() {
        repeat(500) {
            val keys = listOf("language", "prayerMethod", "notificationOn", "qiblaOffset")
            val m1 = com.athkar.core.crdt.LwwMap.empty<String, Any?>()
            val m2 = com.athkar.core.crdt.LwwMap.empty<String, Any?>()
            repeat(20) {
                val k = keys[Random(it).nextInt(keys.size)]
                m1.set(k, "v-${it}-A", Hlc.fromWallMillis(1_600_000_000_000L + it), "dev-A")
            }
            repeat(20) {
                val k = keys[Random(it + 50).nextInt(keys.size)]
                m2.set(k, "v-${it}-B", Hlc.fromWallMillis(1_600_000_000_000L + it + 1000), "dev-B")
            }

            val fwd = m1.merge(m2)
            val rev = m2.merge(m1)
            assertEquals(fwd.asMap(), rev.asMap(), "commutativity")
            assertEquals(fwd, fwd.merge(fwd), "idempotence")
            assertEquals(fwd, fwd.merge(m1).merge(m2), "left absorption")
            assertEquals(fwd, fwd.merge(m2).merge(m1), "right absorption")
        }
    }

    @Test
    fun `newer hlc wins for same key`() {
        val a = com.athkar.core.crdt.LwwMap.empty<String, Any?>()
            .set("language", "ar", Hlc.fromWallMillis(100), "dev-A")
        val b = com.athkar.core.crdt.LwwMap.empty<String, Any?>()
            .set("language", "en", Hlc.fromWallMillis(200), "dev-B")
        assertEquals("en", a.merge(b).get("language"))
        assertEquals("en", b.merge(a).get("language")) // order-independent
    }

    @Test
    fun `hlc tick is strictly monotonic even with flat wall clock`() {
        var last: Hlc? = null
        repeat(10_000) {
            val next = Hlc.tick(last, wallMillis = 1_600_000_000_000L) // fixed physical time
            if (last != null) assertTrue(next > last, "tick must be strictly monotonic")
            last = next
        }
    }

    @Test
    fun `hlc tick advances physical component when wall clock is ahead`() {
        val last = Hlc.fromWallMillis(1_000).let { Hlc.tick(it, 1_100) }
        val next = Hlc.tick(last, 1_100)
        assertEquals(1_100, next.wallMillis)
        assertEquals(1, next.counter)
        assertTrue(next > last)
    }

    @Test
    fun `hlc observe updates local when remote is newer`() {
        val local = Hlc.fromWallMillis(1_000)
        val remote = Hlc.fromWallMillis(2_000)
        val observed = Hlc.observe(local, remote, wallMillis = 1_500)
        assertEquals(Hlc.max(local, remote).let { Hlc.tick(it, 1_500) }, observed)
        assertTrue(observed > local)
    }

    @Test
    fun `field conflict resolves to deterministic writer tie-break on equal hlc`() {
        val hlc = Hlc.fromWallMillis(5_000)
        val a = AdhkarReminder("id", "A", null, null, null, null, null, hlc, "dev-A")
        val b = AdhkarReminder("id", "B", null, null, null, null, null, hlc, "dev-B")
        val ab = EntityConflictResolver.merge(a, b)
        val ba = EntityConflictResolver.merge(b, a)
        assertEquals(ab, ba, "equal-HLC tie-break must be deterministic regardless of order")
    }

    @Test
    fun `uuid v7 is time-ordered`() {
        val t1 = 1_600_000_000_000L
        val t2 = 1_600_000_000_100L
        val first = com.athkar.core.crdt.UuidV7.random(t1, Random(1))
        val second = com.athkar.core.crdt.UuidV7.random(t2, Random(1))
        assertTrue(first < second, "UUID v7 must be lexicographically ordered by time")
        assertEquals('7', first[14], "version nibble must be 7")
        val variant = first[19]
        assertTrue(variant == '8' || variant == '9' || variant == 'a' || variant == 'b',
            "variant must be RFC 4122 (10xx) but was $variant")
        assertEquals(36, first.length)
    }

    @Test
    fun `lastWriteWins projection picks the newest hlc per field`() {
        val writes = listOf(
            FieldWrite("e1", Field.Title, "one", Hlc.fromWallMillis(1_000), "A"),
            FieldWrite("e1", Field.Title, "two", Hlc.fromWallMillis(2_000), "B"),
            FieldWrite("e1", Field.Pinned, true, Hlc.fromWallMillis(500), "C"),
        )
        val snap = EntityConflictResolver.lastWriteWins(writes)
        assertEquals("two", snap.title)
        assertEquals(true, snap.pinned)
    }
}
