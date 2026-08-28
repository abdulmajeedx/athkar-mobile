package com.athkar.core.prayer

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Degree/radian plumbing shared by the solar model. Kept internal: callers work in degrees, the
 * trigonometry works in radians, and every angle that feeds a `cos`/`sin` is normalised exactly once
 * at the boundary so no formula has to guess which convention it received.
 */
internal fun Double.degToRad(): Double = this * (PI / 180.0)

internal fun Double.radToDeg(): Double = this * (180.0 / PI)

/** Normalises to `[0, max)`; the sign-safe modulo the astronomical series expect. */
internal fun normalizeWithBound(value: Double, max: Double): Double = value - max * floor(value / max)

/** Normalises an angle to `[0, 360)`. */
internal fun Double.unwindAngle(): Double = normalizeWithBound(this, 360.0)

/** Maps an angle onto `(-180, 180]` — the shortest signed way round the circle. */
internal fun Double.closestAngle(): Double =
    if (this >= -180.0 && this <= 180.0) this else this - 360.0 * (this / 360.0).roundToLong()
