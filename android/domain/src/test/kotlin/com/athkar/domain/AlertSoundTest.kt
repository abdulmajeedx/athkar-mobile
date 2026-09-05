package com.athkar.domain

import com.athkar.core.prayer.Prayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * The stored value is a bare enum name, so the two things that can go wrong are a name this build
 * does not know and a prayer that should never be called to. Both are covered here rather than
 * discovered at four in the morning.
 */
class AlertSoundTest {

    @Test
    fun `every name round-trips`() {
        for (sound in AlertSound.entries) {
            assertSame(sound, AlertSound.fromName(sound.name), "round trip of ${sound.name}")
        }
    }

    @Test
    fun `an absent or unrecognised name falls back to the default`() {
        assertEquals(AlertSound.DEFAULT, AlertSound.fromName(null))
        assertEquals(AlertSound.DEFAULT, AlertSound.fromName(""))
        assertEquals(AlertSound.DEFAULT, AlertSound.fromName("MUEZZIN_FROM_A_LATER_VERSION"))
        // Names are matched exactly; a lower-case spelling is not a name this build wrote.
        assertEquals(AlertSound.DEFAULT, AlertSound.fromName("adhan"))
    }

    @Test
    fun `sunrise never gets the adhan`() {
        assertEquals(AlertSound.DEVICE_ALARM, AlertSound.ADHAN.forPrayer(Prayer.SUNRISE))
    }

    @Test
    fun `sunrise keeps every other choice as it is`() {
        assertEquals(AlertSound.SILENT, AlertSound.SILENT.forPrayer(Prayer.SUNRISE))
        assertEquals(AlertSound.DEVICE_ALARM, AlertSound.DEVICE_ALARM.forPrayer(Prayer.SUNRISE))
    }

    @Test
    fun `the five called prayers keep the chosen sound`() {
        val called = Prayer.entries - Prayer.SUNRISE
        for (prayer in called) {
            for (sound in AlertSound.entries) {
                assertSame(sound, sound.forPrayer(prayer), "${sound.name} at ${prayer.name}")
            }
        }
    }

    @Test
    fun `the default is the adhan, which is what the setting exists to offer`() {
        assertEquals(AlertSound.ADHAN, AlertSound.DEFAULT)
        assertEquals(AlertSound.ADHAN, PrayerPreferences().alertSound)
    }
}
