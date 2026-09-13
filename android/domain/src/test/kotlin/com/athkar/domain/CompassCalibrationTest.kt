package com.athkar.domain

import com.athkar.core.prayer.Coordinates
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The arithmetic behind the solar correction.
 *
 * Every one of these is a way the app could confidently point someone at the wrong wall, which is
 * the only kind of bug a qibla feature really has. The angles wrap at 360 and the errors that live
 * there — a correction applied across north, a sighting taken facing south — do not announce
 * themselves on screen: they look like a perfectly ordinary bearing.
 */
class CompassCalibrationTest {

    private val riyadh = Coordinates(24.7136, 46.6753)
    private val noon = Instant.parse("2026-09-13T09:00:00Z")

    private fun sighting(
        method: SightingMethod = SightingMethod.SHADOW,
        sunAzimuth: Double,
        rawHeading: Float,
        at: Coordinates = riyadh,
        takenAt: Instant = noon,
    ) = CompassCalibration.fromSighting(method, sunAzimuth, rawHeading, takenAt, at)

    @Test
    fun `a compass reading low is corrected upward by the difference`() {
        // The sun is truly at 200; aimed at it, the phone claimed 190. Everything it says is ten
        // degrees light.
        val calibration = sighting(SightingMethod.SUN, sunAzimuth = 200.0, rawHeading = 190f)
        assertEquals(10.0, calibration.offsetDegrees, 0.001)
        assertEquals(200f, calibration.correct(190f), 0.001f)
        assertEquals(10f, calibration.correct(0f), 0.001f)
    }

    @Test
    fun `the shadow points the opposite way from the sun`() {
        // Sun in the south-east; the shadow of an upright thing runs to the north-west.
        val calibration = sighting(SightingMethod.SHADOW, sunAzimuth = 120.0, rawHeading = 300f)
        assertEquals(0.0, calibration.offsetDegrees, 0.001, "a perfect compass needs no correction")

        val off = sighting(SightingMethod.SHADOW, sunAzimuth = 120.0, rawHeading = 290f)
        assertEquals(10.0, off.offsetDegrees, 0.001)
    }

    @Test
    fun `a correction taken across north does not send the needle the long way round`() {
        // True 5, compass says 355: the offset is ten degrees, not three hundred and fifty.
        val calibration = sighting(SightingMethod.SUN, sunAzimuth = 5.0, rawHeading = 355f)
        assertEquals(10.0, calibration.offsetDegrees, 0.001)
        assertEquals(10.0, calibration.signedOffset, 0.001)
        assertEquals(5f, calibration.correct(355f), 0.001f)
    }

    @Test
    fun `a compass reading high is reported as a negative turn`() {
        // True 350, compass says 10 — it reads twenty degrees heavy, and saying so as "+340" would
        // be arithmetically right and useless to read.
        val calibration = sighting(SightingMethod.SUN, sunAzimuth = 350.0, rawHeading = 10f)
        assertEquals(340.0, calibration.offsetDegrees, 0.001)
        assertEquals(-20.0, calibration.signedOffset, 0.001)
        assertEquals(350f, calibration.correct(10f), 0.001f)
    }

    @Test
    fun `every corrected heading stays a bearing`() {
        val calibration = sighting(SightingMethod.SUN, sunAzimuth = 350.0, rawHeading = 10f)
        for (raw in 0..359) {
            val corrected = calibration.correct(raw.toFloat())
            assertTrue(corrected >= 0f && corrected < 360f, "$raw corrected to $corrected")
        }
    }

    @Test
    fun `a correction expires with the afternoon`() {
        val calibration = sighting(SightingMethod.SHADOW, sunAzimuth = 120.0, rawHeading = 300f)
        assertFalse(calibration.isStale(noon.plus(Duration.ofHours(5)), riyadh))
        assertTrue(calibration.isStale(noon.plus(Duration.ofHours(7)), riyadh))
    }

    @Test
    fun `a correction does not travel with the user`() {
        val calibration = sighting(SightingMethod.SHADOW, sunAzimuth = 120.0, rawHeading = 300f)
        // Across the city is still the same magnetic environment as far as this is concerned.
        assertFalse(calibration.isStale(noon, Coordinates(24.78, 46.72)))
        // Another city is not. The iron it cancelled is a hundred kilometres behind.
        assertTrue(calibration.isStale(noon, Coordinates(26.4207, 50.0888)))
    }

    @Test
    fun `an unknown position cannot make a fresh correction stale`() {
        // The place is still loading; that is not evidence the user has moved, and throwing the
        // correction away on no evidence would mean it never survived a screen being reopened.
        val calibration = sighting(SightingMethod.SHADOW, sunAzimuth = 120.0, rawHeading = 300f)
        assertFalse(calibration.isStale(noon, null))
    }

    @Test
    fun `the sun is refused when it is down, and when it is overhead`() {
        assertFalse(CompassCalibration.canSight(-4.0), "below the horizon there is nothing to sight")
        assertFalse(CompassCalibration.canSight(0.5), "and nothing casting a defined edge")
        assertTrue(CompassCalibration.canSight(20.0))
        assertTrue(CompassCalibration.canSight(65.0))
        // Near the zenith the azimuth sweeps too fast and the shadow is too short to aim with.
        assertFalse(CompassCalibration.canSight(78.0))
    }

    @Test
    fun `the shadow is claimed to be the better sighting, and the numbers say so`() {
        assertTrue(
            SightingMethod.SHADOW.expectedErrorDegrees < SightingMethod.SUN.expectedErrorDegrees,
        )
    }

    @Test
    fun `bearings are compared the short way round`() {
        assertTrue(bearingsAgree(359.0, 1.0, tolerance = 3.0))
        assertTrue(bearingsAgree(1.0, 359.0, tolerance = 3.0))
        assertFalse(bearingsAgree(350.0, 10.0, tolerance = 15.0))
        assertTrue(bearingsAgree(180.0, 183.0, tolerance = 3.0))
    }
}
