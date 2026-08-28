package com.athkar.core.prayer

/**
 * Which shadow length marks the start of Asr.
 *
 * @property shadowLength multiple of an object's own length, added to the shadow it casts at noon.
 */
enum class Madhab(val shadowLength: Int) {
    /** Shafi'i, Maliki, Hanbali: one shadow length. */
    SHAFI(1),

    /** Hanafi: two shadow lengths. */
    HANAFI(2),
}

/**
 * How to place Fajr and Isha where the sun never descends far enough below the horizon for the
 * defining twilight angle to occur — the "abnormal period" of high latitudes.
 */
enum class HighLatitudeRule {
    /** Night is split in half; Fajr and Isha may not be closer to midnight than that. */
    MIDDLE_OF_THE_NIGHT,

    /** Fajr no earlier than 1/7 of the night before sunrise, Isha no later than 1/7 after sunset. */
    SEVENTH_OF_THE_NIGHT,

    /** The night fraction is derived from the method's own twilight angle. Recommended default. */
    TWILIGHT_ANGLE,

    ;

    companion object {
        /**
         * The rule conventionally applied at a given latitude: the angle-based rule is the closest
         * to observation, but below roughly 48° the three barely differ.
         */
        fun recommendedFor(coordinates: Coordinates): HighLatitudeRule =
            if (kotlin.math.abs(coordinates.latitude) > 48) TWILIGHT_ANGLE else MIDDLE_OF_THE_NIGHT
    }
}

/**
 * Minute offsets applied to each computed time. Methods carry their own conventional rounding here
 * (several publish Dhuhr a minute or more after true transit); [PrayerAdjustments] is also the hook
 * for a user's manual correction against their local mosque.
 */
data class PrayerAdjustments(
    val fajr: Int = 0,
    val sunrise: Int = 0,
    val dhuhr: Int = 0,
    val asr: Int = 0,
    val maghrib: Int = 0,
    val isha: Int = 0,
) {
    operator fun plus(other: PrayerAdjustments) = PrayerAdjustments(
        fajr = fajr + other.fajr,
        sunrise = sunrise + other.sunrise,
        dhuhr = dhuhr + other.dhuhr,
        asr = asr + other.asr,
        maghrib = maghrib + other.maghrib,
        isha = isha + other.isha,
    )
}

/**
 * The full parameter set a prayer-time calculation needs.
 *
 * @property fajrAngle sun's depression below the horizon at Fajr, degrees.
 * @property ishaAngle sun's depression below the horizon at Isha, degrees. Ignored when
 *   [ishaInterval] is set.
 * @property ishaInterval fixed minutes after Maghrib, used by methods that define Isha that way
 *   (Umm al-Qura, Qatar). `0` means use [ishaAngle].
 * @property maghribAngle sun's depression at Maghrib for the methods that do not use sunset.
 */
data class CalculationParameters(
    val fajrAngle: Double,
    val ishaAngle: Double = 0.0,
    val ishaInterval: Int = 0,
    val maghribAngle: Double = 0.0,
    val madhab: Madhab = Madhab.SHAFI,
    val highLatitudeRule: HighLatitudeRule = HighLatitudeRule.MIDDLE_OF_THE_NIGHT,
    val adjustments: PrayerAdjustments = PrayerAdjustments(),
    val methodAdjustments: PrayerAdjustments = PrayerAdjustments(),
) {
    /**
     * Portion of the night after which Fajr/Isha are clamped when the twilight angle never occurs.
     * Derived from the method's own angles under [HighLatitudeRule.TWILIGHT_ANGLE].
     */
    internal fun nightPortions(): Pair<Double, Double> = when (highLatitudeRule) {
        HighLatitudeRule.MIDDLE_OF_THE_NIGHT -> 0.5 to 0.5
        HighLatitudeRule.SEVENTH_OF_THE_NIGHT -> (1.0 / 7.0) to (1.0 / 7.0)
        HighLatitudeRule.TWILIGHT_ANGLE -> (fajrAngle / 60.0) to (ishaAngle / 60.0)
    }
}

