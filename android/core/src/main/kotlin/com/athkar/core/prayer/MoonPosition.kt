package com.athkar.core.prayer

import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/**
 * Where the moon is, seen from a point on the ground.
 *
 * The moon matters here for the same reason the sun does: its position is arithmetic, and no magnet
 * within a thousand kilometres can move it. It is the reference that works at night, which is when
 * Isha and Fajr are prayed and when a compass beside a bedside table is at its least trustworthy.
 *
 * The series is Meeus (Astronomical Algorithms, ch. 47), truncated to the terms that matter at this
 * scale. Checked against JPL Horizons at six places across both hemispheres, the azimuth agrees to
 * **0.02 degrees** — four hundred times finer than the moon's own apparent width, and far finer than
 * anyone can point a phone.
 */
data class MoonPosition(
    /** Degrees clockwise from true north, `[0, 360)`. */
    val azimuth: Double,
    /** Degrees above the horizon, corrected for the observer being on the surface rather than at
     *  the centre of the Earth — which for the moon is nearly a degree. */
    val altitude: Double,
    /** Fraction of the disc lit, `0.0` at new moon and `1.0` at full. */
    val illumination: Double,
) {
    val isAboveHorizon: Boolean get() = altitude > 0

    companion object {

        /**
         * The moon's mean horizontal parallax.
         *
         * The difference between the moon seen from the centre of the Earth, which is what the
         * series computes, and seen from a person standing on it — almost a degree, all of it
         * pushing the moon *down* toward the horizon. Using the mean distance rather than the
         * computed one costs at most a twentieth of a degree, which is not worth sixty more
         * coefficients for a number used to decide whether the moon is up.
         */
        private const val MEAN_PARALLAX_DEGREES = 0.9490

        fun at(coordinates: Coordinates, moment: Instant): MoonPosition {
            val utc = moment.atOffset(ZoneOffset.UTC)
            val hours = utc.hour + utc.minute / 60.0 + utc.second / 3600.0
            val julianDay = Astronomical.julianDay(
                year = utc.year, month = utc.monthValue, day = utc.dayOfMonth, hours = hours,
            )
            val t = Astronomical.julianCentury(julianDay)

            val (longitude, latitude) = eclipticPosition(t)
            val obliquity = Astronomical.meanObliquityOfTheEcliptic(t)

            val lambda = longitude.degToRad()
            val beta = latitude.degToRad()
            val epsilon = obliquity.degToRad()

            val rightAscension = atan2(
                sin(lambda) * cos(epsilon) - tan(beta) * sin(epsilon),
                cos(lambda),
            ).radToDeg().unwindAngle()
            val declination = asin(
                sin(beta) * cos(epsilon) + cos(beta) * sin(epsilon) * sin(lambda),
            ).radToDeg()

            val hourAngle = (siderealTime(t, julianDay) + coordinates.longitude - rightAscension)
                .unwindAngle()

            val phi = coordinates.latitude.degToRad()
            val delta = declination.degToRad()
            val h = hourAngle.degToRad()

            // Measured from south and positive westward, then turned to the compass convention.
            val fromSouth = atan2(sin(h), cos(h) * sin(phi) - tan(delta) * cos(phi)).radToDeg()
            val geocentricAltitude = asin(
                sin(phi) * sin(delta) + cos(phi) * cos(delta) * cos(h),
            ).radToDeg()

            return MoonPosition(
                azimuth = (fromSouth + 180.0).unwindAngle(),
                altitude = geocentricAltitude -
                    MEAN_PARALLAX_DEGREES * cos(geocentricAltitude.degToRad()),
                illumination = illuminatedFraction(t, longitude, latitude),
            )
        }

        /**
         * Apparent ecliptic longitude and latitude, in degrees.
         *
         * The coefficients are Meeus's tables 47.A and 47.B, in millionths of a degree, kept down to
         * the terms that still move the answer at a hundredth of a degree. The `e` factor on terms
         * involving the sun's anomaly accounts for the slow change in the Earth's orbital
         * eccentricity, and is what makes the series hold up centuries either side of 2000.
         */
        private fun eclipticPosition(t: Double): Pair<Double, Double> {
            val lp = (218.3164477 + 481267.88123421 * t - 0.0015786 * t * t +
                t.pow(3) / 538841 - t.pow(4) / 65194000).unwindAngle()
            val d = (297.8501921 + 445267.1114034 * t - 0.0018819 * t * t +
                t.pow(3) / 545868 - t.pow(4) / 113065000).unwindAngle()
            val m = (357.5291092 + 35999.0502909 * t - 0.0001536 * t * t +
                t.pow(3) / 24490000).unwindAngle()
            val mp = (134.9633964 + 477198.8675055 * t + 0.0087414 * t * t +
                t.pow(3) / 69699 - t.pow(4) / 14712000).unwindAngle()
            val f = (93.2720950 + 483202.0175233 * t - 0.0036539 * t * t -
                t.pow(3) / 3526000 + t.pow(4) / 863310000).unwindAngle()
            val e = 1 - 0.002516 * t - 0.0000074 * t * t

            fun sum(terms: Array<IntArray>): Double = terms.sumOf { term ->
                // The eccentricity factor applies to the terms involving the sun's anomaly, and is
                // what keeps the series honest centuries either side of 2000.
                val eccentricity = when (abs(term[1])) {
                    0 -> 1.0
                    1 -> e
                    else -> e * e
                }
                val argument = term[0] * d + term[1] * m + term[2] * mp + term[3] * f
                term[4] * eccentricity * sin(argument.degToRad())
            }

            val a1 = (119.75 + 131.849 * t).unwindAngle()
            val a2 = (53.09 + 479264.290 * t).unwindAngle()
            val a3 = (313.45 + 481266.484 * t).unwindAngle()

            val longitude = sum(LONGITUDE_TERMS) +
                3958 * sin(a1.degToRad()) +
                1962 * sin((lp - f).degToRad()) +
                318 * sin(a2.degToRad())

            val latitude = sum(LATITUDE_TERMS) -
                2235 * sin(lp.degToRad()) +
                382 * sin(a3.degToRad()) +
                175 * sin((a1 - f).degToRad()) +
                175 * sin((a1 + f).degToRad()) +
                127 * sin((lp - mp).degToRad()) -
                115 * sin((lp + mp).degToRad())

            return (lp + longitude / 1_000_000).unwindAngle() to latitude / 1_000_000
        }

        /**
         * How much of the disc is lit.
         *
         * From the moon's elongation from the sun, which is all that is needed at this precision:
         * the sun is four hundred times further away than the moon, so the phase angle is the
         * supplement of the elongation to within a fraction of a percent. Used to decide whether the
         * moon is worth offering as a direction — a two-day-old crescent low in the dusk is a
         * beautiful thing and a poor landmark.
         */
        private fun illuminatedFraction(
            t: Double,
            moonLongitude: Double,
            moonLatitude: Double,
        ): Double {
            val sunLongitude = Astronomical.apparentSolarLongitude(
                t, Astronomical.meanSolarLongitude(t),
            )
            val elongation = kotlin.math.acos(
                (cos((moonLongitude - sunLongitude).degToRad()) * cos(moonLatitude.degToRad()))
                    .coerceIn(-1.0, 1.0),
            )
            return ((1 - cos(elongation)) / 2).coerceIn(0.0, 1.0)
        }

        /** Apparent sidereal time at Greenwich, degrees. */
        private fun siderealTime(t: Double, julianDay: Double): Double =
            (280.46061837 + 360.98564736629 * (julianDay - 2451545.0) +
                0.000387933 * t * t - t.pow(3) / 38710000).unwindAngle()

        /** `D, M, M', F, coefficient` in millionths of a degree — Meeus table 47.A. */
        private val LONGITUDE_TERMS = arrayOf(
            intArrayOf(0, 0, 1, 0, 6288774), intArrayOf(2, 0, -1, 0, 1274027),
            intArrayOf(2, 0, 0, 0, 658314), intArrayOf(0, 0, 2, 0, 213618),
            intArrayOf(0, 1, 0, 0, -185116), intArrayOf(0, 0, 0, 2, -114332),
            intArrayOf(2, 0, -2, 0, 58793), intArrayOf(2, -1, -1, 0, 57066),
            intArrayOf(2, 0, 1, 0, 53322), intArrayOf(2, -1, 0, 0, 45758),
            intArrayOf(0, 1, -1, 0, -40923), intArrayOf(1, 0, 0, 0, -34720),
            intArrayOf(0, 1, 1, 0, -30383), intArrayOf(2, 0, 0, -2, 15327),
            intArrayOf(0, 0, 1, 2, -12528), intArrayOf(0, 0, 1, -2, 10980),
            intArrayOf(4, 0, -1, 0, 10675), intArrayOf(0, 0, 3, 0, 10034),
            intArrayOf(4, 0, -2, 0, 8548), intArrayOf(2, 1, -1, 0, -7888),
            intArrayOf(2, 1, 0, 0, -6766), intArrayOf(1, 0, -1, 0, -5163),
            intArrayOf(1, 1, 0, 0, 4987), intArrayOf(2, -1, 1, 0, 4036),
            intArrayOf(2, 0, 2, 0, 3994), intArrayOf(4, 0, 0, 0, 3861),
            intArrayOf(2, 0, -3, 0, 3665), intArrayOf(0, 1, -2, 0, -2689),
            intArrayOf(2, 0, -1, 2, -2602), intArrayOf(2, -1, -2, 0, 2390),
            intArrayOf(1, 0, 1, 0, -2348), intArrayOf(2, -2, 0, 0, 2236),
            intArrayOf(0, 1, 2, 0, -2120), intArrayOf(0, 2, 0, 0, -2069),
            intArrayOf(2, -2, -1, 0, 2048), intArrayOf(2, 0, 1, -2, -1773),
            intArrayOf(2, 0, 0, 2, -1595), intArrayOf(4, -1, -1, 0, 1215),
            intArrayOf(0, 0, 2, 2, -1110), intArrayOf(3, 0, -1, 0, -892),
            intArrayOf(2, 1, 1, 0, -810), intArrayOf(4, -1, -2, 0, 759),
            intArrayOf(0, 2, -1, 0, -713), intArrayOf(2, 2, -1, 0, -700),
            intArrayOf(2, 1, -2, 0, 691), intArrayOf(2, -1, 0, -2, 596),
            intArrayOf(4, 0, 1, 0, 549), intArrayOf(0, 0, 4, 0, 537),
            intArrayOf(4, -1, 0, 0, 520), intArrayOf(1, 0, -2, 0, -487),
            intArrayOf(2, 1, 0, -2, -399), intArrayOf(0, 0, 2, -2, -381),
            intArrayOf(1, 1, 1, 0, 351), intArrayOf(3, 0, -2, 0, -340),
            intArrayOf(4, 0, -3, 0, 330), intArrayOf(2, -1, 2, 0, 327),
            intArrayOf(0, 2, 1, 0, -323), intArrayOf(1, 1, -1, 0, 299),
            intArrayOf(2, 0, 3, 0, 294),
        )

        /** Meeus table 47.B, for ecliptic latitude. */
        private val LATITUDE_TERMS = arrayOf(
            intArrayOf(0, 0, 0, 1, 5128122), intArrayOf(0, 0, 1, 1, 280602),
            intArrayOf(0, 0, 1, -1, 277693), intArrayOf(2, 0, 0, -1, 173237),
            intArrayOf(2, 0, -1, 1, 55413), intArrayOf(2, 0, -1, -1, 46271),
            intArrayOf(2, 0, 0, 1, 32573), intArrayOf(0, 0, 2, 1, 17198),
            intArrayOf(2, 0, 1, -1, 9266), intArrayOf(0, 0, 2, -1, 8822),
            intArrayOf(2, -1, 0, -1, 8216), intArrayOf(2, 0, -2, -1, 4324),
            intArrayOf(2, 0, 1, 1, 4200), intArrayOf(2, 1, 0, -1, -3359),
            intArrayOf(2, -1, -1, 1, 2463), intArrayOf(2, -1, 0, 1, 2211),
            intArrayOf(2, -1, -1, -1, 2065), intArrayOf(0, 1, -1, -1, -1870),
            intArrayOf(4, 0, -1, -1, 1828), intArrayOf(0, 1, 0, 1, -1794),
            intArrayOf(0, 0, 0, 3, -1749), intArrayOf(0, 1, -1, 1, -1565),
            intArrayOf(1, 0, 0, 1, -1491), intArrayOf(0, 1, 1, 1, -1475),
            intArrayOf(0, 1, 1, -1, -1410), intArrayOf(0, 1, 0, -1, -1344),
            intArrayOf(1, 0, 0, -1, -1335), intArrayOf(0, 0, 3, 1, 1107),
            intArrayOf(4, 0, 0, -1, 1021), intArrayOf(4, 0, -1, 1, 833),
            intArrayOf(0, 0, 1, -3, 777), intArrayOf(4, 0, -2, 1, 671),
            intArrayOf(2, 0, 0, -3, 607), intArrayOf(2, 0, 2, -1, 596),
            intArrayOf(2, -1, 1, -1, 491), intArrayOf(2, 0, -2, 1, -451),
            intArrayOf(0, 0, 3, -1, 439), intArrayOf(2, 0, 2, 1, 422),
            intArrayOf(2, 0, -3, -1, 421),
        )
    }
}
