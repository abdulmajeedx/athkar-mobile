package com.athkar.feature.prayertimes

import android.hardware.SensorManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.athkar.core.prayer.Qibla
import com.athkar.core.prayer.QiblaBySun
import com.athkar.core.prayer.QiblaSunAlignment
import com.athkar.domain.Place
import com.athkar.domain.PrayerPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/**
 * Drives the qibla screen: a fixed bearing derived from the stored place, and a live device heading
 * so the needle can point at it. The bearing alone is still useful without a magnetometer, so the
 * two are independent — losing the sensor degrades the screen instead of emptying it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class QiblaViewModel @Inject constructor(
    preferencesRepository: PrayerPreferencesRepository,
    private val compassSource: CompassSource,
) : ViewModel() {

    data class UiState(
        val isLoading: Boolean = true,
        val place: Place? = null,
        /** Degrees clockwise from true north, or null until a place is known. */
        val qiblaBearing: Double? = null,
        val distanceKm: Double? = null,
        /** Live device heading from true north, or null when there is no usable compass. */
        val headingDegrees: Float? = null,
        val hasCompass: Boolean = true,
        val needsCalibration: Boolean = false,
        /** The measured magnetic field disagrees with the model: something ferrous is nearby. */
        val isFieldDisturbed: Boolean = false,
        /** The phone is too far from flat for its heading to be trusted. */
        val isTooTilted: Boolean = false,
        /** Flat enough that the reading is as good as this device can give. */
        val isLevel: Boolean = false,
        /** Which way it is leaning, for the level drawn at the centre of the dial. */
        val pitchDegrees: Float = 0f,
        val rollDegrees: Float = 0f,
        /** Today's moments when the sun itself marks the qibla, which no magnet can disturb. */
        val sunAlignment: QiblaBySun? = null,
        val needsPlace: Boolean = false,
    )

    private val places = preferencesRepository.observe().map { it.place }

    val uiState: StateFlow<UiState> = places
        .flatMapLatest { place ->
            if (place == null) {
                flowOf(UiState(isLoading = false, needsPlace = true))
            } else {
                val bearing = Qibla.direction(place.coordinates)
                val distance = Qibla.distanceKm(place.coordinates)
                val hasCompass = compassSource.isAvailable()
                val base = UiState(
                    isLoading = false,
                    place = place,
                    qiblaBearing = bearing,
                    distanceKm = distance,
                    hasCompass = hasCompass,
                    // Computed once per place rather than per sensor reading: it depends on the
                    // date and the coordinates, neither of which moves while the screen is open.
                    sunAlignment = runCatching {
                        QiblaSunAlignment.forDate(
                            place.coordinates,
                            java.time.LocalDate.now(java.time.ZoneId.systemDefault()),
                            java.time.ZoneId.systemDefault(),
                        )
                    }.getOrNull(),
                )
                if (!hasCompass) {
                    flowOf(base)
                } else {
                    compassSource.headings(place.coordinates)
                        .map { heading ->
                            base.copy(
                                headingDegrees = heading.trueHeadingDegrees,
                                needsCalibration =
                                    heading.accuracy < SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM,
                                isFieldDisturbed = heading.isFieldDisturbed,
                                isTooTilted = heading.isTooTilted,
                                isLevel = heading.isLevel,
                                pitchDegrees = heading.pitchDegrees,
                                rollDegrees = heading.rollDegrees,
                            )
                        }
                        .onStart { emit(base) }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), UiState())

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
