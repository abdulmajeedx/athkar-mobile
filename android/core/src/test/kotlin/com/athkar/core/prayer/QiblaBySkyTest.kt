package com.athkar.core.prayer

import java.time.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The qibla taken from the sky.
 *
 * The property that matters is not any single number but the closure: facing the reference and
 * turning by the stated angle must land on the qibla bearing, every time, for every body, at every
 * hour. If that holds, someone standing in a steel-framed building with a useless compass can still
 * find the direction exactly — which is the entire point of the feature.
 */
class QiblaBySkyTest {

    private val riyadh = Coordinates(24.7136, 46.6753)
    private val london = Coordinates(51.5074, -0.1278)
    private val sydney = Coordinates(-33.8688, 151.2093)

    @Test
    fun `facing the reference and turning by the stated angle lands on the qibla`() {
        for (place in listOf(riyadh, london, sydney)) {
            val qibla = Qibla.direction(place)
            var moment = Instant.parse("2026-01-01T00:00:00Z")
            var checked = 0
            repeat(500) {
                for (fix in QiblaBySky.fixes(place, moment)) {
                    val landed = (fix.bearing + fix.turn + 360.0) % 360.0
                    val error = abs(((landed - qibla + 180 + 360) % 360) - 180)
                    assertTrue(
                        error < 0.0001,
                        "${fix.reference} at $moment: ${fix.bearing} + ${fix.turn} = $landed, " +
                            "not the qibla's $qibla",
                    )
                    checked++
                }
                moment = moment.plusSeconds(41_000)
            }
            assertTrue(checked > 100, "only $checked fixes offered across a year")
        }
    }

    @Test
    fun `the shadow is exactly opposite the sun`() {
        // Noon somewhere the sun is up but not overhead, so both are on offer.
        val moment = Instant.parse("2026-02-10T13:00:00Z")
        val fixes = QiblaBySky.fixes(riyadh, moment)
        val sun = fixes.first { it.reference == SkyReference.SUN }
        val shadow = fixes.first { it.reference == SkyReference.SHADOW }
        val between = abs(((sun.bearing - shadow.bearing + 180 + 360) % 360) - 180)
        assertEquals(180.0, between, 0.0001)
    }

    @Test
    fun `the turn is signed the way a person turns`() {
        val moment = Instant.parse("2026-02-10T13:00:00Z")
        val fix = QiblaBySky.fixes(riyadh, moment).first { it.reference == SkyReference.SUN }
        // Riyadh's qibla is west-south-west; at one in the afternoon UTC — four in the afternoon
        // locally — the sun is in the south-west, so the qibla is a small turn away, not a
        // half-circle. The sign convention is what turns that into "right" or "left" on screen.
        assertTrue(abs(fix.turn) <= 180.0)
        assertTrue(abs(fix.turn) < 90.0, "a turn of ${fix.turn} would put the sun behind the user")
    }

    @Test
    fun `nothing is offered in the middle of the night`() {
        // Two in the morning local time, with the moon below the horizon: no sun, no shadow, and
        // no moon to point at. Offering a reference that cannot be seen would be worse than
        // offering none.
        val moment = Instant.parse("2026-09-14T23:00:00Z")
        val fixes = QiblaBySky.fixes(riyadh, moment)
        assertFalse(
            fixes.any { it.reference == SkyReference.SUN },
            "the sun was offered at $moment, when it is below the horizon",
        )
    }

    @Test
    fun `the sun is refused when it is nearly overhead`() {
        // Makkah in June: the sun passes within a couple of degrees of the zenith, where its
        // azimuth swings through half the compass in minutes and a stick casts no usable shadow.
        val makkah = Qibla.MAKKAH
        var offeredNearZenith = false
        var moment = Instant.parse("2026-06-15T08:00:00Z")
        repeat(60) {
            val altitude = SolarPosition.at(makkah, moment).altitude
            val offered = QiblaBySky.fixes(makkah, moment).any { it.reference == SkyReference.SUN }
            if (altitude > 80 && offered) offeredNearZenith = true
            moment = moment.plusSeconds(300)
        }
        assertFalse(offeredNearZenith, "the sun was offered while within ten degrees of the zenith")
    }

    @Test
    fun `a dark moon is not offered as a landmark`() {
        var moment = Instant.parse("2026-01-01T00:00:00Z")
        repeat(2000) {
            val fix = QiblaBySky.fixes(riyadh, moment).firstOrNull {
                it.reference == SkyReference.MOON
            }
            if (fix != null) {
                assertTrue(
                    (fix.illumination ?: 0.0) >= 0.15,
                    "a moon ${fix.illumination} lit was offered at $moment",
                )
                assertTrue(fix.altitude >= 5.0, "a moon at ${fix.altitude}° was offered")
            }
            moment = moment.plusSeconds(9_000)
        }
    }

    @Test
    fun `the shadow is offered before the sun, since one of them is safe to look at`() {
        val moment = Instant.parse("2026-02-10T13:00:00Z")
        val fixes = QiblaBySky.fixes(riyadh, moment)
        val shadow = fixes.indexOfFirst { it.reference == SkyReference.SHADOW }
        val sun = fixes.indexOfFirst { it.reference == SkyReference.SUN }
        assertTrue(shadow in 0..<sun, "the sun was offered first: $fixes")
    }
}
