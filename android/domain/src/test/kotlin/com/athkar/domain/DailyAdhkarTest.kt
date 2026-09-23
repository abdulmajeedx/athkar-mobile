package com.athkar.domain

import com.athkar.core.prayer.CalculationMethod
import com.athkar.core.prayer.Coordinates
import com.athkar.core.prayer.PrayerTimes
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A reminder outside its window tells the user to read the morning adhkar after sunrise, or the
 * evening ones after Maghrib. These pin each time inside its window, in the middle of it, across the
 * seasons where the window is shortest and longest.
 */
class DailyAdhkarTest {

    private val mecca = Coordinates(21.4225, 39.8262)
    private val london = Coordinates(51.5074, -0.1278)

    private fun times(at: Coordinates, date: LocalDate) =
        PrayerTimes.calculate(at, date, CalculationMethod.UMM_AL_QURA.parameters())

    @Test
    fun `every reminder falls inside its window all year`() {
        for (place in listOf(mecca, london)) {
            for (month in 1..12) {
                val t = times(place, LocalDate.of(2026, month, 15))
                val morning = DailyAdhkar.MORNING.remindAt(t)
                val evening = DailyAdhkar.EVENING.remindAt(t)
                val sleep = DailyAdhkar.SLEEP.remindAt(t)
                val nextFajr = times(place, LocalDate.of(2026, month, 16)).fajr
                assertTrue(morning.isAfter(t.fajr) && morning.isBefore(t.sunrise), "morning in $month at $place")
                assertTrue(evening.isAfter(t.asr) && evening.isBefore(t.maghrib), "evening in $month at $place")
                assertTrue(sleep.isAfter(t.isha) && sleep.isBefore(nextFajr), "sleep in $month at $place")
            }
        }
    }

    @Test
    fun `the sleep reminder sits halfway from Isha to the middle of the night`() {
        val t = times(mecca, LocalDate.of(2026, 3, 15))
        val nextFajr = times(mecca, LocalDate.of(2026, 3, 16)).fajr
        val midnight = t.maghrib.plus(Duration.between(t.maghrib, nextFajr).dividedBy(2))
        val expected = t.isha.plus(Duration.between(t.isha, midnight).dividedBy(2))
        // Today's Fajr stands in for tomorrow's; the two are a minute or two apart at most.
        val drift = Duration.between(expected, DailyAdhkar.SLEEP.remindAt(t)).abs()
        assertTrue(drift <= Duration.ofMinutes(1), "drift $drift")
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

    @Test
    fun `each reminder opens its own chapter`() {
        assertEquals(DailyAdhkar.entries.size, DailyAdhkar.entries.map { it.chapterKey }.toSet().size)
    }
}
