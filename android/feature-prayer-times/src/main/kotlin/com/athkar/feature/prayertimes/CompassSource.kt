package com.athkar.feature.prayertimes

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
        /** Degrees the device is tilted from flat; a steep tilt degrades the heading. */
        val tiltDegrees: Float = 0f,
    ) {
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
                        SensorManager.getOrientation(rotationMatrix, orientation)

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

                        val pitch = Math.toDegrees(orientation[1].toDouble())
                        val roll = Math.toDegrees(orientation[2].toDouble())
                        val tilt = kotlin.math.sqrt(pitch * pitch + roll * roll)

                        val smoothed = Math.toDegrees(kotlin.math.atan2(smoothedY, smoothedX))
                        trySend(
                            Heading(
                                trueHeadingDegrees = ((smoothed % 360 + 360) % 360).toFloat(),
                                accuracy = lastAccuracy,
                                fieldStrengthMicroTesla = measuredStrength,
                                expectedFieldStrengthMicroTesla = expectedStrength,
                                tiltDegrees = tilt.toFloat(),
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
    }
}
