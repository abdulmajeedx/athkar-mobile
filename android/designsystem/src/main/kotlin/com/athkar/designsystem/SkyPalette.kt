package com.athkar.designsystem

import androidx.compose.ui.graphics.Color

/**
 * The colours of the sky at each prayer.
 *
 * A prayer-times app whose surfaces stay one fixed colour all day is telling the user nothing. These
 * are the hours the sky itself is named after, so the app takes its colour from the one the user is
 * standing in: indigo before dawn, amber at sunrise, a high clear blue at noon, gold through the
 * afternoon, the orange-to-violet collapse at sunset, and night after that.
 *
 * The names are the prayers rather than clock hours, because that is what the app already knows and
 * what the user is looking at the screen to find out.
 */
enum class SkyPhase(
    val top: Color,
    val bottom: Color,
    /** Accent that stays legible on this sky — the countdown, the next prayer, the qibla marker. */
    val accent: Color,
    /** True when the sky is dark enough that white text on it is the only readable choice. */
    val isDark: Boolean,
    /**
     * True when the sun is down.
     *
     * Distinct from [isDark], which is about text on *this* surface — every one of these gradients
     * is deep enough to need white — where this is about the world outside the phone, and decides
     * whether the rest of the app is a light page or a dark one.
     */
    val isNight: Boolean,
    /** What to call this hour when the user is choosing a theme and wants to see what they get. */
    val arabicName: String,
) {
    /** Before dawn: the deep blue that has not started to warm. */
    NIGHT_END(
        Color(0xFF141A3A), Color(0xFF0B0F26), Color(0xFFB8C4FF),
        isDark = true, isNight = true, arabicName = "السَّحَر",
    ),

    /** Fajr: indigo giving way at the horizon. */
    DAWN(
        Color(0xFF2A2A63), Color(0xFF6B3F6B), Color(0xFFFFC9A3),
        isDark = true, isNight = true, arabicName = "الفجر",
    ),

    /** Sunrise: the amber hour. */
    SUNRISE(
        Color(0xFFB5602F), Color(0xFF7A3B2E), Color(0xFFFFE0A3),
        isDark = true, isNight = false, arabicName = "الشروق",
    ),

    /** Dhuhr: high sun, the clearest blue of the day. */
    NOON(
        Color(0xFF1D6FA8), Color(0xFF124A73), Color(0xFFFFE9A8),
        isDark = true, isNight = false, arabicName = "الظهر",
    ),

    /** Asr: the light has turned and gone warm. */
    AFTERNOON(
        Color(0xFF1F6B63), Color(0xFF124A4A), Color(0xFFFFD98A),
        isDark = true, isNight = false, arabicName = "العصر",
    ),

    /** Maghrib: orange collapsing into violet. */
    SUNSET(
        Color(0xFF8A3A2E), Color(0xFF3E2350), Color(0xFFFFCF8F),
        isDark = true, isNight = true, arabicName = "المغرب",
    ),

    /** Isha: full night. */
    NIGHT(
        Color(0xFF10203A), Color(0xFF070C1A), Color(0xFFE5C158),
        isDark = true, isNight = true, arabicName = "العشاء",
    ),

    ;

    companion object {
        /**
         * The sky for a prayer that has begun. Sunrise is its own phase rather than part of Fajr:
         * it is the moment the colour actually changes, and the app is showing it anyway.
         */
        fun forPrayerOrdinal(ordinal: Int?): SkyPhase = when (ordinal) {
            0 -> DAWN        // Fajr
            1 -> SUNRISE
            2 -> NOON        // Dhuhr
            3 -> AFTERNOON   // Asr
            4 -> SUNSET      // Maghrib
            5 -> NIGHT       // Isha
            else -> NIGHT_END // before Fajr
        }

        /**
         * The sky by the clock, for before a place is known.
         *
         * A rough stand-in and admittedly so — real prayer times move by hours across a year and a
         * continent. It exists because the alternative on first launch, before the user has chosen
         * a city, is a single fixed colour, and an app that is midnight-blue at noon is more wrong
         * than one that is an hour early into Asr.
         */
        fun forHour(hour: Int): SkyPhase = when (hour) {
            in 0..3 -> NIGHT
            4 -> NIGHT_END
            5 -> DAWN
            in 6..7 -> SUNRISE
            in 8..14 -> NOON
            in 15..17 -> AFTERNOON
            in 18..19 -> SUNSET
            else -> NIGHT
        }
    }
}
