package com.athkar.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.athkar.core.prayer.Prayer
import com.athkar.core.prayer.PrayerTimes
import com.athkar.designsystem.AppTheme
import com.athkar.designsystem.ReadingSize
import com.athkar.designsystem.SkyPhase
import com.athkar.domain.AppearancePreferencesRepository
import com.athkar.domain.PrayerPreferences
import com.athkar.domain.PrayerPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The colour the whole app is wearing, and the settings that decide it.
 *
 * This lives above the tabs rather than inside one because the answer has to be the same on all of
 * them: the hour is a property of the app, not of the prayer screen that happens to know the times.
 */
@HiltViewModel
class AppearanceViewModel @Inject constructor(
    private val appearance: AppearancePreferencesRepository,
    prayerPreferences: PrayerPreferencesRepository,
) : ViewModel() {

    data class Chrome(
        val theme: AppTheme = AppTheme.DEFAULT,
        val sky: SkyPhase = SkyPhase.NIGHT,
        val readingSize: ReadingSize = ReadingSize.DEFAULT,
    )

    private val zone: ZoneId get() = ZoneId.systemDefault()

    // Once a minute, not once a second: the only thing downstream of this is which of seven skies
    // the app is painted in, and no prayer arrives inside a minute of the last check.
    private val ticker = flow {
        while (true) {
            emit(Instant.now())
            delay(SKY_TICK_MILLIS)
        }
    }

    val chrome: StateFlow<Chrome> = combine(
        appearance.observeThemeName(),
        appearance.observeReadingSizeName(),
        prayerPreferences.observe(),
        ticker,
    ) { themeName, sizeName, preferences, now ->
        Chrome(
            theme = AppTheme.fromName(themeName),
            sky = skyAt(preferences, now),
            readingSize = ReadingSize.fromName(sizeName),
        )
    }
        // Without this the whole tree recomposes every minute to be told the sky has not moved.
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), Chrome())

    fun setTheme(theme: AppTheme) {
        viewModelScope.launch { appearance.setThemeName(theme.name) }
    }

    fun setReadingSize(size: ReadingSize) {
        viewModelScope.launch { appearance.setReadingSizeName(size.name) }
    }

    /**
     * Which sky it is here, now.
     *
     * From the user's own prayer times when a place is known, because that is the whole point — the
     * app turns to its evening colours when Maghrib arrives where the user is standing, not at a
     * clock hour that means sunset in one country and full daylight in another. Before a place is
     * chosen, and if the day cannot be computed at all, it falls back to the clock: a rough sky is
     * still a truer picture than one fixed colour.
     */
    private fun skyAt(preferences: PrayerPreferences, now: Instant): SkyPhase {
        val zone = zone
        val place = preferences.place ?: return SkyPhase.forHour(now.atZone(zone).hour)
        val times = runCatching {
            PrayerTimes.calculate(
                coordinates = place.coordinates,
                date = LocalDate.now(zone),
                parameters = preferences.calculationParameters(),
            )
        }.getOrNull() ?: return SkyPhase.forHour(now.atZone(zone).hour)

        val current = Prayer.entries.lastOrNull { !times.timeFor(it).isAfter(now) }
        if (current != null) return SkyPhase.forPrayerOrdinal(current.ordinal)

        // Before today's Fajr the standing prayer is yesterday's Isha, so it is still night — until
        // the hour before dawn, which has a sky of its own and is when this app is most often open.
        val untilFajr = Duration.between(now, times.timeFor(Prayer.FAJR))
        return if (untilFajr <= PRE_DAWN) SkyPhase.NIGHT_END else SkyPhase.NIGHT
    }

    private companion object {
        const val SKY_TICK_MILLIS = 60_000L
        const val STOP_TIMEOUT_MILLIS = 5_000L
        val PRE_DAWN: Duration = Duration.ofMinutes(90)
    }
}
