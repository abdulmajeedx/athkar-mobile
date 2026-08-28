package com.athkar.feature.prayertimes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.athkar.core.prayer.CalculationMethod
import com.athkar.core.prayer.Madhab
import com.athkar.core.prayer.PolarDayException
import com.athkar.core.prayer.Prayer
import com.athkar.core.prayer.PrayerTimes
import com.athkar.domain.DeviceLocationSource
import com.athkar.domain.Place
import com.athkar.domain.PrayerPreferences
import com.athkar.domain.PrayerPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives the prayer-times screen.
 *
 * The day's six times and the once-a-second countdown are deliberately two separate flows: the
 * times change only when the settings or the date change, so folding them into the ticking state
 * would rebuild and recompose the whole list every second for the sake of one changing digit.
 */
@HiltViewModel
class PrayerTimesViewModel @Inject constructor(
    private val preferencesRepository: PrayerPreferencesRepository,
    private val locationSource: DeviceLocationSource,
) : ViewModel() {

    /** One row of the schedule. */
    data class PrayerRow(
        val prayer: Prayer,
        val at: Instant,
    )

    data class UiState(
        val isLoading: Boolean = true,
        val place: Place? = null,
        val method: CalculationMethod = CalculationMethod.UMM_AL_QURA,
        val madhab: Madhab = Madhab.SHAFI,
        val rows: List<PrayerRow> = emptyList(),
        val hijriDate: String = "",
        val gregorianDate: String = "",
        /** True before any place is known; the screen asks for one instead of showing wrong times. */
        val needsPlace: Boolean = false,
        val notificationsEnabled: Boolean = false,
        val notifiedPrayers: Set<Prayer> = emptySet(),
        val error: String? = null,
    )

    /** The part that changes every second. */
    data class Countdown(
        val next: Prayer? = null,
        val nextAt: Instant? = null,
        val remaining: Duration? = null,
        val current: Prayer? = null,
    )

    private val zone: ZoneId get() = ZoneId.systemDefault()

    private val _isLocating = MutableStateFlow(false)
    val isLocating: StateFlow<Boolean> = _isLocating.asStateFlow()

    private val _locationError = MutableStateFlow<String?>(null)
    val locationError: StateFlow<String?> = _locationError.asStateFlow()

    private val ticker = flow {
        while (true) {
            emit(Instant.now())
            delay(TICK_MILLIS)
        }
    }

    /** Recomputes only when the settings or the calendar day change. */
    private val today = ticker.map { LocalDate.now(zone) }.distinctUntilChanged()

    val uiState: StateFlow<UiState> =
        combine(preferencesRepository.observe(), today) { preferences, date ->
            buildState(preferences, date)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), UiState())

    val countdown: StateFlow<Countdown> =
        combine(uiState, ticker) { state, now -> buildCountdown(state, now) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), Countdown())

    private fun buildState(preferences: PrayerPreferences, date: LocalDate): UiState {
        val place = preferences.place
            ?: return UiState(
                isLoading = false,
                method = preferences.method,
                madhab = preferences.madhab,
                needsPlace = true,
                notificationsEnabled = preferences.notificationsEnabled,
                notifiedPrayers = preferences.notifiedPrayers,
                hijriDate = Formatting.hijriDate(date),
                gregorianDate = Formatting.gregorianDate(date),
            )

        return try {
            val times = PrayerTimes.calculate(
                coordinates = place.coordinates,
                date = date,
                parameters = preferences.calculationParameters(),
            )
            UiState(
                isLoading = false,
                place = place,
                method = preferences.method,
                madhab = preferences.madhab,
                rows = Prayer.entries.map { PrayerRow(it, times.timeFor(it)) },
                hijriDate = Formatting.hijriDate(date),
                gregorianDate = Formatting.gregorianDate(date),
                notificationsEnabled = preferences.notificationsEnabled,
                notifiedPrayers = preferences.notifiedPrayers,
            )
        } catch (e: PolarDayException) {
            UiState(
                isLoading = false,
                place = place,
                method = preferences.method,
                madhab = preferences.madhab,
                hijriDate = Formatting.hijriDate(date),
                gregorianDate = Formatting.gregorianDate(date),
                notificationsEnabled = preferences.notificationsEnabled,
                notifiedPrayers = preferences.notifiedPrayers,
                error = "الشمس لا تشرق ولا تغرب في هذا الموقع اليوم، فلا يمكن حساب المواقيت. " +
                    "اختر أقرب مدينة تحتها بخط عرض أدنى.",
            )
        }
    }

    private fun buildCountdown(state: UiState, now: Instant): Countdown {
        if (state.rows.isEmpty()) return Countdown()
        val next = state.rows.firstOrNull { it.at.isAfter(now) }
        val current = state.rows.lastOrNull { !it.at.isAfter(now) }
        return Countdown(
            next = next?.prayer,
            nextAt = next?.at,
            remaining = next?.let { Duration.between(now, it.at) },
            current = current?.prayer,
        )
    }

    /**
     * Asks the device where it is and stores the result. The caller must have obtained location
     * permission first; without it the source returns null and this reports the failure rather than
     * silently leaving the old place in place.
     */
    fun useDeviceLocation() {
        if (_isLocating.value) return
        viewModelScope.launch {
            _isLocating.value = true
            _locationError.value = null
            val place = runCatching { locationSource.currentPlace() }.getOrNull()
            if (place == null) {
                _locationError.value =
                    "تعذّر تحديد موقعك. تأكد من تفعيل خدمة الموقع، أو اختر مدينتك يدويًا."
            } else {
                preferencesRepository.setPlace(place)
            }
            _isLocating.value = false
        }
    }

    fun selectPlace(place: Place) {
        viewModelScope.launch {
            _locationError.value = null
            preferencesRepository.setPlace(place)
        }
    }

    fun selectMethod(method: CalculationMethod) {
        viewModelScope.launch { preferencesRepository.setMethod(method) }
    }

    fun selectMadhab(madhab: Madhab) {
        viewModelScope.launch { preferencesRepository.setMadhab(madhab) }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setNotificationsEnabled(enabled) }
    }

    /** Adds or removes one prayer from the alerting set, leaving the master switch untouched. */
    fun togglePrayerNotification(prayer: Prayer) {
        viewModelScope.launch {
            val current = uiState.value.notifiedPrayers
            val updated = if (prayer in current) current - prayer else current + prayer
            preferencesRepository.setNotifiedPrayers(updated)
        }
    }

    fun dismissLocationError() {
        _locationError.value = null
    }

    private companion object {
        const val TICK_MILLIS = 1_000L
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
