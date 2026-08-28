package com.athkar.core.prayer

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.roundToLong
import kotlin.math.sqrt
import kotlin.math.tan

/** The five obligatory prayers plus sunrise, which bounds the end of Fajr. */
enum class Prayer(val arabicName: String) {
    FAJR("الفجر"),
    SUNRISE("الشروق"),
    DHUHR("الظهر"),
    ASR("العصر"),
    MAGHRIB("المغرب"),
    ISHA("العشاء"),
}

/** Night-portion times used for qiyam. */
data class SunnahTimes(
    val middleOfTheNight: Instant,
    val lastThirdOfTheNight: Instant,
)

/**
 * Prayer times for one calendar day at one location.
 *
 * Times are [Instant]s — an unambiguous point in time — so the caller renders them in whatever zone
 * it wants without this class having to reason about offsets or DST. The calculation itself is
 * zone-free: [date] is only the local calendar day whose transit is being solved for.
 */
data class PrayerTimes(
    val coordinates: Coordinates,
    val date: LocalDate,
    val fajr: Instant,
    val sunrise: Instant,
    val dhuhr: Instant,
    val asr: Instant,
    val maghrib: Instant,
    val isha: Instant,
) {

    fun timeFor(prayer: Prayer): Instant = when (prayer) {
        Prayer.FAJR -> fajr
        Prayer.SUNRISE -> sunrise
        Prayer.DHUHR -> dhuhr
        Prayer.ASR -> asr
        Prayer.MAGHRIB -> maghrib
        Prayer.ISHA -> isha
    }

    /** The prayer whose time has most recently passed, or null before Fajr. */
    fun currentPrayer(at: Instant): Prayer? =
        Prayer.entries.lastOrNull { !timeFor(it).isAfter(at) }

    /** The next prayer to come, or null once Isha has passed. */
    fun nextPrayer(at: Instant): Prayer? =
        Prayer.entries.firstOrNull { timeFor(it).isAfter(at) }

    /**
     * Splits the night — Maghrib to the *following* day's Fajr — into the halves and thirds used for
     * qiyam. Pass the next day's times; using today's Fajr would measure the night backwards.
     */
    fun sunnahTimes(tomorrow: PrayerTimes): SunnahTimes {
        val night = Duration.between(maghrib, tomorrow.fajr)
        return SunnahTimes(
            middleOfTheNight = maghrib.plusSeconds(night.seconds / 2),
            lastThirdOfTheNight = maghrib.plusSeconds(night.seconds * 2 / 3),
        )
    }

    companion object {

        /**
         * Sun's altitude at apparent sunrise/sunset: 16' of semi-diameter plus 34' of standard
         * atmospheric refraction, below the horizon.
         */
        private const val SUNRISE_SUNSET_ALTITUDE = -0.833

        /**
         * Computes the six times for [date] at [coordinates].
         *
         * @param elevationMeters observer height above the local horizon; it advances sunrise and
         *   delays sunset by roughly one minute per 100 m and is otherwise ignored.
         */
        fun calculate(
            coordinates: Coordinates,
            date: LocalDate,
            parameters: CalculationParameters,
            elevationMeters: Double = 0.0,
        ): PrayerTimes {
            val julianDay = Astronomical.julianDay(date.year, date.monthValue, date.dayOfMonth)
            val solar = SolarCoordinates(julianDay)
            val previousSolar = SolarCoordinates(julianDay - 1)
            val nextSolar = SolarCoordinates(julianDay + 1)

            val approximateTransit = Astronomical.approximateTransit(
                coordinates.longitude, solar.apparentSiderealTime, solar.rightAscension,
            )
            val horizonAltitude =
                SUNRISE_SUNSET_ALTITUDE - 0.0347 * sqrt(elevationMeters.coerceAtLeast(0.0))

            fun hourAngle(angle: Double, afterTransit: Boolean): Double? =
                Astronomical.correctedHourAngle(
                    approximateTransit = approximateTransit,
                    angleAboveHorizon = angle,
                    coordinates = coordinates,
                    afterTransit = afterTransit,
                    siderealTime = solar.apparentSiderealTime,
                    rightAscension = solar.rightAscension,
                    previousRightAscension = previousSolar.rightAscension,
                    nextRightAscension = nextSolar.rightAscension,
                    declination = solar.declination,
                    previousDeclination = previousSolar.declination,
                    nextDeclination = nextSolar.declination,
                )

            val transitHours = Astronomical.correctedTransit(
                approximateTransit = approximateTransit,
                longitude = coordinates.longitude,
                siderealTime = solar.apparentSiderealTime,
                rightAscension = solar.rightAscension,
                previousRightAscension = previousSolar.rightAscension,
                nextRightAscension = nextSolar.rightAscension,
            )
            val sunriseHours = hourAngle(horizonAltitude, afterTransit = false)
            val sunsetHours = hourAngle(horizonAltitude, afterTransit = true)

            // A polar day or night leaves the sun permanently above or below the horizon; without a
            // sunrise there is no night to divide, so no rule can rescue the day.
            if (sunriseHours == null || sunsetHours == null) {
                throw PolarDayException(coordinates, date)
            }

            val asrAltitude = asrAltitude(
                shadowLength = parameters.madhab.shadowLength,
                latitude = coordinates.latitude,
                declination = solar.declination,
            )
            val asrHours = hourAngle(asrAltitude, afterTransit = true)
                ?: throw PolarDayException(coordinates, date)

            val dhuhrTime = instantOf(date, transitHours)
            val sunriseTime = instantOf(date, sunriseHours)
            val sunsetTime = instantOf(date, sunsetHours)
            val asrTime = instantOf(date, asrHours)

            // The night that Fajr and Isha are clamped against runs from today's sunset to
            // tomorrow's sunrise, not to today's.
            val tomorrowSunrise = tomorrowSunrise(coordinates, date, horizonAltitude)
            val night = Duration.between(sunsetTime, tomorrowSunrise)
            val (fajrPortion, ishaPortion) = parameters.nightPortions()

            val fajrTime = run {
                val computed = hourAngle(-parameters.fajrAngle, afterTransit = false)
                    ?.let { instantOf(date, it) }
                val safe = sunriseTime.minusSeconds((fajrPortion * night.seconds).roundToLong())
                if (computed == null || computed.isBefore(safe)) safe else computed
            }

            val maghribTime = if (parameters.maghribAngle > 0.0) {
                hourAngle(-parameters.maghribAngle, afterTransit = true)
                    ?.let { instantOf(date, it) } ?: sunsetTime
            } else {
                sunsetTime
            }

            val ishaTime = if (parameters.ishaInterval > 0) {
                maghribTime.plus(parameters.ishaInterval.toLong(), ChronoUnit.MINUTES)
            } else {
                val computed = hourAngle(-parameters.ishaAngle, afterTransit = true)
                    ?.let { instantOf(date, it) }
                val safe = sunsetTime.plusSeconds((ishaPortion * night.seconds).roundToLong())
                if (computed == null || computed.isAfter(safe)) safe else computed
            }

            val offsets = parameters.adjustments + parameters.methodAdjustments
            return PrayerTimes(
                coordinates = coordinates,
                date = date,
                fajr = fajrTime.shiftedBy(offsets.fajr),
                sunrise = sunriseTime.shiftedBy(offsets.sunrise),
                dhuhr = dhuhrTime.shiftedBy(offsets.dhuhr),
                asr = asrTime.shiftedBy(offsets.asr),
                maghrib = maghribTime.shiftedBy(offsets.maghrib),
                isha = ishaTime.shiftedBy(offsets.isha),
            )
        }

        /** Sunrise on the day after [date], needed to measure the length of tonight. */
        private fun tomorrowSunrise(
            coordinates: Coordinates,
            date: LocalDate,
            horizonAltitude: Double,
        ): Instant {
            val tomorrow = date.plusDays(1)
            val julianDay = Astronomical.julianDay(tomorrow.year, tomorrow.monthValue, tomorrow.dayOfMonth)
            val solar = SolarCoordinates(julianDay)
            val previousSolar = SolarCoordinates(julianDay - 1)
            val nextSolar = SolarCoordinates(julianDay + 1)
            val approximateTransit = Astronomical.approximateTransit(
                coordinates.longitude, solar.apparentSiderealTime, solar.rightAscension,
            )
            val hours = Astronomical.correctedHourAngle(
                approximateTransit = approximateTransit,
                angleAboveHorizon = horizonAltitude,
                coordinates = coordinates,
                afterTransit = false,
                siderealTime = solar.apparentSiderealTime,
                rightAscension = solar.rightAscension,
                previousRightAscension = previousSolar.rightAscension,
                nextRightAscension = nextSolar.rightAscension,
                declination = solar.declination,
                previousDeclination = previousSolar.declination,
                nextDeclination = nextSolar.declination,
            ) ?: throw PolarDayException(coordinates, tomorrow)
            return instantOf(tomorrow, hours)
        }

        /**
         * Sun's altitude when an object's shadow has grown by [shadowLength] times its own height
         * beyond the shadow it cast at transit.
         */
        private fun asrAltitude(shadowLength: Int, latitude: Double, declination: Double): Double {
            val noonShadowAngle = abs(latitude - declination)
            return atan(1.0 / (shadowLength + tan(noonShadowAngle.degToRad()))).radToDeg()
        }

        /**
         * Converts hours-from-midnight-UT into an instant, rounded to the nearest minute — the
         * precision at which prayer times are published and displayed. Hours outside `0..24` roll
         * into the neighbouring day, which is normal for locations far from their zone meridian.
         */
        private fun instantOf(date: LocalDate, hoursUt: Double): Instant {
            require(hoursUt.isFinite()) { "non-finite hour angle for $date" }
            return date.atStartOfDay(ZoneOffset.UTC).toInstant()
                .plus((hoursUt * 60.0).roundToLong(), ChronoUnit.MINUTES)
        }

        private fun Instant.shiftedBy(minutes: Int): Instant =
            if (minutes == 0L.toInt()) this else plus(minutes.toLong(), ChronoUnit.MINUTES)
    }
}

/**
 * Thrown when the sun does not cross the horizon on the requested day, so sunrise and sunset — and
 * therefore every time derived from them — are undefined. Above the polar circles for part of the
 * year; callers should fall back to a nearby lower-latitude reference or to Makkah timings.
 */
class PolarDayException(
    val coordinates: Coordinates,
    val date: LocalDate,
) : IllegalStateException(
    "The sun neither rises nor sets at ${coordinates.latitude}, ${coordinates.longitude} on $date"
)
