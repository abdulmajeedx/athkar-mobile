package com.athkar.core

import com.athkar.core.clock.Hlc
import com.athkar.core.domain.AdhkarReminder
import kotlin.random.Random

/**
 * Deterministic pseudo-random generators for property-based tests. Seeded so runs are reproducible.
 */
object TestData {
    fun randomReminder(random: Random, seed: Long, writer: String, baseWall: Long): AdhkarReminder {
        val wall = baseWall + random.nextLong(0, 100_000)
        val hlc = Hlc.fromWallMillis(wall)
        return AdhkarReminder(
            id = "ent-$seed",
            title = "title-$seed",
            body = if (random.nextBoolean()) null else "body-$seed",
            targetCount = if (random.nextBoolean()) null else random.nextInt(1, 101),
            times = setOf("morning", "evening"),
            catOrder = random.nextInt(0, 100),
            pinned = random.nextBoolean(),
            hlc = hlc,
            writerId = writer,
            tombstoned = false,
        )
    }

    fun varyField(e: AdhkarReminder, random: Random, writer: String, baseWall: Long): AdhkarReminder {
        val wall = baseWall + random.nextLong(0, 1_000_000)
        val hlc = Hlc.fromWallMillis(wall)
        return e.copy(
            title = if (random.nextBoolean()) e.title else "v-${random.nextInt()}",
            targetCount = if (random.nextBoolean()) e.targetCount else random.nextInt(0, 200),
            pinned = if (random.nextBoolean()) e.pinned else random.nextBoolean(),
            hlc = hlc,
            writerId = writer,
        )
    }
}
