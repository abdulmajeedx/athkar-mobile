package com.athkar.core.crdt

import com.athkar.core.clock.Hlc

/**
 * A Last-Writer-Wins register over a single field.
 *
 * Merge semantics: the value written at the greatest (fieldHlc, elementId) wins. It is
 * associative, commutative and idempotent by construction, because the winner is a pure function of
 * the full set of (value, hlc, writerId) writes observed — order of delivery does not matter.
 */
data class Lww<out T>(
    val value: T?,
    val hlc: Hlc,
    val writerId: String,
) {
    fun isPresent(): Boolean = value != null
}

/**
 * Registry of fields that are excluded from automated LWW resolution.
 *
 * Per the delivery brief: financial and security-sensitive fields must never silently converge on
 * a last-writer-wins basis. They are routed to a manual merge screen that shows both versions side
 * by side. This registry is the single, auditable source of truth for that rule.
 */
object ManualMergeFields {
    // Example for the domain at hand: no financial fields exist, but a future reserved
    // field ("paymentStatus" / "billingProfileId") demonstrates the mechanism.
    val RESERVED: Set<String> = setOf("paymentStatus", "billingProfileId", "devicePinHash")

    fun isSensitive(field: String): Boolean = field in RESERVED
}

/**
 * Resolves a conflict over a single [field] between a local and a remote write.
 *
 * @return a [Resolution] describing either the converged value or a manual-merge requirement.
 */
object FieldConflictResolver {

    sealed interface Resolution<T> {
        /** Converged value; safe to apply automatically. */
        data class Resolved<T>(val value: T?, val hlc: Hlc, val writerId: String) : Resolution<T>
        /** Both versions must be shown side by side for a human decision. */
        data class ManualMergeRequired<T>(val left: Lww<T>, val right: Lww<T>) : Resolution<T>
    }

    fun <T> resolve(
        field: String,
        local: Lww<T>,
        remote: Lww<T>,
    ): Resolution<T> = when {
        // Sensitive fields are never auto-merged.
        ManualMergeFields.isSensitive(field) -> Resolution.ManualMergeRequired(local, remote)
        // Local strictly newer (or equal then local writer id tie-break for determinism).
        remote.hlc > local.hlc -> Resolution.Resolved(remote.value, remote.hlc, remote.writerId)
        local.hlc > remote.hlc -> Resolution.Resolved(local.value, local.hlc, local.writerId)
        // Equal HLC: resolve deterministically by writerId so all replicas agree regardless of order.
        local.writerId >= remote.writerId ->
            Resolution.Resolved(local.value, local.hlc, local.writerId)
        else -> Resolution.Resolved(remote.value, remote.hlc, remote.writerId)
    }
}
