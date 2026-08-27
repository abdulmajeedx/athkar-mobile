package com.athkar.core.crdt

import kotlin.random.Random

/**
 * UUID v7 (RFC 9562) — time-ordered, roughly monotonic. Generated per the RFC bit layout:
 *
 *   unix_ts_ms (48 bits) | ver(0x7) (4 bits) | rand_a (12 bits)
 *   var(0b10) (2 bits) | rand_b (62 bits)
 *
 * Encoded as two 64-bit halves (`msb`,`lsb`) then rendered in canonical 8-4-4-4-12 form. Because the
 * 48-bit timestamp occupies the top of [msb], the first 12 hex characters are lexicographically
 * ordered by creation time — exactly what the delta-sync cursor and outbox idempotency keys need.
 */
class UuidV7 private constructor(private val msb: Long, private val lsb: Long) {

    override fun toString(): String {
        val sb = StringBuilder(36)
        hex(sb, msb ushr 32, 8); sb.append('-')
        hex(sb, msb ushr 16 and 0xFFFF, 4); sb.append('-')
        hex(sb, msb and 0xFFFF, 4); sb.append('-')
        hex(sb, lsb ushr 48 and 0xFFFF, 4); sb.append('-')
        hex(sb, lsb and 0xFFFFFFFFFFFFL, 12)
        return sb.toString()
    }

    private fun hex(sb: StringBuilder, value: Long, digits: Int) {
        val h = value.toString(16).padStart(digits, '0')
        sb.append(h.takeLast(digits))
    }

    override fun equals(other: Any?): Boolean =
        other is UuidV7 && other.msb == msb && other.lsb == lsb
    override fun hashCode(): Int = (msb xor (lsb ushr 32)).toInt()

    companion object {
        @JvmStatic
        fun random(nowMillis: Long? = null, random: Random = Random.Default): String =
            UuidV7.randomUuid(nowMillis, random).toString()

        private const val VERSION_7 = 0x7L
        private const val VARIANT_RFC = 0b10L

        @JvmStatic
        fun randomUuid(nowMillis: Long?, random: Random): UuidV7 {
            val unixMs = nowMillis ?: System.currentTimeMillis()
            // 48-bit time | 4-bit version | 12-bit rand_a
            val msb = (unixMs shl 16) or (VERSION_7 shl 12) or (random.nextLong() and 0x0FFF)
            // 2-bit RFC variant | 62-bit rand_b
            val lsb = (VARIANT_RFC shl 62) or (random.nextLong() and 0x3FFF_FFFF_FFFF_FFFFL)
            return UuidV7(msb, lsb)
        }
    }
}
