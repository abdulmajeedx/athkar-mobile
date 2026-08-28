package com.athkar.feature.prayertimes

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.chrono.HijrahChronology
import java.time.chrono.HijrahDate
import java.time.temporal.ChronoField

/**
 * Arabic date and time formatting done explicitly rather than through the platform locale, so the
 * output is identical whatever language the device is set to — the app's UI is Arabic regardless.
 */
object Formatting {

    private val hijriMonths = arrayOf(
        "محرّم", "صفر", "ربيع الأول", "ربيع الآخر", "جمادى الأولى", "جمادى الآخرة",
        "رجب", "شعبان", "رمضان", "شوّال", "ذو القعدة", "ذو الحجة",
    )

    private val gregorianMonths = arrayOf(
        "يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو",
        "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر",
    )

    private val weekdays = arrayOf(
        "الاثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت", "الأحد",
    )

    /** Clock time as `h:mm ص/م`. */
    fun time(instant: Instant, zone: ZoneId): String {
        val local = instant.atZone(zone)
        val hour24 = local.hour
        val hour12 = when (hour24 % 12) {
            0 -> 12
            else -> hour24 % 12
        }
        val suffix = if (hour24 < 12) "ص" else "م"
        return "%d:%02d %s".format(hour12, local.minute, suffix)
    }

    /** A countdown as `h:mm:ss`, or `mm:ss` under an hour. */
    fun countdown(duration: Duration): String {
        val total = duration.seconds.coerceAtLeast(0)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }

    /** Coarse remaining time for a subtitle, e.g. `بعد ساعتين و15 دقيقة`. */
    fun humanRemaining(duration: Duration): String {
        val total = duration.seconds.coerceAtLeast(0)
        val hours = (total / 3600).toInt()
        val minutes = ((total % 3600) / 60).toInt()
        return when {
            hours == 0 && minutes == 0 -> "الآن"
            hours == 0 -> "بعد $minutes ${pluralMinutes(minutes)}"
            minutes == 0 -> "بعد ${countNoun(hours, "ساعة", "ساعتين", "ساعات")}"
            else -> "بعد ${countNoun(hours, "ساعة", "ساعتين", "ساعات")} و$minutes ${pluralMinutes(minutes)}"
        }
    }

    private fun pluralMinutes(minutes: Int): String = if (minutes in 3..10) "دقائق" else "دقيقة"

    private fun countNoun(count: Int, singular: String, dual: String, plural: String): String = when {
        count == 1 -> singular
        count == 2 -> dual
        count in 3..10 -> "$count $plural"
        else -> "$count $singular"
    }

    /** `الجمعة، 5 رمضان 1446 هـ`. */
    fun hijriDate(date: LocalDate): String {
        val hijri = HijrahChronology.INSTANCE.date(date) as HijrahDate
        val day = hijri.get(ChronoField.DAY_OF_MONTH)
        val month = hijriMonths[hijri.get(ChronoField.MONTH_OF_YEAR) - 1]
        val year = hijri.get(ChronoField.YEAR)
        return "${weekdays[date.dayOfWeek.value - 1]}، $day $month $year هـ"
    }

    /** `28 أغسطس 2026 م`. */
    fun gregorianDate(date: LocalDate): String =
        "${date.dayOfMonth} ${gregorianMonths[date.monthValue - 1]} ${date.year} م"

    /** Distance in whole kilometres, with a thousands separator. */
    fun distanceKm(km: Double): String = "%,d كم".format(km.toLong())

    /** A compass bearing to one decimal place. */
    fun bearing(degrees: Double): String = "%.1f°".format(degrees)
}
