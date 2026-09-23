package com.athkar.domain

import com.athkar.core.prayer.CalculationMethod
import com.athkar.core.prayer.Coordinates
import com.athkar.core.prayer.PrayerTimes
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A reminder outside its window tells the user to read the morning adhkar after sunrise, or the
 * evening ones after Maghrib. These pin the time inside the window, in the middle of it, across the
 * seasons where the window is shortest and longest.
 */
class DailyAdhkarTest {

    private val mecca = Coordinates(21.4225, 39.8262)
    private val london = Coordinates(51.5074, -0.1278)

    private fun times(at: Coordinates, date: LocalDate) =
        PrayerTimes.calculate(at, date, CalculationMethod.UMM_AL_QURA.parameters())

    @Test
    fun `the morning reminder falls between Fajr and sunrise, the evening between Asr and Maghrib`() {
        for (place in listOf(mecca, london)) {
            for (month in 1..12) {
                val t = times(place, LocalDate.of(2026, month, 15))
                val morning = DailyAdhkar.MORNING.remindAt(t)
                val evening = DailyAdhkar.EVENING.remindAt(t)
                assertTrue(morning.isAfter(t.fajr) && morning.isBefore(t.sunrise), "morning in $month at $place")
                assertTrue(evening.isAfter(t.asr) && evening.isBefore(t.maghrib), "evening in $month at $place")
            }
        }
    }

    @Test
    fun `each reminder sits in the middle of its window`() {
        // Raleigh on the day the core tests pin: Fajr 4:42, sunrise 6:08 under ISNA.
        val t = PrayerTimes.calculate(
            Coordinates(35.7750, -78.6336),
            LocalDate.of(2015, 7, 12),
            CalculationMethod.NORTH_AMERICA.parameters(),
        )
        val zone = ZoneId.of("America/New_York")
        assertEquals(
            LocalTime.of(5, 25),
            DailyAdhkar.MORNING.remindAt(t).atZone(zone).toLocalTime().withSecond(0).withNano(0),
        )
    }
}
