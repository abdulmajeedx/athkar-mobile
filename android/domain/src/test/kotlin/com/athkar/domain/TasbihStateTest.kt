package com.athkar.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The counting rules, which are the whole of the tasbih worth testing: the screen is a ring and a
 * number, and everything that can be wrong about it is in here.
 */
class TasbihStateTest {

    @Test
    fun `counting advances within the round`() {
        val state = TasbihState(target = 33).increment().increment()
        assertEquals(2, state.count)
        assertEquals(0, state.rounds)
    }

    @Test
    fun `reaching the target opens a new round rather than stopping`() {
        var state = TasbihState(target = 3)
        repeat(3) { state = state.increment() }
        assertEquals(0, state.count, "the count restarts")
        assertEquals(1, state.rounds, "and the round is banked")
    }

    @Test
    fun `rounds accumulate across many passes`() {
        var state = TasbihState(target = 33)
        repeat(33 * 3) { state = state.increment() }
        assertEquals(3, state.rounds)
        assertEquals(0, state.count)
    }

    @Test
    fun `reset clears the count and the rounds together`() {
        var state = TasbihState(target = 3)
        repeat(7) { state = state.increment() }
        val cleared = state.reset()
        assertEquals(0, cleared.count)
        assertEquals(0, cleared.rounds)
        assertEquals(state.dhikr, cleared.dhikr, "the phrase is not a thing reset undoes")
        assertEquals(state.target, cleared.target, "nor the target")
    }

    @Test
    fun `choosing a phrase brings its own target and starts fresh`() {
        val state = TasbihState(target = 33).increment().increment()
            .select(Dhikr.LA_ILAHA_ILLA_ALLAH)
        assertEquals(Dhikr.LA_ILAHA_ILLA_ALLAH, state.dhikr)
        assertEquals(100, state.target)
        assertEquals(0, state.count)
        assertEquals(0, state.rounds)
    }

    @Test
    fun `a target below one is refused, or every tap would complete a round`() {
        assertEquals(1, TasbihState().retarget(0).target)
        assertEquals(1, TasbihState().retarget(-40).target)
    }

    @Test
    fun `progress runs from empty to full within a round`() {
        val state = TasbihState(target = 4)
        assertEquals(0f, state.progress)
        assertEquals(0.5f, state.increment().increment().progress)
        // The fourth tap banks the round and the ring starts over rather than sitting full.
        var full = state
        repeat(4) { full = full.increment() }
        assertEquals(0f, full.progress)
        assertTrue(full.isRoundComplete)
    }

    @Test
    fun `an unknown stored phrase falls back instead of crashing`() {
        assertEquals(Dhikr.DEFAULT, Dhikr.fromName(null))
        assertEquals(Dhikr.DEFAULT, Dhikr.fromName("A_PHRASE_FROM_A_LATER_VERSION"))
        for (dhikr in Dhikr.entries) assertEquals(dhikr, Dhikr.fromName(dhikr.name))
    }

    @Test
    fun `every phrase carries a usable target`() {
        for (dhikr in Dhikr.entries) {
            assertTrue(dhikr.defaultTarget >= 1, "${dhikr.name} would complete on every tap")
            assertTrue(dhikr.arabic.isNotBlank(), "${dhikr.name} has nothing to show")
        }
    }
}
