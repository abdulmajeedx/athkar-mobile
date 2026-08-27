package com.athkar.core.domain

import com.athkar.core.clock.Hlc
import com.athkar.core.crdt.FieldConflictResolver
import com.athkar.core.crdt.Lww
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * A single dhikr / adhkar reminder entity.
 *
 * This is the shared, platform-free domain model. Both the Android (Room) and iOS (GRDB) data layers
 * map this type to their local storage, and the shared OpenAPI contract serializes it.
 *
 * The entity is treated as a bag of [Lww] fields; the [EntityConflictResolver] merges field-by-field.
 *
 * @property id UUID v7 (time-ordered); primary key on both platforms.
 * @property title The dhikr text / title.
 * @property body The full text to be recited.
 * @property targetCount Number of repetitions for a complete khatm / wird.
 * @property times Periodic reminders (adhan, morning, evening, afterPrayer, custom).
 * @property catOrder Sort order within a category.
 * @property hlc Server-issued HLC of the last write to this entity.
 * @property writerId Opaque id of the last writer (device or user) — used only for deterministic
 *                     tie-breaking, never for identity/authorization decisions.
 * @property tombstoned True when the record is a logical delete (see outbox / tombstone policy).
 */
data class AdhkarReminder(
    val id: String,
    val title: String?,
    val body: String?,
    val targetCount: Int?,
    val times: Set<String>?,
    val catOrder: Int?,
    val pinned: Boolean?,
    val hlc: Hlc,
    val writerId: String,
    val tombstoned: Boolean = false,
)

/**
 * Non-sensitive scalar fields that participate in automatic LWW resolution. The `id` is immutable
 * (never merges); sensitive fields are routed to manual merge via [ManualMergeFields].
 */
enum class Field(val key: String) {
    Title("title"),
    Body("body"),
    TargetCount("targetCount"),
    Times("times"),
    CatOrder("catOrder"),
    Pinned("pinned"),
}

/** Immutable atomic intent targeting a single field write on a single entity. */
data class FieldWrite(
    val entityId: String,
    val field: Field,
    val value: Any?,
    val hlc: Hlc,
    val writerId: String,
)

/**
 * Per-field LWW entity merger.
 *
 * Guarantees (all verified by property-based tests -> see ConflictResolverPropertyTest):
 *   - Associative, commutative, idempotent over the merge operation.
 *   - Deterministic final state across N devices regardless of delivery order.
 *   - Tombstone wins over any non-tombstone write (a deleted record stays deleted), matching the
 *     "deletes defeat all" convention used so a tombstone is never resurrected.
 */
object EntityConflictResolver {

    /** Merge field-by-field; returns the converged entity. */
    fun merge(entities: List<AdhkarReminder>): AdhkarReminder {
        val first = entities.first()
        if (entities.size == 1) return first
        return entities.drop(1).fold(first) { acc, e -> mergePair(acc, e) }
    }

    fun merge(a: AdhkarReminder, b: AdhkarReminder): AdhkarReminder = mergePair(a, b)

    private fun mergePair(a: AdhkarReminder, b: AdhkarReminder): AdhkarReminder {
        // Immutable identity.
        if (a.id != b.id) error("Cannot merge entities with different ids: ${a.id} vs ${b.id}")

        // Tombstone defeats all.
        if (a.tombstoned || b.tombstoned) {
            val t = if (a.tombstoned) a else b
            return t
        }

        val hlc = Hlc.max(a.hlc, b.hlc)
        // Total order on (hlc, writerId): writer of the globally-max element. Symmetric in its
        // arguments, so merge(a,b) and merge(b,a) agree; also associative across folds (max is
        // associative), which is required for 3+ device convergence.
        val writer = when {
            a.hlc != b.hlc -> if (a.hlc > b.hlc) a.writerId else b.writerId
            a.writerId >= b.writerId -> a.writerId
            else -> b.writerId
        }

        return AdhkarReminder(
            id = a.id,
            title = mergeField(Field.Title, a.title, b.title, a, b),
            body = mergeField(Field.Body, a.body, b.body, a, b),
            targetCount = mergeField(Field.TargetCount, a.targetCount, b.targetCount, a, b),
            times = mergeField(Field.Times, a.times, b.times, a, b),
            catOrder = mergeField(Field.CatOrder, a.catOrder, b.catOrder, a, b),
            pinned = mergeField(Field.Pinned, a.pinned, b.pinned, a, b),
            hlc = hlc,
            writerId = writer,
            tombstoned = false,
        )
    }

    private fun <T> mergeField(
        field: Field,
        aValue: T?,
        bValue: T?,
        a: AdhkarReminder,
        b: AdhkarReminder,
    ): T? {
        val resolution = FieldConflictResolver.resolve(
            field = field.key,
            local = Lww(aValue, a.hlc, a.writerId),
            remote = Lww(bValue, b.hlc, b.writerId),
        )
        return when (resolution) {
            is FieldConflictResolver.Resolution.Resolved<*> -> @Suppress("UNCHECKED_CAST")
                resolution.value as? T
            is FieldConflictResolver.Resolution.ManualMergeRequired<*> ->
                // No sensitive fields exist in this domain today; guard for future-proofing.
                (if (a.hlc >= b.hlc) aValue else bValue)
        }
    }

    /** Convenience that turns a list of writes into a stable snapshot (used in tests & docs). */
    fun lastWriteWins(writes: List<FieldWrite>): AdhkarReminder {
        val byEntity = writes.first().entityId
        var hlc = Hlc.minValue()
        var writer = ""
        val fields = mutableMapOf<Field, Any?>()
        for (w in writes) {
            if (w.entityId != byEntity) error("FieldWrite list must be single-entity")
            val existing = fields[w.field]
            if (existing == null || w.hlc > hlc) {
                fields[w.field] = w.value
            }
            if (w.hlc > hlc) { hlc = w.hlc; writer = w.writerId }
        }
        return AdhkarReminder(
            id = byEntity,
            title = fields[Field.Title] as? String,
            body = fields[Field.Body] as? String,
            targetCount = fields[Field.TargetCount] as? Int,
            times = fields[Field.Times] as? Set<String>,
            catOrder = fields[Field.CatOrder] as? Int,
            pinned = fields[Field.Pinned] as? Boolean,
            hlc = hlc,
            writerId = writer,
        )
    }
}
