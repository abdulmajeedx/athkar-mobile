package com.athkar.feature.prayertimes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the qibla screen is allowed to conclude from a sensor reading.
 *
 * All of it is arithmetic on two angles and a field strength, and all of it decides whether the app
 * tells someone they are facing the Kaaba when they are not — which is the only bug on that screen
 * that matters.
 */
class CompassHeadingTest {

    private fun heading(
        pitch: Float = 0f,
        roll: Float = 0f,
        measured: Float? = null,
        expected: Float? = null,
    ) = CompassSource.Heading(
        trueHeadingDegrees = 0f,
        accuracy = 3,
        fieldStrengthMicroTesla = measured,
        expectedFieldStrengthMicroTesla = expected,
        pitchDegrees = pitch,
        rollDegrees = roll,
    )

    @Test
    fun `tilt combines both axes rather than taking the larger`() {
        // Three degrees of pitch and four of roll is five degrees off flat, not four: a phone
        // leaning in two directions at once is further from level than either number alone says.
        assertEquals(5f, heading(pitch = 3f, roll = 4f).tiltDegrees, 0.001f)
        assertEquals(0f, heading().tiltDegrees, 0.001f)
    }

    @Test
    fun `level is a band a hand can hold, and tilted is well outside it`() {
        assertTrue(heading(pitch = 5f).isLevel)
        assertTrue(heading(pitch = -5f).isLevel, "leaning back is as level as leaning forward")
        assertTrue(heading(pitch = 6f, roll = 6f).isLevel, "8.5 degrees off is still a held hand")
        assertFalse(heading(pitch = 12f).isLevel)

        // The two thresholds are not the same line: between them the reading is usable but not
        // confirmed, which is the state most people hold a phone in.
        assertFalse(heading(pitch = 20f).isLevel)
        assertFalse(heading(pitch = 20f).isTooTilted)
        assertTrue(heading(pitch = 40f).isTooTilted)
    }

    @Test
    fun `a field far from the model's value is reported as disturbed`() {
        // The model says 48 microtesla here; a fifth off is past calibration drift.
        assertTrue(heading(measured = 20f, expected = 48f).isFieldDisturbed)
        assertTrue(heading(measured = 90f, expected = 48f).isFieldDisturbed)
        assertFalse(heading(measured = 46f, expected = 48f).isFieldDisturbed)
    }

    @Test
    fun `nothing is claimed about a field that has not been measured`() {
        // Silence is not evidence of a clean field: before the magnetometer reports, the app has
        // no grounds to either warn or reassure.
        assertFalse(heading(measured = null, expected = 48f).isFieldDisturbed)
        assertFalse(heading(measured = 48f, expected = null).isFieldDisturbed)
    }
}
