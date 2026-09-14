package com.athkar.core.prayer

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reference bearings are the great-circle values for each city. They are the check that matters:
 * a rhumb-line implementation passes for Cairo and is ~40 degrees wrong for Jakarta, so the suite
 * deliberately spans both hemispheres and the far side of the globe.
 */
class QiblaTest {

    private val tolerance = 0.01

    private fun assertBearing(expected: Double, coordinates: Coordinates, name: String) {
        val actual = Qibla.direction(coordinates)
        assertTrue(
            abs(expected - actual) < tolerance,
            "$name: expected $expected, was $actual",
        )
    }

    /**
     * The same bearings, against the AlAdhan qibla API at the precision it publishes.
     *
     * AlAdhan is the reference this project is measured against, and the values below were fetched
     * from `api.aladhan.com/v1/qibla/{lat}/{lng}` and pasted here. **It is not called at runtime,
     * and adding that call would be a mistake in three ways at once:** it would put the user's
     * coordinates on someone else's server, contradicting the listing's promise that they never
     * leave the phone; it would make the qibla fail without a network, on a screen whose whole
     * value is working in a desert or a basement; and it would buy nothing, because the answer is
     * the same to a thousandth of a degree. Both compute the initial great-circle bearing, which is
     * not an approximation of anything — it is the direction.
     *
     * A thousandth of a degree is four thousand times finer than the four degrees this app calls
     * "facing the qibla", and about a metre and a half of sideways drift over the 790 kilometres
     * from Riyadh to Makkah.
     */
    @Test
    fun `bearings match the AlAdhan qibla API to a thousandth of a degree`() {
        fun againstAlAdhan(published: Double, coordinates: Coordinates, name: String) {
            val actual = Qibla.direction(coordinates)
            assertTrue(
                abs(published - actual) < 0.001,
                "$name: AlAdhan publishes $published, this computes $actual",
            )
        }

        againstAlAdhan(56.56048281743561, Coordinates(38.9072, -77.0369), "Washington DC")
        againstAlAdhan(58.48178226835465, Coordinates(40.7128, -74.0059), "New York")
        againstAlAdhan(18.843840560979134, Coordinates(37.7749, -122.4194), "San Francisco")
        againstAlAdhan(350.8830880262056, Coordinates(61.2181, -149.9003), "Anchorage")
        againstAlAdhan(277.4995893582511, Coordinates(-33.8688, 151.2093), "Sydney")
        againstAlAdhan(118.98724251452296, Coordinates(51.5074, -0.1278), "London")
        againstAlAdhan(119.16316478082387, Coordinates(48.8566, 2.3522), "Paris")
        againstAlAdhan(139.02787506254048, Coordinates(59.9139, 10.7522), "Oslo")
        againstAlAdhan(255.88161631650698, Coordinates(33.7294, 73.0931), "Islamabad")
        againstAlAdhan(293.0207310398085, Coordinates(35.6895, 139.6917), "Tokyo")
        againstAlAdhan(295.1442879900359, Coordinates(-6.18233995, 106.84287), "Jakarta")
        againstAlAdhan(243.79818967345182, Coordinates(24.7136, 46.6753), "Riyadh")
    }

    @Test
    fun `bearings match published great-circle values`() {
        assertBearing(56.560, Coordinates(38.9072, -77.0369), "Washington DC")
        assertBearing(58.481, Coordinates(40.7128, -74.0059), "New York")
        assertBearing(18.843, Coordinates(37.7749, -122.4194), "San Francisco")
        assertBearing(350.883, Coordinates(61.2181, -149.9003), "Anchorage")
        assertBearing(277.499, Coordinates(-33.8688, 151.2093), "Sydney")
        assertBearing(118.987, Coordinates(51.5074, -0.1278), "London")
        assertBearing(119.163, Coordinates(48.8566, 2.3522), "Paris")
        assertBearing(139.027, Coordinates(59.9139, 10.7522), "Oslo")
        assertBearing(255.881, Coordinates(33.7294, 73.0931), "Islamabad")
        assertBearing(293.020, Coordinates(35.6895, 139.6917), "Tokyo")
        assertBearing(295.144, Coordinates(-6.18233995, 106.84287), "Jakarta")
    }

    @Test
    fun `points due north and south of the Kaaba read as due south and north`() {
        val north = Coordinates(Qibla.MAKKAH.latitude + 10, Qibla.MAKKAH.longitude)
        val south = Coordinates(Qibla.MAKKAH.latitude - 10, Qibla.MAKKAH.longitude)

        assertEquals(180.0, Qibla.direction(north), tolerance)
        assertEquals(0.0, Qibla.direction(south), tolerance)
    }

    @Test
    fun `distance is zero at the Kaaba and matches known separations elsewhere`() {
        assertEquals(0.0, Qibla.distanceKm(Qibla.MAKKAH), 0.001)
        // Makkah to Madinah is about 340 km.
        assertEquals(339.0, Qibla.distanceKm(Coordinates(24.4672, 39.6111)), 5.0)
        // Makkah to Cairo is about 1290 km.
        assertEquals(1288.0, Qibla.distanceKm(Coordinates(30.0444, 31.2357)), 15.0)
    }

    @Test
    fun `every bearing is a valid compass heading`() {
        var latitude = -80.0
        while (latitude <= 80.0) {
            var longitude = -180.0
            while (longitude < 180.0) {
                val bearing = Qibla.direction(Coordinates(latitude, longitude))
                assertTrue(bearing >= 0.0 && bearing < 360.0, "bearing $bearing at $latitude/$longitude")
                longitude += 10.0
            }
            latitude += 10.0
        }
    }
}
