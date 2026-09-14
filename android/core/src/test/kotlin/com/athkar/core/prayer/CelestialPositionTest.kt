package com.athkar.core.prayer

import java.time.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The sun and the moon, against JPL Horizons.
 *
 * The reference values are the ephemeris NASA publishes — the apparent, airless azimuth and
 * elevation for each place and instant, fetched from the Horizons API and pasted here. They are the
 * ground truth for the whole qibla feature, because the direction the app now gives is taken from
 * these two bodies rather than from the magnetometer: an error here is not a degraded reading, it is
 * a confident and wrong one.
 *
 * The tolerances are what the series is worth, not what the feature needs. The azimuth of both
 * bodies lands within a fiftieth of a degree; the moon's altitude within a tenth, since the parallax
 * correction uses the mean distance rather than the computed one.
 */
class CelestialPositionTest {

    private fun delta(a: Double, b: Double): Double = abs(((a - b + 180).unwindAngle()) - 180)

    @Test
    fun `the moon agrees with the published ephemeris`() {
        fun check(
            name: String,
            at: Coordinates,
            utc: String,
            azimuth: Double,
            altitude: Double,
            illumination: Double,
        ) {
            val moon = MoonPosition.at(at, Instant.parse(utc))
            assertTrue(
                delta(moon.azimuth, azimuth) < 0.05,
                "$name azimuth: ${moon.azimuth} against JPL's $azimuth",
            )
            assertTrue(
                abs(moon.altitude - altitude) < 0.15,
                "$name altitude: ${moon.altitude} against JPL's $altitude",
            )
            assertTrue(
                abs(moon.illumination - illumination) < 0.02,
                "$name illumination: ${moon.illumination} against JPL's $illumination",
            )
        }

        check(
            "Riyadh", Coordinates(24.7136, 46.6753), "2026-09-14T21:00:00Z",
            azimuth = 272.208227, altitude = -54.817094, illumination = 0.1488,
        )
        check(
            "Riyadh", Coordinates(24.7136, 46.6753), "2026-03-03T20:00:00Z",
            azimuth = 133.740143, altitude = 61.480571, illumination = 0.9983,
        )
        check(
            "London", Coordinates(51.5074, -0.1278), "2026-06-21T23:00:00Z",
            azimuth = 256.484013, altitude = 6.176825, illumination = 0.5039,
        )
        check(
            "Jakarta", Coordinates(-6.18234, 106.84287), "2026-01-15T14:00:00Z",
            azimuth = 188.966747, altitude = -55.843731, illumination = 0.0998,
        )
        check(
            "Sydney", Coordinates(-33.8688, 151.2093), "2026-11-05T11:00:00Z",
            azimuth = 173.543704, altitude = -54.892926, illumination = 0.1485,
        )
        check(
            "Anchorage", Coordinates(61.2181, -149.9003), "2026-12-25T06:00:00Z",
            azimuth = 87.259255, altitude = 25.821996, illumination = 0.9765,
        )
    }

    @Test
    fun `the sun agrees with the published ephemeris`() {
        fun check(name: String, at: Coordinates, utc: String, azimuth: Double, altitude: Double) {
            val position = SolarPosition.at(at, Instant.parse(utc))
            assertTrue(
                delta(position.azimuth, azimuth) < 0.05,
                "$name azimuth: ${position.azimuth} against JPL's $azimuth",
            )
            assertTrue(
                abs(position.altitude - altitude) < 0.05,
                "$name altitude: ${position.altitude} against JPL's $altitude",
            )
        }

        check(
            "Riyadh", Coordinates(24.7136, 46.6753), "2026-09-14T09:00:00Z",
            azimuth = 187.552198, altitude = 68.459538,
        )
        check(
            "Riyadh", Coordinates(24.7136, 46.6753), "2026-03-21T12:00:00Z",
            azimuth = 247.616737, altitude = 40.247215,
        )
        check(
            "London", Coordinates(51.5074, -0.1278), "2026-06-21T11:00:00Z",
            azimuth = 150.978447, altitude = 59.472398,
        )
        check(
            "Jakarta", Coordinates(-6.18234, 106.84287), "2026-01-15T03:00:00Z",
            azimuth = 119.895988, altitude = 56.933129,
        )
        check(
            "Sydney", Coordinates(-33.8688, 151.2093), "2026-11-05T01:00:00Z",
            azimuth = 27.94356, altitude = 69.774083,
        )
        check(
            "Anchorage", Coordinates(61.2181, -149.9003), "2026-06-25T20:00:00Z",
            azimuth = 137.040533, altitude = 46.727694,
        )
    }

    @Test
    fun `the moon is reported dark at conjunction and full at opposition`() {
        // A new moon and a full moon a fortnight apart, over Riyadh.
        val riyadh = Coordinates(24.7136, 46.6753)
        val newMoon = MoonPosition.at(riyadh, Instant.parse("2026-03-19T01:00:00Z"))
        val fullMoon = MoonPosition.at(riyadh, Instant.parse("2026-03-03T20:00:00Z"))
        assertTrue(newMoon.illumination < 0.05, "new moon read as ${newMoon.illumination}")
        assertTrue(fullMoon.illumination > 0.95, "full moon read as ${fullMoon.illumination}")
    }

    @Test
    fun `every azimuth is a bearing and every altitude is an angle`() {
        val riyadh = Coordinates(24.7136, 46.6753)
        var moment = Instant.parse("2026-01-01T00:00:00Z")
        repeat(400) {
            val moon = MoonPosition.at(riyadh, moment)
            val sun = SolarPosition.at(riyadh, moment)
            assertTrue(moon.azimuth >= 0 && moon.azimuth < 360, "moon azimuth ${moon.azimuth}")
            assertTrue(sun.azimuth >= 0 && sun.azimuth < 360, "sun azimuth ${sun.azimuth}")
            assertTrue(abs(moon.altitude) <= 91, "moon altitude ${moon.altitude}")
            assertTrue(moon.illumination in 0.0..1.0, "illumination ${moon.illumination}")
            moment = moment.plusSeconds(53_000)
        }
    }
}
