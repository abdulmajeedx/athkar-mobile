package com.athkar.domain

import com.athkar.core.prayer.PrayerTimes
import java.time.Duration
import java.time.Instant

/**
 * The two daily sets the app can remind the user to read.
 *
 * Each is anchored to the window the sunnah gives it rather than to a clock time: the morning
 * adhkar belong between Fajr and sunrise, the evening ones between Asr and Maghrib. A fixed
 * "07:00" would fall after sunrise for half the year in much of the Gulf, and before Fajr in a
 * northern summer, and a reminder that arrives outside its window is teaching the wrong thing.
 *
 * @property title the notification title.
 * @property body the line under it.
 */
enum class DailyAdhkar(val title: String, val body: String) {
    MORNING("أذكار الصباح", "حان وقت أذكار الصباح، قبل شروق الشمس"),
    EVENING("أذكار المساء", "حان وقت أذكار المساء، قبل غروب الشمس"),
    ;

    /**
     * When to remind on the day [times] describes.
     *
     * The midpoint of the window, not its opening: at the opening the user is praying, and
     * a reminder that lands during the prayer is one they swipe away unread. The midpoint also
     * scales with the season and the latitude on its own, and always leaves as long to read as it
     * took to arrive — which a fixed offset from Fajr cannot promise when the window is short.
     */
    fun remindAt(times: PrayerTimes): Instant = when (this) {
        MORNING -> midpoint(times.fajr, times.sunrise)
        EVENING -> midpoint(times.asr, times.maghrib)
    }

    private fun midpoint(start: Instant, end: Instant): Instant =
        start.plus(Duration.between(start, end).dividedBy(2))
}
