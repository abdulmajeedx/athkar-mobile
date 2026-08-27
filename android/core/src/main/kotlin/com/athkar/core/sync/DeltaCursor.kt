package com.athkar.core.sync

/**
 * Cursor a client sends to request the next page of changes from the server. The cursor is opaque
 * to the client — it is a server-issued position key that encodes (usually) the max server HLC / a
 * monotonic commit id it has already seen.
 *
 * Delta sync contract (mirrors OpenAPI `/sync/changes`):
 *   - Client sends `cursor` of the last successful page; server returns up to PAGE_SIZE records whose
 *     server HLC is strictly greater than the cursor's position.
 *   - If `hasMore`, the response carries a `nextCursor` the client uses to resume; a mid-stream
 *     network failure resumes from the last *decoded* page, never from the start.
 *   - Page size capped at [PAGE_SIZE] = 200; payload budget per page < 256 KiB (enforced at the
 *     transport layer via Brotli + hard cap).
 */
data class DeltaCursor(val value: String) {
    val isInitial: Boolean get() = value.isEmpty()
    companion object { const val INITIAL_VALUE = "" }
}

const val SYNC_PAGE_SIZE: Int = 200

data class DeltaChange<T>(
    val entity: T,
    val serverHlc: Long,          // decoded server HLC used to advance the cursor
)

data class DeltaPage<T>(
    val changes: List<DeltaChange<T>>,
    val nextCursor: DeltaCursor,
    val hasMore: Boolean,
)

/**
 * Cursor advancement without IO. Given the previous cursor and the decoded change list, produce the
 * next cursor (the max serverHlc observed). Robust to out-of-order server delivery because it takes
 * the max, and idempotent — applying the same page twice yields the same next cursor.
 */
object CursorLogic {
    fun advance(previous: DeltaCursor, changes: List<DeltaChange<*>>): DeltaCursor {
        if (changes.isEmpty()) return previous
        val maxHlc = changes.maxOf { it.serverHlc }
        return DeltaCursor(maxHlc.toString())
    }
}
