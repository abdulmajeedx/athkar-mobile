package com.athkar.designsystem

/**
 * Which palette the app wears.
 *
 * [BY_TIME] is the default and the reason the others exist as an escape from it: it follows the sky
 * the user is actually standing under, so the app is a light page through the morning and a dark one
 * after Maghrib, changing at the prayers rather than at midnight. That is right for most people and
 * wrong for anyone who reads adhkar in bed with the lights off, so the fixed choices stay.
 */
enum class AppTheme(val label: String, val description: String) {
    BY_TIME(
        "حسب وقت الصلاة",
        "يتغيّر لون التطبيق مع الوقت: للفجر لون وللظهر آخر، ويُظلم بعد المغرب.",
    ),
    LIGHT("فاتح", "صفحة فاتحة في كل الأوقات."),
    DARK("داكن", "خلفية داكنة في كل الأوقات، أريح للعين ليلًا."),
    SYSTEM("حسب النظام", "يتبع الوضع الداكن في إعدادات الجهاز."),
    ;

    companion object {
        val DEFAULT = BY_TIME

        /** An unknown name — a theme from a later version — falls back rather than crashing. */
        fun fromName(name: String?): AppTheme = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
