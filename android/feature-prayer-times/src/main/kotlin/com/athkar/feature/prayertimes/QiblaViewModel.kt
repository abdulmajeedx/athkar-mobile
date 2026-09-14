package com.athkar.feature.prayertimes

import android.hardware.SensorManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.athkar.core.prayer.Qibla
import com.athkar.core.prayer.QiblaBySky
import com.athkar.core.prayer.QiblaBySun
import com.athkar.core.prayer.QiblaSunAlignment
import com.athkar.core.prayer.SkyFix
import com.athkar.core.prayer.SolarPosition
import com.athkar.domain.CompassCalibration
import com.athkar.domain.CompassCalibrationRepository
import com.athkar.domain.Place
import com.athkar.domain.PrayerPreferencesRepository
import com.athkar.domain.SightingMethod
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
    private val calibrationRepository: CompassCalibrationRepository,
) : ViewModel() {

    data class UiState(
        val isLoading: Boolean = true,
        val place: Place? = null,
        /** Degrees clockwise from true north, or null until a place is known. */
        val qiblaBearing: Double? = null,
        val distanceKm: Double? = null,
        /** Live device heading from true north, corrected when a sighting is in force. */
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
        /**
         * The qibla as a turn from something visible in the sky, right now.
         *
         * The direction without a compass at all: computed rather than measured, so there is
         * nothing in it for a car door to bend and nothing for the user to calibrate.
         */
        val skyFixes: List<SkyFix> = emptyList(),
        /** The correction in force, or null when the compass is running raw. */
        val calibration: CompassCalibration? = null,
        /** Where the sun is at this moment, for taking a sighting against. */
        val sun: SolarPosition? = null,
        val needsPlace: Boolean = false,
    ) {
        /** True when the heading shown has been measured against the sun rather than assumed. */
        val isCalibrated: Boolean get() = calibration != null

        /** Whether a sighting can usefully be taken at this instant. */
        val canSightSun: Boolean get() = sun != null && CompassCalibration.canSight(sun.altitude)
    }

    private val places = preferencesRepository.observe().map { it.place }

    /**
     * The last *uncorrected* reading.
     *
     * Kept apart from the UI state deliberately: a sighting compares what the sensor said against
     * where the sun truly is, so handing it a heading that already carries a previous correction
     * would measure the compass against itself and stack one offset on top of the next.
     */
    private val rawHeading = MutableStateFlow<Float?>(null)

    /** Ticks the sun's position; it moves about a degree every four minutes. */
    private val solarTicker = flow {
        while (true) {
            emit(Instant.now())
            delay(SUN_TICK_MILLIS)
        }
    }

    val uiState: StateFlow<UiState> = places
        .flatMapLatest { place ->
            if (place == null) flowOf(UiState(isLoading = false, needsPlace = true))
            else readingsFor(place)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), UiState())

    private fun readingsFor(place: Place): Flow<UiState> {
        val base = UiState(
            isLoading = false,
            place = place,
            qiblaBearing = Qibla.direction(place.coordinates),
            distanceKm = Qibla.distanceKm(place.coordinates),
            hasCompass = compassSource.isAvailable(),
            // Computed once per place rather than per sensor reading: it depends on the date and
            // the coordinates, neither of which moves while the screen is open.
            sunAlignment = runCatching {
                QiblaSunAlignment.forDate(
                    place.coordinates,
                    java.time.LocalDate.now(java.time.ZoneId.systemDefault()),
                    java.time.ZoneId.systemDefault(),
                )
            }.getOrNull(),
        )

        val corrections = calibrationRepository.observe().map { stored ->
            // A correction that has expired, or that was taken somewhere else, is worse than none —
            // it adds an error the raw reading did not have. Dropped here rather than where it is
            // applied, so the screen shows the compass running raw and can say why.
            stored?.takeUnless { it.isStale(Instant.now(), place.coordinates) }
        }
        // One tick drives both: where the sun is for a sighting, and where the sky says the qibla
        // is. Recomputing the fixes is a few hundred floating-point operations — cheaper than the
        // recomposition it feeds.
        val sky = solarTicker.map { moment ->
            SolarPosition.at(place.coordinates, moment) to
                QiblaBySky.fixes(place.coordinates, moment)
        }

        if (!base.hasCompass) {
            return combine(corrections, sky) { calibration, (position, fixes) ->
                base.copy(calibration = calibration, sun = position, skyFixes = fixes)
            }
        }

        return combine(
            compassSource.headings(place.coordinates),
            corrections,
            sky,
        ) { heading, calibration, (position, fixes) ->
            rawHeading.value = heading.trueHeadingDegrees
            base.copy(
                headingDegrees = calibration?.correct(heading.trueHeadingDegrees)
                    ?: heading.trueHeadingDegrees,
                needsCalibration = heading.accuracy < SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM,
                isFieldDisturbed = heading.isFieldDisturbed,
                isTooTilted = heading.isTooTilted,
                isLevel = heading.isLevel,
                pitchDegrees = heading.pitchDegrees,
                rollDegrees = heading.rollDegrees,
                calibration = calibration,
                sun = position,
                skyFixes = fixes,
            )
        }.onStart { emit(base) }
    }

    /**
     * Takes a sighting: the user is saying the phone points at the sun, or along the shadow, *now*.
     *
     * Everything hangs on that instant, so the sun's azimuth is recomputed here rather than read
     * from the ticking state — the reference has to be the moment of the tap, not up to twenty
     * seconds before it — and the heading used is the raw one for the same reason.
     */
    fun sight(method: SightingMethod) {
        val state = uiState.value
        val place = state.place ?: return
        val raw = rawHeading.value ?: return
        // A sighting taken while the phone leans measures the lean as well as the compass, and then
        // applies that error to every later reading — the one way this feature could leave someone
        // worse off than the bare magnetometer did.
        if (!state.isLevel) return
        val now = Instant.now()
        val sun = SolarPosition.at(place.coordinates, now)
        if (!CompassCalibration.canSight(sun.altitude)) return

        viewModelScope.launch {
            calibrationRepository.save(
                CompassCalibration.fromSighting(
                    method = method,
                    sunAzimuth = sun.azimuth,
                    rawHeading = raw,
                    takenAt = now,
                    at = place.coordinates,
                ),
            )
        }
    }

    /** Drops the correction and returns the needle to the bare magnetometer. */
    fun clearCalibration() {
        viewModelScope.launch { calibrationRepository.clear() }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L

        /** The sun moves a degree every four minutes; twenty seconds is far finer than needed. */
        const val SUN_TICK_MILLIS = 20_000L
    }
}
