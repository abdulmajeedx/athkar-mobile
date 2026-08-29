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
) {
    /** Before dawn: the deep blue that has not started to warm. */
    NIGHT_END(Color(0xFF141A3A), Color(0xFF0B0F26), Color(0xFFB8C4FF), isDark = true),

    /** Fajr: indigo giving way at the horizon. */
    DAWN(Color(0xFF2A2A63), Color(0xFF6B3F6B), Color(0xFFFFC9A3), isDark = true),

    /** Sunrise: the amber hour. */
    SUNRISE(Color(0xFFB5602F), Color(0xFF7A3B2E), Color(0xFFFFE0A3), isDark = true),

    /** Dhuhr: high sun, the clearest blue of the day. */
    NOON(Color(0xFF1D6FA8), Color(0xFF124A73), Color(0xFFFFE9A8), isDark = true),

    /** Asr: the light has turned and gone warm. */
    AFTERNOON(Color(0xFF1F6B63), Color(0xFF124A4A), Color(0xFFFFD98A), isDark = true),

    /** Maghrib: orange collapsing into violet. */
    SUNSET(Color(0xFF8A3A2E), Color(0xFF3E2350), Color(0xFFFFCF8F), isDark = true),

    /** Isha: full night. */
    NIGHT(Color(0xFF10203A), Color(0xFF070C1A), Color(0xFFE5C158), isDark = true),

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
    }
}
