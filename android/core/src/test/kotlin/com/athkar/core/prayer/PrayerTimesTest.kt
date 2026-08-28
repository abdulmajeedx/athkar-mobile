package com.athkar.core.prayer

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Reference values are the published timings for each location/method, which is what a user checks
 * the app against. Self-consistency (ordering, night length) is asserted separately so a regression
 * in the solar model cannot hide behind a coincidentally-correct single time.
 */
class PrayerTimesTest {

    private fun localTime(instant: Instant, zone: String): LocalTime =
        instant.atZone(ZoneId.of(zone)).toLocalTime()

    @Test
    fun `matches published times for Raleigh under ISNA and the Hanafi madhab`() {
        val times = PrayerTimes.calculate(
            coordinates = Coordinates(35.7750, -78.6336),
            date = LocalDate.of(2015, 7, 12),
            parameters = CalculationMethod.NORTH_AMERICA.parameters().copy(madhab = Madhab.HANAFI),
        )
        val zone = "America/New_York"

        assertEquals(LocalTime.of(4, 42), localTime(times.fajr, zone))
        assertEquals(LocalTime.of(6, 8), localTime(times.sunrise, zone))
        assertEquals(LocalTime.of(13, 21), localTime(times.dhuhr, zone))
        assertEquals(LocalTime.of(18, 22), localTime(times.asr, zone))
        assertEquals(LocalTime.of(20, 32), localTime(times.maghrib, zone))
        assertEquals(LocalTime.of(21, 57), localTime(times.isha, zone))
    }

    @Test
    fun `Hanafi Asr falls after Shafi Asr`() {
        val coordinates = Coordinates(35.7750, -78.6336)
        val date = LocalDate.of(2015, 7, 12)
        val shafi = PrayerTimes.calculate(
            coordinates, date,
            CalculationMethod.NORTH_AMERICA.parameters().copy(madhab = Madhab.SHAFI),
        )
        val hanafi = PrayerTimes.calculate(
            coordinates, date,
            CalculationMethod.NORTH_AMERICA.parameters().copy(madhab = Madhab.HANAFI),
        )

        assertEquals(LocalTime.of(18, 22), localTime(hanafi.asr, "America/New_York"))
        assertTrue(hanafi.asr.isAfter(shafi.asr))
        // Only Asr moves with the madhab; the other five are fixed by the sun alone.
        assertEquals(shafi.fajr, hanafi.fajr)
        assertEquals(shafi.maghrib, hanafi.maghrib)
    }

    @Test
    fun `Umm al-Qura places Isha exactly ninety minutes after Maghrib`() {
        val times = PrayerTimes.calculate(
            coordinates = Coordinates(21.4225, 39.8262),
            date = LocalDate.of(2024, 3, 15),
            parameters = CalculationMethod.UMM_AL_QURA.parameters(),
        )
        assertEquals(Duration.ofMinutes(90), Duration.between(times.maghrib, times.isha))
    }

    @Test
    fun `times are strictly ordered through the day`() {
        val coordinates = Coordinates(24.7136, 46.6753) // Riyadh
        var date = LocalDate.of(2024, 1, 1)
        repeat(366) {
            val t = PrayerTimes.calculate(
                coordinates, date, CalculationMethod.UMM_AL_QURA.parameters(),
            )
            assertTrue(t.fajr < t.sunrise, "fajr before sunrise on $date")
            assertTrue(t.sunrise < t.dhuhr, "sunrise before dhuhr on $date")
            assertTrue(t.dhuhr < t.asr, "dhuhr before asr on $date")
            assertTrue(t.asr < t.maghrib, "asr before maghrib on $date")
            assertTrue(t.maghrib < t.isha, "maghrib before isha on $date")
            date = date.plusDays(1)
        }
    }

    @Test
    fun `high latitude twilight that never occurs is clamped instead of dropped`() {
        // Oslo in June: the sun sets, but never descends 18 degrees below the horizon, so the
        // angle-based Fajr and Isha do not exist and the night-portion rule has to supply them.
        val times = PrayerTimes.calculate(
            coordinates = Coordinates(59.9139, 10.7522),
            date = LocalDate.of(2024, 6, 21),
            parameters = CalculationMethod.MUSLIM_WORLD_LEAGUE.parameters()
                .copy(highLatitudeRule = HighLatitudeRule.TWILIGHT_ANGLE),
        )
        assertTrue(times.fajr < times.sunrise)
        assertTrue(times.isha > times.maghrib)
        assertTrue(times.isha < times.fajr.plus(Duration.ofDays(1)))
    }

    @Test
    fun `polar day is reported rather than silently wrong`() {
        assertFailsWith<PolarDayException> {
            PrayerTimes.calculate(
                coordinates = Coordinates(78.2232, 15.6267), // Longyearbyen
                date = LocalDate.of(2024, 6, 21),
                parameters = CalculationMethod.MUSLIM_WORLD_LEAGUE.parameters(),
            )
        }
    }

    @Test
    fun `current and next prayer track the clock`() {
        val date = LocalDate.of(2024, 3, 15)
        val times = PrayerTimes.calculate(
            Coordinates(21.4225, 39.8262), date, CalculationMethod.UMM_AL_QURA.parameters(),
        )

        assertEquals(null, times.currentPrayer(times.fajr.minusSeconds(1)))
        assertEquals(Prayer.FAJR, times.nextPrayer(times.fajr.minusSeconds(1)))
        assertEquals(Prayer.FAJR, times.currentPrayer(times.fajr))
        assertEquals(Prayer.DHUHR, times.currentPrayer(times.asr.minusSeconds(1)))
        assertEquals(Prayer.ASR, times.nextPrayer(times.asr.minusSeconds(1)))
        assertEquals(Prayer.ISHA, times.currentPrayer(times.isha.plusSeconds(1)))
        assertEquals(null, times.nextPrayer(times.isha.plusSeconds(1)))
    }

    @Test
    fun `sunnah times split the night into halves and thirds`() {
        val coordinates = Coordinates(21.4225, 39.8262)
        val date = LocalDate.of(2024, 3, 15)
        val params = CalculationMethod.UMM_AL_QURA.parameters()
        val today = PrayerTimes.calculate(coordinates, date, params)
        val tomorrow = PrayerTimes.calculate(coordinates, date.plusDays(1), params)

        val sunnah = today.sunnahTimes(tomorrow)
        val night = Duration.between(today.maghrib, tomorrow.fajr)

        assertEquals(today.maghrib.plusSeconds(night.seconds / 2), sunnah.middleOfTheNight)
        assertEquals(today.maghrib.plusSeconds(night.seconds * 2 / 3), sunnah.lastThirdOfTheNight)
        assertTrue(sunnah.middleOfTheNight < sunnah.lastThirdOfTheNight)
    }

    @Test
    fun `elevation brings sunrise forward and pushes sunset back`() {
        val coordinates = Coordinates(21.4225, 39.8262)
        val date = LocalDate.of(2024, 3, 15)
        val params = CalculationMethod.UMM_AL_QURA.parameters()

        val atSeaLevel = PrayerTimes.calculate(coordinates, date, params, elevationMeters = 0.0)
        val onAMountain = PrayerTimes.calculate(coordinates, date, params, elevationMeters = 2000.0)

        assertTrue(onAMountain.sunrise < atSeaLevel.sunrise)
        assertTrue(onAMountain.maghrib > atSeaLevel.maghrib)
    }
}
