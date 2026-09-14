package com.athkar.core.prayer

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Direction of the Kaaba as a great-circle initial bearing.
 *
 * A rhumb line — the "straight line on a flat map" people expect — is the wrong answer: it is not
 * the shortest path on a sphere, and its error grows to tens of degrees at long range. The initial
 * bearing of the great circle is the direction one actually faces.
 *
 * This is the same computation the [AlAdhan qibla API](https://aladhan.com/qibla-api) performs, and
 * `QiblaTest` checks it against AlAdhan's published values for a dozen cities: the two agree to a
 * thousandth of a degree everywhere. It is done here rather than fetched because a fetched bearing
 * would send the user's coordinates to a server, fail without a network, and arrive no more correct
 * than the one computed on the phone in a few microseconds.
 */
object Qibla {

    /** The Kaaba, Al-Masjid al-Haram, Makkah. */
    val MAKKAH = Coordinates(latitude = 21.4225241, longitude = 39.8261818)

    /** Mean Earth radius, kilometres (IUGG). */
    private const val EARTH_RADIUS_KM = 6371.0088

    /**
     * Initial great-circle bearing from [from] to the Kaaba, in degrees clockwise from true north
     * (`[0, 360)`).
     */
    fun direction(from: Coordinates): Double {
        val phi1 = from.latitude.degToRad()
        val phi2 = MAKKAH.latitude.degToRad()
        val deltaLambda = (MAKKAH.longitude - from.longitude).degToRad()

        val y = sin(deltaLambda)
        val x = cos(phi1) * tan(phi2) - sin(phi1) * cos(deltaLambda)
        return atan2(y, x).radToDeg().unwindAngle()
    }

    /** Great-circle distance from [from] to the Kaaba, in kilometres. */
    fun distanceKm(from: Coordinates): Double {
        val phi1 = from.latitude.degToRad()
        val phi2 = MAKKAH.latitude.degToRad()
        val deltaPhi = (MAKKAH.latitude - from.latitude).degToRad()
        val deltaLambda = (MAKKAH.longitude - from.longitude).degToRad()

        // Haversine: numerically stable for the short distances a rearranged cosine rule loses.
        val a = sin(deltaPhi / 2) * sin(deltaPhi / 2) +
            cos(phi1) * cos(phi2) * sin(deltaLambda / 2) * sin(deltaLambda / 2)
        return 2 * EARTH_RADIUS_KM * asin(sqrt(a).coerceAtMost(1.0))
    }
}
