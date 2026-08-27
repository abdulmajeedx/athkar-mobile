package com.athkar.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.athkar.core.domain.AdhkarReminder
import com.athkar.core.clock.Hlc

/**
 * Room entity for a dhikr / remembrance reminder.
 *
 * Indexing policy (delivery brief): every column participating in filtering or ordering is indexed.
 * The main list is read by sort order, so [catOrder] and [pinned] carry indices; the sync engine
 * filters by [serverHlc] (cursor page), and [tombstoned] gates the query.
 *
 * WAL mode is enabled at the database level; the local main-list query must stay under 30 ms on
 * 10k rows (verified by the list benchmark in androidTest).
 */
@Entity(
    tableName = "adhkar_entities",
    indices = [
        Index("serverHlc"),
        Index("catOrder"),
        Index("pinned"),
        Index("tombstoned"),
    ],
)
data class AdhkarEntity(
    @PrimaryKey val id: String,
    val title: String?,
    val body: String?,
    val targetCount: Int?,
    val timesJson: String?,
    val catOrder: Int?,
    val pinned: Boolean?,
    val serverHlc: Long,
    val writerId: String,
    val tombstoned: Boolean,
    val updatedAtMillis: Long,
) {
    fun toDomain(): AdhkarReminder = AdhkarReminder(
        id = id,
        title = title,
        body = body,
        targetCount = targetCount,
        times = TimesCodec.decode(timesJson),
        catOrder = catOrder,
        pinned = pinned,
        hlc = Hlc.decode(serverHlc),
        writerId = writerId,
        tombstoned = tombstoned,
    )

    companion object {
        fun fromDomain(r: AdhkarReminder, nowMillis: Long): AdhkarEntity = AdhkarEntity(
            id = r.id,
            title = r.title,
            body = r.body,
            targetCount = r.targetCount,
            timesJson = TimesCodec.encode(r.times),
            catOrder = r.catOrder,
            pinned = r.pinned,
            serverHlc = r.hlc.value,
            writerId = r.writerId,
            tombstoned = r.tombstoned,
            updatedAtMillis = nowMillis,
        )
    }
}

object TimesCodec {
    fun encode(times: Set<String>?): String? = times?.sorted()?.joinToString(",")
    fun decode(json: String?): Set<String>? = json?.takeIf { it.isNotBlank() }?.split(",")?.toSet()
}
