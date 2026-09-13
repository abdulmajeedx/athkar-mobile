package com.athkar.domain

import com.athkar.core.prayer.Coordinates
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.flow.Flow

/**
 * How the user told the app where true north really is.
 *
 * Both work the same way — the app knows the sun's true azimuth to a fraction of a degree from the
 * date, the time and the coordinates, so any sighting of the sun is a sighting of an absolute
 * bearing — and they differ only in what the user aims with.
 */
enum class SightingMethod(
    val arabicName: String,
    /**
     * How far a heading corrected by this method can still be out.
     *
     * Stated rather than hidden, because the whole feature is a claim about accuracy, and a claim
     * about accuracy without its own error bar is marketing. The computed solar azimuth contributes
     * a fraction of a degree to these; all the rest is the hand that did the aiming.
     */
    val expectedErrorDegrees: Double,
) {
    /**
     * The shadow of anything upright, which points exactly away from the sun.
     *
     * The recommended one, and by a distance. A shadow's edge is a sharp line lying on the ground
     * that can be matched to within a degree or so, the phone can rest flat while it is done, and
     * nobody has to look anywhere near the sun to do it.
     */
    SHADOW("بالظل", expectedErrorDegrees = 2.0),

    /** The sun itself, for when there is no shadow to use — a hazier sighting, and a harsher one. */
    SUN("بالشمس", expectedErrorDegrees = 5.0),
    ;

    /**
     * The true bearing the top of the phone is pointing along when the sighting is made.
     *
     * The shadow falls on the anti-solar bearing, so aligning the phone with a shadow that runs
     * away from the observer means the phone points at the sun's azimuth plus a half turn.
     */
    fun trueBearing(sunAzimuth: Double): Double = when (this) {
        SUN -> sunAzimuth.normalisedDegrees()
        SHADOW -> (sunAzimuth + 180.0).normalisedDegrees()
    }
}

/**
 * A correction to the magnetic compass, measured against the sun.
 *
 * This is the answer to the one failure a phone compass cannot detect in itself. The magnetometer
 * measures the local field, not the Earth's: a steel balcony rail, a car door, a magnetic case or a
 * laptop half a metre away bends it by tens of degrees, and the sensor reports the bent bearing with
 * exactly the same confidence as a clean one. Comparing the field's *strength* against the World
 * Magnetic Model catches gross interference, but a field that is bent without changing much in
 * magnitude passes that test and is still wrong.
 *
 * The sun is outside all of it. Its azimuth at a given place and instant is a matter of celestial
 * mechanics, computed here to a small fraction of a degree, and no magnet within a thousand
 * kilometres can move it. One sighting of it says what the compass *should* have read, and the
 * difference is this offset — which then corrects every later reading, hard-iron bias, soft-iron
 * distortion, stale calibration and all.
 */
data class CompassCalibration(
    /** Degrees to add to a raw heading to get the true one, on `[0, 360)`. */
    val offsetDegrees: Double,
    val takenAt: Instant,
    /** Where it was taken — a correction is local to the metal that made it necessary. */
    val latitude: Double,
    val longitude: Double,
    val method: SightingMethod,
) {
    /** The correction as a signed turn, which is how it is worth reading: "+7°", "−23°". */
    val signedOffset: Double get() = ((offsetDegrees + 180.0) % 360.0 + 360.0) % 360.0 - 180.0

    /** A raw magnetic heading, corrected. */
    fun correct(rawHeading: Float): Float =
        ((rawHeading + offsetDegrees) % 360.0 + 360.0).toFloat() % 360f

    /**
     * True once this correction has stopped describing the user's surroundings.
     *
     * Both limits are about the same thing. The offset measures the iron *around the phone at the
     * moment of the sighting*: step out of the car it was taken in and the distortion it cancels is
     * no longer there, so a stale correction does not merely lose accuracy — it actively adds an
     * error that was not in the raw reading. Better to expire and say so.
     */
    fun isStale(now: Instant, at: Coordinates?): Boolean {
        if (Duration.between(takenAt, now) >= MAX_AGE) return true
        if (at == null) return false
        return distanceKm(latitude, longitude, at.latitude, at.longitude) > MAX_DRIFT_KM
    }

    companion object {
        /**
         * Long enough to cover a stay somewhere, short enough that it cannot survive the journey
         * home. The magnetic environment of a room does not change; the user's position in it does.
         */
        val MAX_AGE: Duration = Duration.ofHours(6)

        /** Past this the user is somewhere else, whatever the clock says. */
        const val MAX_DRIFT_KM = 20.0

        /**
         * A sighting past this altitude is refused.
         *
         * Near the zenith the sun has no useful bearing: its azimuth sweeps through a large angle in
         * minutes, shadows shrink to nothing, and an aiming error of a degree in the hand becomes
         * many degrees in the answer. Two hours either side of noon in summer is the window this
         * shuts, and it shuts it rather than quietly returning a bad correction.
         */
        const val MAX_USABLE_ALTITUDE = 70.0

        /** Below this the sun is too close to the horizon to be seen or to cast a defined edge. */
        const val MIN_USABLE_ALTITUDE = 1.0

        /**
         * Builds the correction from one sighting.
         *
         * @param sunAzimuth the sun's true azimuth at [takenAt], degrees clockwise from true north.
         * @param rawHeading what the magnetometer said the phone was pointing at, uncorrected.
         */
        fun fromSighting(
            method: SightingMethod,
            sunAzimuth: Double,
            rawHeading: Float,
            takenAt: Instant,
            at: Coordinates,
        ): CompassCalibration = CompassCalibration(
            offsetDegrees = (method.trueBearing(sunAzimuth) - rawHeading).normalisedDegrees(),
            takenAt = takenAt,
            latitude = at.latitude,
            longitude = at.longitude,
            method = method,
        )

        /** Whether the sun is usable for a sighting right now. */
        fun canSight(sunAltitude: Double): Boolean =
            sunAltitude in MIN_USABLE_ALTITUDE..MAX_USABLE_ALTITUDE

        private const val EARTH_RADIUS_KM = 6371.0088

        private fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val phi1 = Math.toRadians(lat1)
            val phi2 = Math.toRadians(lat2)
            val deltaPhi = Math.toRadians(lat2 - lat1)
            val deltaLambda = Math.toRadians(lon2 - lon1)
            val a = sin(deltaPhi / 2) * sin(deltaPhi / 2) +
                cos(phi1) * cos(phi2) * sin(deltaLambda / 2) * sin(deltaLambda / 2)
            return 2 * EARTH_RADIUS_KM * asin(sqrt(a).coerceAtMost(1.0))
        }
    }
}

/** Any angle, brought onto `[0, 360)`. */
internal fun Double.normalisedDegrees(): Double = ((this % 360.0) + 360.0) % 360.0

/** True when two bearings are within [tolerance] degrees of each other, the short way round. */
fun bearingsAgree(a: Double, b: Double, tolerance: Double): Boolean =
    abs(((a - b + 180.0).normalisedDegrees()) - 180.0) <= tolerance

/**
 * Port for the one correction in force, if any.
 *
 * Persisted rather than held in memory: the sighting is a deliberate act performed outdoors, and
 * making the user repeat it every time the qibla screen is reopened would mean nobody used it twice.
 */
interface CompassCalibrationRepository {
    fun observe(): Flow<CompassCalibration?>
    suspend fun save(calibration: CompassCalibration)
    suspend fun clear()
}
