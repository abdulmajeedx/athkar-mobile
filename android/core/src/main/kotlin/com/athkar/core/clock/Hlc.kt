package com.athkar.core.clock

import kotlin.math.max

/**
 * Hybrid Logical Clock (HLC) per Bailis et al. "Logical Physical Clocks and Consistent Snapshots in
 * Globally Distributed Databases" (2014).
 *
 * WHY we use HLC and not the device wall-clock (per the delivery brief):
 *   - Device clocks drift and users can change them, silently corrupting merge order under LWW.
 *   - The physical component is *issued by the server*, so a client can never fabricate a timestamp
 *     that competes with server time. Clients only *advance* the logical counter.
 *
 * Encoding: 64-bit long -> (wallMillis: 48 bits, logical counter: 16 bits). Compact for storage and
 * wire transfer (`compareTo` is a plain long comparison, so HLC doubles as a total order key).
 */
class Hlc private constructor(val value: Long) : Comparable<Hlc> {

    val wallMillis: Long get() = value ushr 16
    val counter: Int get() = value.toInt() and 0xFFFF

    override fun compareTo(other: Hlc): Int = value.compareTo(other.value)

    override fun equals(other: Any?): Boolean = (other as? Hlc)?.value == value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = "Hlc(${wallMillis}.${counter})"

    companion object {
        // Layout: unix_ts_ms (48 bits) at bits 16..63, logical counter (16 bits) at bits 0..15.
        // A 48-bit timestamp << 16 fills exactly 64 bits without overflow in practice (the top bit
        // boundary is ~year 6500, outside any real deployment window).
        private const val WALL_MASK = 0x0000FFFF_FFFFFFFFL

        /** Creating a fresh HLC from a server-issued physical time (millis). Counter starts at 0. */
        @JvmStatic
        fun fromWallMillis(wallMillis: Long): Hlc =
            Hlc((wallMillis and WALL_MASK) shl 16)

        @JvmStatic
        fun fromComponents(wallMillis: Long, counter: Int): Hlc =
            Hlc(((wallMillis and WALL_MASK) shl 16) or (counter.toLong() and 0xFFFF))

        @JvmStatic
        fun decode(value: Long): Hlc = Hlc(value)

        /**
         * Produce a new HLC strictly greater than `last` (the max HLC this replica / the server has
         * seen) using `wallMillis` as the candidate physical time.
         *
         * Invariants guaranteed:
         *   - result > last
         *   - result.wallMillis >= last.wallMillis
         *   - if wallMillis > last.wallMillis then result.wallMillis == wallMillis and counter == 0
         */
        @JvmStatic
        fun tick(last: Hlc?, wallMillis: Long): Hlc {
            val lastValue = last?.value ?: 0L
            val lastWall = lastValue ushr 16
            return when {
                wallMillis > lastWall -> fromWallMillis(wallMillis)
                last == null -> fromWallMillis(wallMillis)
                else -> Hlc(lastValue + 1) // bump logical counter; produces value > last and same wall
            }
        }

        /** Merge of two HLCs: the elementwise max. Used when a replica learns about both. */
        @JvmStatic
        fun max(a: Hlc, b: Hlc): Hlc = if (a.value >= b.value) a else b

        /** Update of a locally-held HLC when observing an incoming remote HLC [remote]. */
        @JvmStatic
        fun observe(local: Hlc?, remote: Hlc, wallMillis: Long): Hlc {
            val base = max(local ?: minValue(), remote)
            return tick(base, wallMillis)
        }

        @JvmStatic
        fun minValue(): Hlc = Hlc(0L)

        @JvmStatic
        fun maxValue(): Hlc = Hlc(Long.MAX_VALUE)
    }
}
