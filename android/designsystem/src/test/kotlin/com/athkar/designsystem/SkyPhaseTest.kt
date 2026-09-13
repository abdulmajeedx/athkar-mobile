package com.athkar.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The palette that changes with the hour, and the two ways it can be chosen: from the user's own
 * prayer times, or — before a place is known — from the clock.
 */
class SkyPhaseTest {

    @Test
    fun `each prayer brings its own sky`() {
        assertEquals(SkyPhase.DAWN, SkyPhase.forPrayerOrdinal(0))
        assertEquals(SkyPhase.SUNRISE, SkyPhase.forPrayerOrdinal(1))
        assertEquals(SkyPhase.NOON, SkyPhase.forPrayerOrdinal(2))
        assertEquals(SkyPhase.AFTERNOON, SkyPhase.forPrayerOrdinal(3))
        assertEquals(SkyPhase.SUNSET, SkyPhase.forPrayerOrdinal(4))
        assertEquals(SkyPhase.NIGHT, SkyPhase.forPrayerOrdinal(5))
    }

    @Test
    fun `before the first prayer of the day it is still the hour before dawn`() {
        assertEquals(SkyPhase.NIGHT_END, SkyPhase.forPrayerOrdinal(null))
        // A prayer this app does not have — a later version's — must not crash the theme.
        assertEquals(SkyPhase.NIGHT_END, SkyPhase.forPrayerOrdinal(9))
    }

    @Test
    fun `every hour of the clock has a sky`() {
        for (hour in 0..23) {
            // The call itself is the assertion: an unmapped hour would fall off the `when`.
            SkyPhase.forHour(hour)
        }
        assertEquals(SkyPhase.NIGHT, SkyPhase.forHour(1))
        assertEquals(SkyPhase.NOON, SkyPhase.forHour(12))
        assertEquals(SkyPhase.AFTERNOON, SkyPhase.forHour(16))
        assertEquals(SkyPhase.SUNSET, SkyPhase.forHour(18))
        assertEquals(SkyPhase.NIGHT, SkyPhase.forHour(23))
    }

    @Test
    fun `the daylight hours are not night`() {
        // This is what flips the whole app between a light page and a dark one, so a phase on the
        // wrong side of it is not a shade too warm — it is the reader's eyes at four in the morning.
        assertFalse(SkyPhase.SUNRISE.isNight)
        assertFalse(SkyPhase.NOON.isNight)
        assertFalse(SkyPhase.AFTERNOON.isNight)
        assertTrue(SkyPhase.SUNSET.isNight)
        assertTrue(SkyPhase.NIGHT.isNight)
        assertTrue(SkyPhase.NIGHT_END.isNight)
        assertTrue(SkyPhase.DAWN.isNight)
    }

    @Test
    fun `every sky is namable and dark enough for white text`() {
        for (phase in SkyPhase.entries) {
            assertTrue(phase.arabicName.isNotBlank(), "${phase.name} has nothing to call itself")
            assertTrue(phase.isDark, "${phase.name} is painted with white text on it")
        }
    }
}
