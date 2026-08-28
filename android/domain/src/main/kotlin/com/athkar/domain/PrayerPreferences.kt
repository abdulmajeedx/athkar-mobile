package com.athkar.domain

import com.athkar.core.prayer.CalculationMethod
import com.athkar.core.prayer.Coordinates
import com.athkar.core.prayer.HighLatitudeRule
import com.athkar.core.prayer.Madhab
import com.athkar.core.prayer.Prayer
import kotlinx.coroutines.flow.Flow

/**
 * A place the user computes times for, and how it was obtained.
 *
 * @property name what to show in the header — a city, or a coordinate pair when nothing better is
 *   known. Reverse geocoding is best-effort and offline devices often have none.
 * @property isAutomatic true when it came from the device's location, false when picked from the
 *   city list. Manual choices must survive a failed location fix.
 */
data class Place(
    val coordinates: Coordinates,
    val name: String,
    val isAutomatic: Boolean,
)

/**
 * Everything that changes a computed prayer time, plus the place it is computed for and which of
 * those times should raise an alert.
 *
 * @property notificationsEnabled the master switch. Off by default: an app that starts making noise
 *   at dawn without being asked is one the user uninstalls.
 * @property notifiedPrayers which times alert when [notificationsEnabled]. Sunrise is excluded by
 *   default — it ends Fajr rather than beginning a prayer.
 */
data class PrayerPreferences(
    val method: CalculationMethod = CalculationMethod.UMM_AL_QURA,
    val madhab: Madhab = Madhab.SHAFI,
    val highLatitudeRule: HighLatitudeRule? = null,
    val place: Place? = null,
    val notificationsEnabled: Boolean = false,
    val notifiedPrayers: Set<Prayer> = DEFAULT_NOTIFIED_PRAYERS,
) {
    /**
     * The parameter set to hand the calculator: the method's published values, with the user's
     * madhab and — unless they chose one — the high-latitude rule appropriate to their latitude.
     */
    fun calculationParameters() = method.parameters().copy(
        madhab = madhab,
        highLatitudeRule = highLatitudeRule
            ?: place?.let { HighLatitudeRule.recommendedFor(it.coordinates) }
            ?: HighLatitudeRule.MIDDLE_OF_THE_NIGHT,
    )

    companion object {
        val DEFAULT_NOTIFIED_PRAYERS: Set<Prayer> = setOf(
            Prayer.FAJR, Prayer.DHUHR, Prayer.ASR, Prayer.MAGHRIB, Prayer.ISHA,
        )
    }
}

/** Port for the persisted prayer settings. Implemented over DataStore in :data. */
interface PrayerPreferencesRepository {
    fun observe(): Flow<PrayerPreferences>
    suspend fun setMethod(method: CalculationMethod)
    suspend fun setMadhab(madhab: Madhab)
    suspend fun setHighLatitudeRule(rule: HighLatitudeRule?)
    suspend fun setPlace(place: Place)
    suspend fun setNotificationsEnabled(enabled: Boolean)
    suspend fun setNotifiedPrayers(prayers: Set<Prayer>)
}

/** Port for a one-shot device location fix. Returns null when unavailable or not permitted. */
interface DeviceLocationSource {
    suspend fun currentPlace(): Place?
}
