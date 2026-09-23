package com.athkar.domain

import com.athkar.core.prayer.PrayerTimes
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant

/**
 * The sets the app can remind the user to read: three every day, and one on Fridays.
 *
 * Each is anchored to the window the sunnah gives it rather than to a clock time: the morning
 * adhkar belong between Fajr and sunrise, the evening ones between Asr and Maghrib. A fixed
 * "07:00" would fall after sunrise for half the year in much of the Gulf, and before Fajr in a
 * northern summer, and a reminder that arrives outside its window is teaching the wrong thing.
 *
 * @property title the notification title.
 * @property body the line under it.
 * @property chapterKey the bundled chapter the reminder opens. These are the corpus's own keys,
 *   stable across versions of the bundle, so tapping the reminder lands on the reading itself
 *   rather than on the 133-chapter index.
 */
enum class DailyAdhkar(val title: String, val body: String, val chapterKey: String) {
    MORNING("أذكار الصباح", "حان وقت أذكار الصباح، قبل شروق الشمس", "cat-27m"),
    EVENING("أذكار المساء", "حان وقت أذكار المساء، قبل غروب الشمس", "cat-27e"),
    SLEEP("أذكار النوم", "قبل أن تنام، أذكار النوم", "cat-28"),

    /**
     * Surah al-Kahf and sending prayers on the Prophet ﷺ, the two acts the sunnah ties to Friday.
     *
     * Opens the chapter on the virtue of those prayers. The surah itself is not in the bundle —
     * Hisn al-Muslim is a book of adhkar, not the mushaf — and Quran text is not something to add
     * from anywhere but a verified source, so the reminder names it and leaves the reading to the
     * user's own mushaf.
     */
    FRIDAY(
        "يوم الجمعة",
        "اقرأ سورة الكهف، وأكثر من الصلاة على النبي ﷺ",
        "cat-107",
    ),
    ;

    /**
     * When to remind on the day [times] describes, or null when this reminder does not fall on
     * that day.
     *
     * The midpoint of the window, not its opening: at the opening the user is praying, and
     * a reminder that lands during the prayer is one they swipe away unread. The midpoint also
     * scales with the season and the latitude on its own, and always leaves as long to read as it
     * took to arrive — which a fixed offset from Fajr cannot promise when the window is short.
     *
     * Sleep has no window of its own in the sunnah, only "when you lie down". It takes the one
     * between Isha and the middle of the night, which is where the night's sleep begins for most
     * people and which, like the others, follows the season instead of a clock.
     */
    fun remindAt(times: PrayerTimes): Instant? = when (this) {
        MORNING -> midpoint(times.fajr, times.sunrise)
        EVENING -> midpoint(times.asr, times.maghrib)
        SLEEP -> midpoint(times.isha, islamicMidnight(times))
        // Between sunrise and the Friday prayer: the surah is commonly read before it, and a
        // reminder after Dhuhr would arrive while the user is at the mosque or just back from it.
        FRIDAY -> if (times.date.dayOfWeek == DayOfWeek.FRIDAY) {
            midpoint(times.sunrise, times.dhuhr)
        } else {
            null
        }
    }

    private fun midpoint(start: Instant, end: Instant): Instant =
        start.plus(Duration.between(start, end).dividedBy(2))

    /**
     * Halfway from Maghrib to the next Fajr.
     *
     * Tomorrow's Fajr is taken as today's plus a day. The two differ by a minute or two at most,
     * which moves this point by half that — and computing tomorrow properly would mean a second
     * calculation that can itself fail on a polar day.
     */
    private fun islamicMidnight(times: PrayerTimes): Instant =
        midpoint(times.maghrib, times.fajr.plus(Duration.ofDays(1)))
}
