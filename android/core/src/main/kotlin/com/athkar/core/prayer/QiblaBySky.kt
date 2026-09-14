package com.athkar.core.prayer

import java.time.Instant

/**
 * Something in the sky whose direction the app knows exactly.
 *
 * Each of these is a true bearing computed from the date, the time and the coordinates — arithmetic
 * all the way down, with nothing in it that a car door or a magnetic case can bend.
 */
enum class SkyReference(val arabicName: String) {
    /** The sun itself. Precise, and the one thing nobody should be told to stare at. */
    SUN("الشمس"),

    /**
     * The shadow of anything upright, which falls exactly opposite the sun.
     *
     * The best of the three in practice: a shadow is a sharp line lying still on the ground, it can
     * be sighted along to within a degree, and looking at it is safe.
     */
    SHADOW("الظل"),

    /** The moon, for the hours when the other two are unavailable — which is most of Isha. */
    MOON("القمر"),
}

/**
 * The qibla expressed as a turn from something visible in the sky.
 *
 * @property bearing the reference's own true azimuth, degrees clockwise from true north.
 * @property turn the signed angle from the reference to the qibla, on `(-180, 180]`. Positive is
 *   clockwise — to the user's right as they face the reference.
 * @property altitude how high the body stands, in degrees.
 */
data class SkyFix(
    val reference: SkyReference,
    val bearing: Double,
    val turn: Double,
    val altitude: Double,
    /** Fraction of the moon's disc lit, for [SkyReference.MOON]; null for the other two. */
    val illumination: Double? = null,
)

/**
 * The qibla without a compass.
 *
 * This is the answer to the oldest problem in the app: a magnetometer beside anything ferrous is
 * wrong by tens of degrees and says so with complete confidence, and no amount of warning text
 * fixes a reading that is simply false. So the direction is taken from the sky instead, the way it
 * was taken for a thousand years before there were magnets in phones — face the sun, turn so many
 * degrees, and you are facing the Kaaba.
 *
 * Nothing here is measured. There is no sensor in it, no calibration to perform and nothing for the
 * user to hold still: given a place and a moment it is all computed, to a small fraction of a
 * degree, from the same solar and lunar models the prayer times rest on.
 */
object QiblaBySky {

    /**
     * Below this the body is too close to the horizon to sight: it is behind the rooftops, the
     * refraction is worst there, and a shadow at this angle runs off across the next street.
     */
    private const val MIN_ALTITUDE = 5.0

    /**
     * Above this the sun has no useful bearing on the ground: its azimuth sweeps through a large
     * angle in minutes and a vertical stick casts almost no shadow to sight along.
     */
    private const val MAX_SUN_ALTITUDE = 75.0

    /**
     * Below this fraction lit, the moon is a thin crescent or invisible — and a crescent close to
     * the sun sits in twilight where it cannot be picked out reliably.
     */
    private const val MIN_ILLUMINATION = 0.15

    /**
     * Every reference that can be used right now, best first.
     *
     * Ordered by how precisely a person can actually sight the thing, not by how precisely the app
     * knows where it is: the shadow first because it is a line on the ground that holds still, then
     * the moon, which can be looked at directly, and the sun last because nobody should be asked to
     * look at it.
     */
    fun fixes(at: Coordinates, moment: Instant): List<SkyFix> {
        val qibla = Qibla.direction(at)
        val sun = SolarPosition.at(at, moment)
        val moon = MoonPosition.at(at, moment)

        val available = mutableListOf<SkyFix>()

        if (sun.altitude in MIN_ALTITUDE..MAX_SUN_ALTITUDE) {
            // The shadow runs from the base of an upright object *away* from the sun.
            val shadowBearing = (sun.azimuth + 180.0).unwindAngle()
            available += SkyFix(
                reference = SkyReference.SHADOW,
                bearing = shadowBearing,
                turn = signedTurn(from = shadowBearing, to = qibla),
                altitude = sun.altitude,
            )
        }

        if (moon.altitude >= MIN_ALTITUDE && moon.illumination >= MIN_ILLUMINATION) {
            available += SkyFix(
                reference = SkyReference.MOON,
                bearing = moon.azimuth,
                turn = signedTurn(from = moon.azimuth, to = qibla),
                altitude = moon.altitude,
                illumination = moon.illumination,
            )
        }

        if (sun.altitude in MIN_ALTITUDE..MAX_SUN_ALTITUDE) {
            available += SkyFix(
                reference = SkyReference.SUN,
                bearing = sun.azimuth,
                turn = signedTurn(from = sun.azimuth, to = qibla),
                altitude = sun.altitude,
            )
        }

        return available
    }

    /** Signed angle from [from] to [to], on `(-180, 180]`; positive is clockwise. */
    private fun signedTurn(from: Double, to: Double): Double =
        ((to - from + 540.0) % 360.0) - 180.0
}
