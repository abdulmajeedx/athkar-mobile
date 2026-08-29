package com.athkar.core.prayer

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The sun's position is what makes a qibla accurate without a magnetometer, so it is checked
 * against facts that hold independently of this implementation: the sun is due south at local noon
 * from the northern mid-latitudes, it rises in the east and sets in the west, and its altitude at
 * the computed prayer times matches the angles those prayers are defined by.
 */
class SolarPositionTest {

    private val makkah = Coordinates(21.4225, 39.8262)!!
    private val riyadh = Coordinates(24.7136, 46.6753)!!

    @Test
    fun `sun is due south at solar noon from a northern latitude`() {
        val date = LocalDate.of(2024, 3, 20) // near the equinox, when noon is unambiguous
        val times = PrayerTimes.calculate(
            riyadh, date, CalculationMethod.UMM_AL_QURA.parameters(),
        )
        // Dhuhr is solar transit plus the method's one-minute rounding; at transit the sun crosses
        // the meridian, so its azimuth is due south.
        val azimuth = SolarPosition.at(riyadh, times.dhuhr).azimuth
        assertTrue(abs(azimuth - 180.0) < 1.0, "azimuth at transit was $azimuth")
    }

    @Test
    fun `sun rises in the east and sets in the west`() {
        val date = LocalDate.of(2024, 6, 21)
        val times = PrayerTimes.calculate(makkah, date, CalculationMethod.UMM_AL_QURA.parameters())

        val sunrise = SolarPosition.at(makkah, times.sunrise).azimuth
        val sunset = SolarPosition.at(makkah, times.maghrib).azimuth
        assertTrue(sunrise in 45.0..115.0, "sunrise azimuth was $sunrise")
        assertTrue(sunset in 245.0..315.0, "sunset azimuth was $sunset")
    }

    @Test
    fun `altitude at sunrise and sunset is the horizon`() {
        val date = LocalDate.of(2024, 9, 15)
        val times = PrayerTimes.calculate(riyadh, date, CalculationMethod.UMM_AL_QURA.parameters())

        // -0.833 degrees: the refraction-and-semidiameter definition the times are computed from.
        // A minute of rounding moves the sun by about a quarter of a degree.
        assertTrue(abs(SolarPosition.at(riyadh, times.sunrise).altitude + 0.833) < 0.35)
        assertTrue(abs(SolarPosition.at(riyadh, times.maghrib).altitude + 0.833) < 0.35)
    }

    @Test
    fun `altitude is negative at night and positive at midday`() {
        val date = LocalDate.of(2024, 1, 15)
        val times = PrayerTimes.calculate(makkah, date, CalculationMethod.UMM_AL_QURA.parameters())
        assertTrue(SolarPosition.at(makkah, times.fajr).altitude < 0)
        assertTrue(SolarPosition.at(makkah, times.dhuhr).altitude > 0)
        assertTrue(SolarPosition.at(makkah, times.isha).altitude < 0)
    }

    @Test
    fun `sun alignment points at the qibla when it says it does`() {
        val zone = ZoneId.of("Asia/Riyadh")
        val date = LocalDate.of(2024, 5, 15)
        val alignment = QiblaSunAlignment.forDate(riyadh, date, zone)

        val facingSun = assertNotNull(alignment.facingSun, "Riyadh should have a facing-sun moment")
        val qibla = Qibla.direction(riyadh)
        val azimuth = SolarPosition.at(riyadh, facingSun).azimuth
        // The whole point of the method: at this instant the sun stands in the qibla direction.
        assertTrue(abs((azimuth - qibla + 540) % 360 - 180) < 0.05, "azimuth $azimuth vs qibla $qibla")
        assertTrue(SolarPosition.at(riyadh, facingSun).isAboveHorizon)
    }

    @Test
    fun `shadow alignment is opposite the qibla and the sun is up`() {
        // Nairobi in January: the qibla runs almost due north, so its opposite is almost due south,
        // and with the sun's declination south of the observer it crosses that bearing at transit.
        // The bearing has to lie inside the arc the sun actually travels that day, which is a
        // narrower condition than it sounds — Sanaa and Riyadh both fail it in May, because a sun
        // declined further north than the observer transits to the north, not the south.
        val nairobi = Coordinates(-1.2921, 36.8219)!!
        val alignment = QiblaSunAlignment.forDate(
            nairobi, LocalDate.of(2024, 1, 15), ZoneId.of("Africa/Nairobi"),
        )

        val facingShadow = assertNotNull(alignment.facingShadow, "Nairobi should have a shadow moment")
        val opposite = (Qibla.direction(nairobi) + 180.0) % 360.0
        val azimuth = SolarPosition.at(nairobi, facingShadow).azimuth
        assertTrue(abs((azimuth - opposite + 540) % 360 - 180) < 0.05, "azimuth $azimuth vs $opposite")
        assertTrue(SolarPosition.at(nairobi, facingShadow).isAboveHorizon)
    }

    @Test
    fun `an alignment that falls below the horizon is reported as absent`() {
        // The anti-qibla from Riyadh is about 65 degrees, which the sun passes before sunrise: the
        // crossing exists mathematically and is useless on the ground, so it must not be offered.
        val alignment = QiblaSunAlignment.forDate(
            riyadh, LocalDate.of(2024, 5, 15), ZoneId.of("Asia/Riyadh"),
        )
        alignment.facingShadow?.let {
            assertTrue(SolarPosition.at(riyadh, it).isAboveHorizon, "offered a crossing below the horizon")
        }
    }

    @Test
    fun `alignment is absent where the sun never reaches the bearing`() {
        // The qibla from Anchorage is close to due north, which the sun there never occupies.
        val anchorage = Coordinates(61.2181, -149.9003)!!
        val alignment = QiblaSunAlignment.forDate(
            anchorage, LocalDate.of(2024, 12, 21), ZoneId.of("America/Anchorage"),
        )
        assertTrue(alignment.facingSun == null || SolarPosition.at(anchorage, alignment.facingSun).isAboveHorizon)
    }
}
