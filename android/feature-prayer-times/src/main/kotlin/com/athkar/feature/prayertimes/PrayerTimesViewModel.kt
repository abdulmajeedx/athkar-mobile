package com.athkar.feature.prayertimes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.athkar.core.prayer.CalculationMethod
import com.athkar.core.prayer.HighLatitudeRule
import com.athkar.core.prayer.Madhab
import com.athkar.core.prayer.PolarDayException
import com.athkar.core.prayer.Prayer
import com.athkar.core.prayer.PrayerTimes
import com.athkar.domain.AlertSound
import com.athkar.domain.AlertSoundPreview
import com.athkar.domain.DailyAdhkar
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
    private val alertSoundPreview: AlertSoundPreview,
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
        val alertSound: AlertSound = AlertSound.DEFAULT,
        val preAdhanMinutes: Int = 0,
        val highLatitudeRule: HighLatitudeRule? = null,
        val iqamaMinutes: Map<Prayer, Int> = emptyMap(),
        val adhkarReminders: Set<DailyAdhkar> = emptySet(),
        /** Today's reminder times, so the switch says when it will speak rather than just that it will. */
        val adhkarReminderTimes: Map<DailyAdhkar, Instant> = emptyMap(),
        /**
         * Tomorrow's dawn, so the hours after Isha have something to count down to.
         *
         * Between Isha and midnight there is no later prayer today, and that is exactly when
         * someone opens the app to see when they have to be up. Without this the screen said the
         * day's prayers were over and showed nothing else — while the alarm for that same Fajr was
         * already registered.
         */
        val tomorrowFajrAt: Instant? = null,
        val error: String? = null,
    )

    /**
     * The part that changes every second.
     *
     * Between the adhan and the iqama the countdown is the one the worshipper actually needs, so it
     * takes over the display; afterwards it becomes the time elapsed since the call, which answers
     * "have I missed it" at a glance.
     */
    data class Countdown(
        val next: Prayer? = null,
        val nextAt: Instant? = null,
        /** True when [next] is tomorrow's Fajr rather than a prayer still to come today. */
        val nextIsTomorrow: Boolean = false,
        val remaining: Duration? = null,
        val current: Prayer? = null,
        val currentAt: Instant? = null,
        /** Time since the current prayer's adhan. */
        val sinceCurrent: Duration? = null,
        /** Countdown to the iqama, while it is still ahead. */
        val untilIqama: Duration? = null,
        val iqamaAt: Instant? = null,
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
                alertSound = preferences.alertSound,
                preAdhanMinutes = preferences.preAdhanMinutes,
                adhkarReminders = preferences.adhkarReminders,
                highLatitudeRule = preferences.highLatitudeRule,
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
                adhkarReminderTimes = DailyAdhkar.entries.associateWith { it.remindAt(times) },
                hijriDate = Formatting.hijriDate(date),
                gregorianDate = Formatting.gregorianDate(date),
                notificationsEnabled = preferences.notificationsEnabled,
                notifiedPrayers = preferences.notifiedPrayers,
                alertSound = preferences.alertSound,
                preAdhanMinutes = preferences.preAdhanMinutes,
                adhkarReminders = preferences.adhkarReminders,
                highLatitudeRule = preferences.highLatitudeRule,
                iqamaMinutes = preferences.iqamaMinutes,
                // A polar day tomorrow is not a reason to fail today, so this is computed
                // separately and simply absent when it cannot be had.
                tomorrowFajrAt = runCatching {
                    PrayerTimes.calculate(
                        coordinates = place.coordinates,
                        date = date.plusDays(1),
                        parameters = preferences.calculationParameters(),
                    ).timeFor(Prayer.FAJR)
                }.getOrNull(),
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
                alertSound = preferences.alertSound,
                preAdhanMinutes = preferences.preAdhanMinutes,
                adhkarReminders = preferences.adhkarReminders,
                highLatitudeRule = preferences.highLatitudeRule,
                error = "الشمس لا تشرق ولا تغرب في هذا الموقع اليوم، فلا يمكن حساب المواقيت. " +
                    "اختر أقرب مدينة تحتها بخط عرض أدنى.",
            )
        }
    }

    private fun buildCountdown(state: UiState, now: Instant): Countdown {
        if (state.rows.isEmpty()) return Countdown()
        val next = state.rows.firstOrNull { it.at.isAfter(now) }
        val current = state.rows.lastOrNull { !it.at.isAfter(now) }

        // After Isha there is no later prayer today, and that is precisely the hour someone opens
        // the app to find out when Fajr is. Roll on to tomorrow's rather than showing nothing.
        val tomorrowFajr = state.tomorrowFajrAt?.takeIf { next == null && it.isAfter(now) }

        // Sunrise has no congregation and so no iqama; treating it like the others would put a
        // countdown on the screen for a prayer nobody is being called to.
        val iqamaMinutes = current
            ?.takeIf { it.prayer != Prayer.SUNRISE }
            ?.let { state.iqamaMinutes[it.prayer] ?: 0 }
            ?: 0
        val iqamaAt = current
            ?.takeIf { iqamaMinutes > 0 }
            ?.at?.plus(iqamaMinutes.toLong(), java.time.temporal.ChronoUnit.MINUTES)

        return Countdown(
            next = next?.prayer ?: tomorrowFajr?.let { Prayer.FAJR },
            nextAt = next?.at ?: tomorrowFajr,
            nextIsTomorrow = next == null && tomorrowFajr != null,
            remaining = (next?.at ?: tomorrowFajr)?.let { Duration.between(now, it) },
            current = current?.prayer,
            currentAt = current?.at,
            sinceCurrent = current?.let { Duration.between(it.at, now) },
            iqamaAt = iqamaAt,
            untilIqama = iqamaAt?.takeIf { it.isAfter(now) }?.let { Duration.between(now, it) },
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

    fun selectAlertSound(sound: AlertSound) {
        viewModelScope.launch { preferencesRepository.setAlertSound(sound) }
    }

    /** True while an audition is actually sounding, reported by the player rather than assumed. */
    val isPreviewingAlertSound: StateFlow<Boolean> = alertSoundPreview.isPlaying

    /** Plays the chosen sound so the user hears it now rather than at dawn. */
    fun previewAlertSound(sound: AlertSound) = alertSoundPreview.play(sound)

    fun stopAlertSoundPreview() = alertSoundPreview.stop()

    /**
     * Leaving the screen stops the audition.
     *
     * Two and a half minutes of adhan continuing after the user has walked away from the settings
     * they were adjusting is the app talking over them.
     */
    override fun onCleared() {
        alertSoundPreview.stop()
        super.onCleared()
    }

    /** Adds or removes one prayer from the alerting set, leaving the master switch untouched. */
    fun togglePrayerNotification(prayer: Prayer) {
        viewModelScope.launch {
            val current = uiState.value.notifiedPrayers
            val updated = if (prayer in current) current - prayer else current + prayer
            preferencesRepository.setNotifiedPrayers(updated)
        }
    }

    /** Null restores the rule recommended for the user's latitude. */
    fun selectHighLatitudeRule(rule: HighLatitudeRule?) {
        viewModelScope.launch { preferencesRepository.setHighLatitudeRule(rule) }
    }

    fun setPreAdhanMinutes(minutes: Int) {
        viewModelScope.launch { preferencesRepository.setPreAdhanMinutes(minutes) }
    }

    /** Turns one reminder on or off, leaving the others as they are. */
    fun setAdhkarReminder(kind: DailyAdhkar, enabled: Boolean) {
        viewModelScope.launch {
            val current = uiState.value.adhkarReminders
            preferencesRepository.setAdhkarReminders(if (enabled) current + kind else current - kind)
        }
    }

    fun setIqamaMinutes(prayer: Prayer, minutes: Int) {
        viewModelScope.launch { preferencesRepository.setIqamaMinutes(prayer, minutes) }
    }

    /**
     * The user refused the location permission.
     *
     * Said out loud, because the system stops saying it: the second refusal shows no dialog at all,
     * so without this the button simply absorbs the tap and the app looks broken.
     */
    fun reportLocationPermissionDenied() {
        _locationError.value = "لم يُمنح إذن الموقع، فلا يمكن تحديده تلقائيًا. " +
            "اختر مدينتك يدويًا، أو امنح الإذن من إعدادات التطبيق."
    }

    fun dismissLocationError() {
        _locationError.value = null
    }

    private companion object {
        const val TICK_MILLIS = 1_000L
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
