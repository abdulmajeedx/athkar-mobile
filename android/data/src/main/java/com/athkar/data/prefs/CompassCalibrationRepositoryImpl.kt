package com.athkar.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.athkar.domain.CompassCalibration
import com.athkar.domain.CompassCalibrationRepository
import com.athkar.domain.SightingMethod
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.calibrationDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "compass_calibration",
)

/**
 * The solar correction, kept across launches.
 *
 * Its own store rather than a column beside the prayer settings: this is the one preference in the
 * app that is *expected* to be thrown away — it expires with the afternoon and with the user walking
 * away — and mixing something deliberately short-lived into the settings that must never be lost is
 * how the settings that must never be lost end up cleared.
 */
@Singleton
class CompassCalibrationRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : CompassCalibrationRepository {

    override fun observe(): Flow<CompassCalibration?> =
        context.calibrationDataStore.data.map { prefs ->
            val offset = prefs[KEY_OFFSET] ?: return@map null
            val takenAt = prefs[KEY_TAKEN_AT] ?: return@map null
            val latitude = prefs[KEY_LATITUDE] ?: return@map null
            val longitude = prefs[KEY_LONGITUDE] ?: return@map null
            CompassCalibration(
                offsetDegrees = offset,
                takenAt = Instant.ofEpochSecond(takenAt),
                latitude = latitude,
                longitude = longitude,
                // A method name from a later version reads as the conservative one rather than
                // throwing the whole correction away over a label.
                method = SightingMethod.entries.firstOrNull { it.name == prefs[KEY_METHOD] }
                    ?: SightingMethod.SUN,
            )
        }

    override suspend fun save(calibration: CompassCalibration) {
        context.calibrationDataStore.edit { prefs ->
            prefs[KEY_OFFSET] = calibration.offsetDegrees
            prefs[KEY_TAKEN_AT] = calibration.takenAt.epochSecond
            prefs[KEY_LATITUDE] = calibration.latitude
            prefs[KEY_LONGITUDE] = calibration.longitude
            prefs[KEY_METHOD] = calibration.method.name
        }
    }

    override suspend fun clear() {
        context.calibrationDataStore.edit { it.clear() }
    }

    private companion object {
        val KEY_OFFSET = doublePreferencesKey("offset_degrees")
        val KEY_TAKEN_AT = longPreferencesKey("taken_at")
        val KEY_LATITUDE = doublePreferencesKey("latitude")
        val KEY_LONGITUDE = doublePreferencesKey("longitude")
        val KEY_METHOD = stringPreferencesKey("method")
    }
}
