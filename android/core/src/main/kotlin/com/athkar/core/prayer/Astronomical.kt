package com.athkar.core.prayer

import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Solar position from Jean Meeus, *Astronomical Algorithms* (2nd ed.), chapters 12, 22, 25 and 15.
 *
 * Accuracy is what separates a prayer-time app from a rough estimate, so this is the full apparent
 * position — equation of the centre, nutation in longitude and obliquity, and the apparent sidereal
 * time — rather than the low-precision series. Rise/set/twilight instants are then refined by
 * interpolating the sun's coordinates across the previous, current and next day (Meeus ch. 15), which
 * removes the error that a single-day model accumulates near the solstices and at high latitude.
 */
internal object Astronomical {

    /** Julian Day for a Gregorian calendar date; `hours` is UT expressed as a fraction of a day. */
    fun julianDay(year: Int, month: Int, day: Int, hours: Double = 0.0): Double {
        val y = if (month > 2) year else year - 1
        val m = if (month > 2) month else month + 12
        val d = day + (hours / 24.0)
        val a = y / 100
        val b = 2 - a + (a / 4)
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + d + b - 1524.5
    }

    /** Julian centuries since J2000.0 — the time argument of every series below. */
    fun julianCentury(julianDay: Double): Double = (julianDay - 2451545.0) / 36525.0

    /** Geometric mean longitude of the sun, degrees (Meeus 25.2). */
    fun meanSolarLongitude(t: Double): Double =
        (280.4664567 + 36000.76983 * t + 0.0003032 * t * t).unwindAngle()

    /** Geometric mean longitude of the moon, degrees — only needed for the nutation terms. */
    fun meanLunarLongitude(t: Double): Double = (218.3165 + 481267.8813 * t).unwindAngle()

    /** Longitude of the ascending lunar node, degrees (Meeus 47.7). */
    fun ascendingLunarNodeLongitude(t: Double): Double =
        (125.04452 - 1934.136261 * t + 0.0020708 * t * t + (t * t * t) / 450000.0).unwindAngle()

    /** Mean anomaly of the sun, degrees (Meeus 25.3). */
    fun meanSolarAnomaly(t: Double): Double =
        (357.52911 + 35999.05029 * t - 0.0001537 * t * t).unwindAngle()

    /** Equation of the centre, degrees (Meeus p. 164). */
    fun solarEquationOfTheCenter(t: Double, meanAnomaly: Double): Double {
        val m = meanAnomaly.degToRad()
        return sin(m) * (1.914602 - 0.004817 * t - 0.000014 * t * t) +
            sin(2 * m) * (0.019993 - 0.000101 * t) +
            sin(3 * m) * 0.000289
    }

    /** Apparent longitude of the sun, degrees — true longitude corrected for aberration and nutation. */
    fun apparentSolarLongitude(t: Double, meanLongitude: Double): Double {
        val trueLongitude = meanLongitude + solarEquationOfTheCenter(t, meanSolarAnomaly(t))
        val omega = (125.04 - 1934.136 * t).degToRad()
        return (trueLongitude - 0.00569 - 0.00478 * sin(omega)).unwindAngle()
    }

    /** Mean obliquity of the ecliptic, degrees (Meeus 22.2). */
    fun meanObliquityOfTheEcliptic(t: Double): Double =
        23.439291 - 0.013004167 * t - 0.0000001639 * t * t + 0.0000005036 * t * t * t

    /** Obliquity corrected for the leading nutation term, degrees (Meeus p. 165). */
    fun apparentObliquityOfTheEcliptic(t: Double, meanObliquity: Double): Double =
        meanObliquity + 0.00256 * cos((125.04 - 1934.136 * t).degToRad())

    /** Mean sidereal time at Greenwich, degrees (Meeus 12.4). */
    fun meanSiderealTime(t: Double): Double {
        val jd = t * 36525.0 + 2451545.0
        val theta = 280.46061837 + 360.98564736629 * (jd - 2451545.0) +
            0.000387933 * t * t - (t * t * t) / 38710000.0
        return theta.unwindAngle()
    }

    /** Nutation in longitude, degrees — leading terms only (Meeus ch. 22). */
    fun nutationInLongitude(solarLongitude: Double, lunarLongitude: Double, ascendingNode: Double): Double {
        val l0 = solarLongitude.degToRad()
        val lp = lunarLongitude.degToRad()
        val omega = ascendingNode.degToRad()
        return (-17.2 / 3600) * sin(omega) -
            (1.32 / 3600) * sin(2 * l0) -
            (0.23 / 3600) * sin(2 * lp) +
            (0.21 / 3600) * sin(2 * omega)
    }

    /** Nutation in obliquity, degrees — leading terms only (Meeus ch. 22). */
    fun nutationInObliquity(solarLongitude: Double, lunarLongitude: Double, ascendingNode: Double): Double {
        val l0 = solarLongitude.degToRad()
        val lp = lunarLongitude.degToRad()
        val omega = ascendingNode.degToRad()
        return (9.2 / 3600) * cos(omega) +
            (0.57 / 3600) * cos(2 * l0) +
            (0.10 / 3600) * cos(2 * lp) -
            (0.09 / 3600) * cos(2 * omega)
    }

    /** Altitude of a body above the horizon, degrees, from its declination and local hour angle. */
    fun altitudeOfCelestialBody(observerLatitude: Double, declination: Double, localHourAngle: Double): Double {
        val phi = observerLatitude.degToRad()
        val delta = declination.degToRad()
        val h = localHourAngle.degToRad()
        return asin(sin(phi) * sin(delta) + cos(phi) * cos(delta) * cos(h)).radToDeg()
    }