/**
 * Published parameter sets. Choosing the method your local authority uses matters more than any
 * refinement in the astronomy: the spread between methods reaches ~20 minutes at Fajr and Isha.
 *
 * @property arabicName label shown in the picker.
 */
enum class CalculationMethod(val arabicName: String) {
    /** Umm al-Qura University, Makkah — the calendar used across Saudi Arabia. */
    UMM_AL_QURA("أم القرى — مكة المكرمة"),

    /** Muslim World League. Widely used in Europe and the Far East. */
    MUSLIM_WORLD_LEAGUE("رابطة العالم الإسلامي"),

    /** Egyptian General Authority of Survey. */
    EGYPTIAN("الهيئة المصرية العامة للمساحة"),

    /** University of Islamic Sciences, Karachi. */
    KARACHI("جامعة العلوم الإسلامية — كراتشي"),

    /** Islamic Society of North America. */
    NORTH_AMERICA("الجمعية الإسلامية لأمريكا الشمالية"),

    /** Dubai — the UAE government's set. */
    DUBAI("دبي — الإمارات"),

    /** Qatar Calendar House. */
    QATAR("دار التقويم القطرية"),

    /** Kuwait. */
    KUWAIT("الكويت"),

    /** Majlis Ugama Islam Singapura. */
    SINGAPORE("سنغافورة"),

    /** Diyanet İşleri Başkanlığı, Turkey. */
    TURKEY("ديانت — تركيا"),

    /** Institute of Geophysics, University of Tehran. */
    TEHRAN("جامعة طهران"),

    ;

    /** The parameter set for this method, before user [PrayerAdjustments] are applied. */
    fun parameters(): CalculationParameters = when (this) {
        UMM_AL_QURA -> CalculationParameters(
            fajrAngle = 18.5,
            ishaInterval = 90,
        )

        MUSLIM_WORLD_LEAGUE -> CalculationParameters(
            fajrAngle = 18.0,
            ishaAngle = 17.0,
            methodAdjustments = PrayerAdjustments(dhuhr = 1),
        )

        EGYPTIAN -> CalculationParameters(
            fajrAngle = 19.5,
            ishaAngle = 17.5,
            methodAdjustments = PrayerAdjustments(dhuhr = 1),
        )

        KARACHI -> CalculationParameters(
            fajrAngle = 18.0,
            ishaAngle = 18.0,
            methodAdjustments = PrayerAdjustments(dhuhr = 1),
        )

        NORTH_AMERICA -> CalculationParameters(
            fajrAngle = 15.0,
            ishaAngle = 15.0,
            methodAdjustments = PrayerAdjustments(dhuhr = 1),
        )

        DUBAI -> CalculationParameters(
            fajrAngle = 18.2,
            ishaAngle = 18.2,
            methodAdjustments = PrayerAdjustments(sunrise = -3, dhuhr = 3, asr = 3, maghrib = 3),
        )

        QATAR -> CalculationParameters(
            fajrAngle = 18.0,
            ishaInterval = 90,
        )

        KUWAIT -> CalculationParameters(
            fajrAngle = 18.0,
            ishaAngle = 17.5,
        )

        SINGAPORE -> CalculationParameters(
            fajrAngle = 20.0,
            ishaAngle = 18.0,
            methodAdjustments = PrayerAdjustments(dhuhr = 1),
        )

        TURKEY -> CalculationParameters(
            fajrAngle = 18.0,
            ishaAngle = 17.0,
            methodAdjustments = PrayerAdjustments(sunrise = -7, dhuhr = 5, asr = 4, maghrib = 7),
        )

        TEHRAN -> CalculationParameters(
            fajrAngle = 17.7,
            ishaAngle = 14.0,
            maghribAngle = 4.5,
        )
    }
}
