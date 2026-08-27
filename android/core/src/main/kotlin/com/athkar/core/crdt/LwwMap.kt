package com.athkar.core.crdt

import com.athkar.core.clock.Hlc

/**
 * LWW-Element-Map (Last-Writer-Wins) — a classic CRDT used to sync small settings maps
 * (language, prayer method, offsets, notification toggles) across devices with automatic,
 * order-independent convergence.
 *
 * The map is a projection over per-key [Lww] registers; merging is the pointwise merge. This CRDT is
 * associative, commutative and idempotent, so any number of devices can merge in any order and
 * converge on the identical map. Verified by property tests in CrdtMapPropertyTest.
 */
data class LwwMap<K, V>(
    private val registers: Map<K, Lww<V>> = emptyMap(),
) {

    fun get(key: K): V? = registers[key]?.value

    fun set(key: K, value: V?, hlc: Hlc, writerId: String): LwwMap<K, V> {
        val reg = Lww(value, hlc, writerId)
        // A tombstone (null value with newer hlc) is retained so it can defeat stale writes.
        val updated = registers + (key to reg)
        return LwwMap(updated)
    }

    fun asMap(): Map<K, V> = registers.mapNotNull { (k, r) -> r.value?.let { k to it } }.toMap()

    /** Pointwise maximum merge. Deterministic regardless of caller order (`merge(a,b)==merge(b,a)`). */
    fun merge(other: LwwMap<K, V>): LwwMap<K, V> {
        val keys = registers.keys + other.registers.keys
        val merged = keys.associateWith { key ->
            val a = registers[key]
            val b = other.registers[key]
            when {
                a == null -> b!!
                b == null -> a
                a.hlc > b.hlc -> a
                b.hlc > a.hlc -> b
                a.writerId >= b.writerId -> a
                else -> b
            }
        }
        return LwwMap(merged)
    }

    companion object {
        fun <K, V> empty(): LwwMap<K, V> = LwwMap()
    }
}
