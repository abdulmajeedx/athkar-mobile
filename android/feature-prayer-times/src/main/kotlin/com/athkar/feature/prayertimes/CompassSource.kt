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
    data class Heading(val trueHeadingDegrees: Float, val accuracy: Int)

    private val sensorManager: SensorManager?
        get() = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    /** True when the device can report a heading at all. */
    fun isAvailable(): Boolean =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null

    fun headings(at: Coordinates): Flow<Heading> = callbackFlow {
        val manager = sensorManager
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (manager == null || sensor == null) {
            close()
            return@callbackFlow
        }

        val declination = GeomagneticField(
            at.latitude.toFloat(),
            at.longitude.toFloat(),
            0f,
            System.currentTimeMillis(),
        ).declination

        val rotationMatrix = FloatArray(9)
        val orientation = FloatArray(3)

        val listener = object : SensorEventListener {
            private var lastAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE

            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientation)
                val magneticHeading = Math.toDegrees(orientation[0].toDouble()).toFloat()
                val trueHeading = ((magneticHeading + declination) % 360f + 360f) % 360f
                trySend(Heading(trueHeading, lastAccuracy))
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                lastAccuracy = accuracy
            }
        }

        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        awaitClose { manager.unregisterListener(listener) }
    }
}
