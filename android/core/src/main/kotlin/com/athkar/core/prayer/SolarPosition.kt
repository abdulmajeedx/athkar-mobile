package com.athkar.core.prayer

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Where the sun is, seen from a point on the ground. */
data class SolarPosition(
    /** Degrees clockwise from true north, `[0, 360)`. */
    val azimuth: Double,
    /** Degrees above the horizon; negative when the sun has set. */
    val altitude: Double,
) {
    val isAboveHorizon: Boolean get() = altitude > 0

    companion object {

        /** The sun's apparent position at [moment], seen from [coordinates]. */
        fun at(coordinates: Coordinates, moment: Instant): SolarPosition {
            val utc = moment.atOffset(ZoneOffset.UTC)
            val hours = utc.hour + utc.minute / 60.0 + utc.second / 3600.0
            val julianDay = Astronomical.julianDay(
                year = utc.year, month = utc.monthValue, day = utc.dayOfMonth, hours = hours,
            )
            val solar = SolarCoordinates(julianDay)

            // Local hour angle: how far west of the observer's meridian the sun has travelled.
            val hourAngle = (solar.apparentSiderealTime + coordinates.longitude - solar.rightAscension)
                .closestAngle()

            val phi = coordinates.latitude.degToRad()
            val delta = solar.declination.degToRad()
            val h = hourAngle.degToRad()

            // Measured from south and positive westward, which is the convention the formula falls
            // out of; turned to the compass convention on the way out.
            val fromSouth = atan2(sin(h), cos(h) * sin(phi) - tan(delta) * cos(phi)).radToDeg()

            return SolarPosition(
                azimuth = (fromSouth + 180.0).unwindAngle(),
                altitude = Astronomical.altitudeOfCelestialBody(
                    observerLatitude = coordinates.latitude,
                    declination = solar.declination,
                    localHourAngle = hourAngle,
                ),
            )
        }
    }
}

/**
 * The moments when the sun stands in the qibla direction, or exactly opposite it.
 *
 * This is the method that does not depend on a magnetometer at all, and it is the accurate one. A
 * phone's compass can be wrong by tens of degrees near anything ferrous — a car door, a radiator, a
 * magnetic case — and it will report those degrees with complete confidence. The sun cannot be
 * pulled off course by a speaker magnet.
 *
 * At [facingSun] the sun's azimuth equals the qibla bearing: face the sun and you face the Kaaba.
 * At [facingShadow] the sun is behind you, so the shadow of anything upright points along the qibla.
 * Either may be absent — the alignment can fall at night, and above the polar circles the sun may
 * not rise at all.
 */
data class QiblaBySun(
    val facingSun: Instant?,
    val facingShadow: Instant?,
)

object QiblaSunAlignment {

    /** Sampling interval for the coarse scan. The sun moves at most a degree or so per step. */
    private const val SCAN_STEP_MINUTES = 4L

    /** Bisection until the answer is stable to well under a second of arc. */
    private const val REFINEMENTS = 24

    /**
     * The alignments that fall on [date] at [coordinates], in the given zone's day.
     *
     * The whole day is scanned rather than solved analytically: the sun's azimuth is not monotonic,
     * it can pass a given bearing twice, and near the poles it can circle without crossing at all.
     * A scan finds whatever is actually there.
     */
    fun forDate(
        coordinates: Coordinates,
        date: LocalDate,
        zone: java.time.ZoneId,
    ): QiblaBySun {
        val qibla = Qibla.direction(coordinates)
        val start = date.atStartOfDay(zone).toInstant()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant()

        return QiblaBySun(
            facingSun = findCrossing(coordinates, start, end, qibla),
            facingShadow = findCrossing(coordinates, start, end, (qibla + 180.0).unwindAngle()),
        )
    }

    /**
     * The first moment in the window when the sun's azimuth crosses [targetAzimuth] while the sun is
     * up. Returns null when it never does.
     */
    private fun findCrossing(
        coordinates: Coordinates,
        start: Instant,
        end: Instant,
        targetAzimuth: Double,
    ): Instant? {
        var previous = start
        var previousDelta = signedDelta(coordinates, previous, targetAzimuth)

        var moment = start.plus(SCAN_STEP_MINUTES, ChronoUnit.MINUTES)
        while (moment.isBefore(end)) {
            val delta = signedDelta(coordinates, moment, targetAzimuth)

            // A sign change with both samples on the same side of the wrap is a real crossing; the
            // magnitude guard rejects the jump from +179 to -179, which is the azimuth passing north
            // rather than passing the target.
            if (previousDelta != 0.0 && delta != 0.0 &&
                (previousDelta < 0) != (delta < 0) &&
                abs(previousDelta) < 90 && abs(delta) < 90
            ) {
                val crossing = refine(coordinates, previous, moment, targetAzimuth)
                if (SolarPosition.at(coordinates, crossing).isAboveHorizon) return crossing
            }

            previous = moment
            previousDelta = delta
            moment = moment.plus(SCAN_STEP_MINUTES, ChronoUnit.MINUTES)
        }
        return null
    }

    private fun refine(
        coordinates: Coordinates,
        low: Instant,
        high: Instant,
        targetAzimuth: Double,
    ): Instant {
        var lower = low
        var upper = high
        repeat(REFINEMENTS) {
            val middle = lower.plusSeconds((upper.epochSecond - lower.epochSecond) / 2)
            if (middle == lower || middle == upper) return middle
            val lowerSign = signedDelta(coordinates, lower, targetAzimuth) < 0
            val middleSign = signedDelta(coordinates, middle, targetAzimuth) < 0
            if (lowerSign == middleSign) lower = middle else upper = middle
        }
        return lower
    }

    /** How far the sun is from [targetAzimuth], as a signed angle on `(-180, 180]`. */
    private fun signedDelta(coordinates: Coordinates, moment: Instant, targetAzimuth: Double): Double =
        (SolarPosition.at(coordinates, moment).azimuth - targetAzimuth).closestAngle()
}
