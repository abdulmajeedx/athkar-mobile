package com.athkar.feature.prayertimes

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface
import com.athkar.core.prayer.Coordinates
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Device heading from the fused rotation-vector sensor.
 *
 * The sensor reports bearings from *magnetic* north, which is up to ~25 degrees away from true
 * north depending on where you are — far more than the precision a qibla needs. Callers pass their
 * coordinates so the local magnetic declination can be added and the heading returned relative to
 * true north, which is what the qibla bearing is measured from.
 *
 * The math assumes the device is held upright in portrait. Flat-on-a-table and landscape readings
 * are not remapped.
 */
@Singleton
class CompassSource @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    /**
     * @property trueHeadingDegrees clockwise from true north, `[0, 360)`.
     * @property accuracy one of the `SensorManager.SENSOR_STATUS_*` constants; anything below
     *   [SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM] means the user should re-calibrate.
     */
    data class Heading(
        val trueHeadingDegrees: Float,
        val accuracy: Int,
        /** Measured field strength in microtesla, or null before the magnetometer has reported. */
        val fieldStrengthMicroTesla: Float? = null,
        /** What the geomagnetic model says the field should be here, in microtesla. */
        val expectedFieldStrengthMicroTesla: Float? = null,
        /**
         * Degrees the top edge of the screen is tipped down from flat, negative when tipped up.
         *
         * Reported alongside [rollDegrees] rather than only as the combined [tiltDegrees] so the
         * screen can show *which way* the phone is leaning. "Hold it flat" tells someone their
         * reading is being spoiled without telling them what to do with their hand.
         */
        val pitchDegrees: Float = 0f,
        /** Degrees the right edge is tipped down from flat, negative when tipped up. */
        val rollDegrees: Float = 0f,
    ) {
        /** How far from flat, in any direction; a steep tilt degrades the heading. */
        val tiltDegrees: Float
            get() = kotlin.math.sqrt(
                pitchDegrees * pitchDegrees + rollDegrees * rollDegrees,
            )

        /**
         * True when the phone is flat enough for the heading to be worth trusting.
         *
         * Stricter than [isTooTilted], and deliberately: that one is the point past which the
         * reading is *wrong*, this is the point within which it is *right*. Most people hold a
         * phone at a reading angle without ever being told that a compass needs it level, so the
         * qibla is only confirmed inside this band.
         */
        val isLevel: Boolean get() = tiltDegrees <= LEVEL_TOLERANCE_DEGREES

        /**
         * True when the measured field is far enough from the model's value that something ferrous
         * or magnetic is nearby.
         *
         * This is the check that catches the failure a compass never announces. An uncalibrated or
         * disturbed magnetometer does not report an error — it reports a heading, confidently, that
         * can be tens of degrees wrong. Comparing the field's magnitude against what the World
         * Magnetic Model says it should be at this point on Earth is the one cheap, local test that
         * a phone can actually run.
         */
        val isFieldDisturbed: Boolean
            get() {
                val measured = fieldStrengthMicroTesla ?: return false
                val expected = expectedFieldStrengthMicroTesla ?: return false
                return kotlin.math.abs(measured - expected) > expected * FIELD_TOLERANCE
            }

        /** True when the phone is too far from flat for the heading to be dependable. */
        val isTooTilted: Boolean get() = tiltDegrees > MAX_USABLE_TILT_DEGREES
    }

    private val sensorManager: SensorManager?
        get() = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    /** True when the device can report a heading at all. */
    fun isAvailable(): Boolean =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null

    /**
     * The axis pair that turns device coordinates into screen coordinates.
     *
     * Read on every reading rather than once: the screen can rotate while the compass is open, and
     * a value captured at subscription time would be stale from that moment on.
     */
    private fun displayAxes(): Pair<Int, Int> {
        return when (displayRotation()) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
    }

    /**
     * How far the screen is turned from the device's natural orientation.
     *
     * Read through the *display manager*, never through `Context.display`. This class is
     * constructed with the application context, and asking an application context for its display
     * throws `UnsupportedOperationException` on Android 11 and later — it is not associated with
     * one. That is not a theoretical hazard: it shipped, and it crashed the qibla screen the moment
     * it opened.
     *
     * Every failure here falls back to the natural orientation, which is what the compass assumed
     * before any of this existed. A heading that is ninety degrees out on a rotated tablet is a bug
     * worth fixing; a compass that cannot open is worse than the bug it was fixing.
     */
    private fun displayRotation(): Int = runCatching {
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        manager?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
    }.getOrNull() ?: Surface.ROTATION_0

    fun headings(at: Coordinates): Flow<Heading> = callbackFlow {
        val manager = sensorManager
        val rotation = manager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (manager == null || rotation == null) {
            close()
            return@callbackFlow
        }
        val magnetometer = manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        val field = GeomagneticField(
            at.latitude.toFloat(),
            at.longitude.toFloat(),
            0f,
            System.currentTimeMillis(),
        )
        val declination = field.declination
        // getFieldStrength returns nanotesla; the sensor reports microtesla.
        val expectedStrength = field.fieldStrength / 1000f

        val rotationMatrix = FloatArray(9)
        val remappedMatrix = FloatArray(9)
        val orientation = FloatArray(3)

        val listener = object : SensorEventListener {
            private var lastAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
            private var measuredStrength: Float? = null
            private var smoothedX = 0.0
            private var smoothedY = 0.0
            private var hasHeading = false

            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
                        measuredStrength = kotlin.math.sqrt(x * x + y * y + z * z)
                    }

                    Sensor.TYPE_ROTATION_VECTOR -> {
                        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                        // Remapped for however the screen is turned. getOrientation reads the
                        // device's natural orientation, which on a phone held sideways is ninety
                        // degrees from where the user is looking — the needle pointed confidently
                        // at the wrong wall, and nothing on screen suggested anything was wrong.
                        val (axisX, axisY) = displayAxes()
                        SensorManager.remapCoordinateSystem(
                            rotationMatrix, axisX, axisY, remappedMatrix,
                        )
                        SensorManager.getOrientation(remappedMatrix, orientation)

                        val magneticHeading = Math.toDegrees(orientation[0].toDouble())
                        val trueHeading = magneticHeading + declination

                        // Smoothed as a unit vector, not as an angle: averaging degrees swings the
                        // needle the long way round whenever a reading straddles north.
                        val radians = Math.toRadians(trueHeading)
                        val x = kotlin.math.cos(radians)
                        val y = kotlin.math.sin(radians)
                        if (hasHeading) {
                            smoothedX += SMOOTHING * (x - smoothedX)
                            smoothedY += SMOOTHING * (y - smoothedY)
                        } else {
                            smoothedX = x
                            smoothedY = y
                            hasHeading = true
                        }

                        // From the remapped matrix, so these are the screen's own axes however the
                        // device is turned — which is what a level drawn on that screen needs.
                        val pitch = Math.toDegrees(orientation[1].toDouble())
                        val roll = Math.toDegrees(orientation[2].toDouble())

                        val smoothed = Math.toDegrees(kotlin.math.atan2(smoothedY, smoothedX))
                        trySend(
                            Heading(
                                trueHeadingDegrees = ((smoothed % 360 + 360) % 360).toFloat(),
                                accuracy = lastAccuracy,
                                fieldStrengthMicroTesla = measuredStrength,
                                expectedFieldStrengthMicroTesla = expectedStrength,
                                pitchDegrees = pitch.toFloat(),
                                rollDegrees = roll.toFloat(),
                            )
                        )
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                if (sensor?.type == Sensor.TYPE_ROTATION_VECTOR) lastAccuracy = accuracy
            }
        }

        manager.registerListener(listener, rotation, SensorManager.SENSOR_DELAY_UI)
        magnetometer?.let { manager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        awaitClose { manager.unregisterListener(listener) }
    }

    private companion object {
        /** Low-pass factor: low enough to still the jitter, high enough to keep up with a turn. */
        const val SMOOTHING = 0.15

        /** A fifth off the modelled field is past calibration drift and means real interference. */
        const val FIELD_TOLERANCE = 0.20f

        /** Beyond this tilt the horizontal projection the heading rests on stops being dependable. */
        const val MAX_USABLE_TILT_DEGREES = 35f

        /**
         * Within this much of flat the heading is as good as the sensor can give.
         *
         * Ten degrees rather than two: it has to be a band a hand can hold at arm's length, not a
         * knife edge that flickers in and out while someone stands as still as a person can. Past
         * it the projection error starts to show; inside it, the remaining error is smaller than
         * the four degrees the qibla itself is called aligned within.
         */
        const val LEVEL_TOLERANCE_DEGREES = 10f
    }
}