    /** First approximation of the transit (solar noon) as a fraction of the day (Meeus 15.1). */
    fun approximateTransit(longitude: Double, siderealTime: Double, rightAscension: Double): Double {
        val westLongitude = -longitude
        return normalizeWithBound((rightAscension + westLongitude - siderealTime) / 360.0, 1.0)
    }

    /** Transit refined by interpolating right ascension across three days; returns hours UT. */
    fun correctedTransit(
        approximateTransit: Double,
        longitude: Double,
        siderealTime: Double,
        rightAscension: Double,
        previousRightAscension: Double,
        nextRightAscension: Double,
    ): Double {
        val westLongitude = -longitude
        val theta = (siderealTime + 360.985647 * approximateTransit).unwindAngle()
        val alpha = interpolateAngles(
            rightAscension, previousRightAscension, nextRightAscension, approximateTransit,
        ).unwindAngle()
        val hourAngle = (theta - westLongitude - alpha).closestAngle()
        val delta = hourAngle / -360.0
        return (approximateTransit + delta) * 24.0
    }

    /**
     * Instant, in hours UT, at which the sun reaches [angleAboveHorizon]. [afterTransit] picks the
     * afternoon/evening branch. Returns null when the sun never reaches that altitude on this day —
     * the polar-night / midnight-sun case the caller must resolve with a high-latitude rule.
     */
    fun correctedHourAngle(
        approximateTransit: Double,
        angleAboveHorizon: Double,
        coordinates: Coordinates,
        afterTransit: Boolean,
        siderealTime: Double,
        rightAscension: Double,
        previousRightAscension: Double,
        nextRightAscension: Double,
        declination: Double,
        previousDeclination: Double,
        nextDeclination: Double,
    ): Double? {
        val westLongitude = -coordinates.longitude
        val phi = coordinates.latitude.degToRad()
        val term1 = sin(angleAboveHorizon.degToRad()) - sin(phi) * sin(declination.degToRad())
        val term2 = cos(phi) * cos(declination.degToRad())
        val ratio = term1 / term2
        if (ratio > 1.0 || ratio < -1.0) return null

        val h0 = acos(ratio).radToDeg()
        val m = if (afterTransit) approximateTransit + h0 / 360.0 else approximateTransit - h0 / 360.0
        val theta = (siderealTime + 360.985647 * m).unwindAngle()
        val alpha = interpolateAngles(rightAscension, previousRightAscension, nextRightAscension, m).unwindAngle()
        val delta = interpolate(declination, previousDeclination, nextDeclination, m)
        val hourAngle = theta - westLongitude - alpha
        val altitude = altitudeOfCelestialBody(coordinates.latitude, delta, hourAngle)
        val denominator = 360.0 * cos(delta.degToRad()) * cos(phi) * sin(hourAngle.degToRad())
        val deltaM = (altitude - angleAboveHorizon) / denominator
        return (m + deltaM) * 24.0
    }

    /** Three-point interpolation (Meeus 3.3). */
    fun interpolate(y2: Double, y1: Double, y3: Double, n: Double): Double {
        val a = y2 - y1
        val b = y3 - y2
        val c = b - a
        return y2 + (n / 2) * (a + b + n * c)
    }

    /** Three-point interpolation for angles, taking the shortest way round the circle. */
    fun interpolateAngles(y2: Double, y1: Double, y3: Double, n: Double): Double {
        val a = (y2 - y1).unwindAngle()
        val b = (y3 - y2).unwindAngle()
        val c = b - a
        return y2 + (n / 2) * (a + b + n * c)
    }
}

/** Apparent solar coordinates for one Julian Day. */
internal class SolarCoordinates(julianDay: Double) {

    /** Apparent declination of the sun, degrees. */
    val declination: Double

    /** Apparent right ascension of the sun, degrees, normalised to `[0, 360)`. */
    val rightAscension: Double

    /** Apparent sidereal time at Greenwich, degrees. */
    val apparentSiderealTime: Double

    init {
        val t = Astronomical.julianCentury(julianDay)
        val meanLongitude = Astronomical.meanSolarLongitude(t)
        val meanLunarLongitude = Astronomical.meanLunarLongitude(t)
        val ascendingNode = Astronomical.ascendingLunarNodeLongitude(t)
        val apparentLongitude = Astronomical.apparentSolarLongitude(t, meanLongitude).degToRad()
        val meanSiderealTime = Astronomical.meanSiderealTime(t)
        val nutationLongitude =
            Astronomical.nutationInLongitude(meanLongitude, meanLunarLongitude, ascendingNode)
        val nutationObliquity =
            Astronomical.nutationInObliquity(meanLongitude, meanLunarLongitude, ascendingNode)
        val meanObliquity = Astronomical.meanObliquityOfTheEcliptic(t)
        val apparentObliquity =
            Astronomical.apparentObliquityOfTheEcliptic(t, meanObliquity).degToRad()

        declination = asin(sin(apparentObliquity) * sin(apparentLongitude)).radToDeg()
        rightAscension = atan2(
            cos(apparentObliquity) * sin(apparentLongitude),
            cos(apparentLongitude),
        ).radToDeg().unwindAngle()
        apparentSiderealTime = meanSiderealTime +
            (nutationLongitude * 3600 * cos((meanObliquity + nutationObliquity).degToRad())) / 3600
    }
}
