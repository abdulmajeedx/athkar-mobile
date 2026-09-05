package com.athkar.domain

import com.athkar.core.prayer.CalculationMethod
import com.athkar.core.prayer.Coordinates
import com.athkar.core.prayer.HighLatitudeRule
import com.athkar.core.prayer.Madhab
import com.athkar.core.prayer.Prayer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

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
 * What the alert sounds like when a prayer time arrives.
 *
 * @property label the Arabic name shown in settings.
 * @property description the one-line explanation shown under it.
 */
enum class AlertSound(val label: String, val description: String) {
    /** The call itself, played in full. */
    ADHAN("الأذان", "الأذان كاملًا، ويمكن إيقافه من الإشعار"),

    /** Whatever tone the device uses for its own alarms — short, and already familiar. */
    DEVICE_ALARM("نغمة المنبّه", "نغمة المنبّه المضبوطة في جهازك"),

    /** Notification and vibration only. */
    SILENT("صامت", "إشعار واهتزاز بلا صوت"),
    ;

    /**
     * The sound to actually use for [prayer].
     *
     * Sunrise is not prayed and is never called to, so it never gets the adhan however the setting
     * reads — the muezzin does not call at sunrise, and an app that does would be teaching the user
     * something false about their own religion. It still alerts, because the user asked it to; it
     * just uses the ordinary tone.
     */
    fun forPrayer(prayer: Prayer): AlertSound =
        if (this == ADHAN && prayer == Prayer.SUNRISE) DEVICE_ALARM else this

    companion object {
        val DEFAULT: AlertSound = ADHAN

        /**
         * A name written by another version of the app must not crash this one; an unrecognised
         * value falls back to the default exactly as an absent one does.
         */
        fun fromName(name: String?): AlertSound = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * Everything that changes a computed prayer time, plus the place it is computed for and which of
 * those times should raise an alert.
 *
 * @property notificationsEnabled the master switch. Off by default: an app that starts making noise
 *   at dawn without being asked is one the user uninstalls.
 * @property notifiedPrayers which times alert when [notificationsEnabled]. Sunrise is excluded by
 *   default — it ends Fajr rather than beginning a prayer.
 * @property alertSound what that alert sounds like. Only consulted when [notificationsEnabled]; the
 *   master switch is the one that decides whether the app makes a sound unasked.
 */
data class PrayerPreferences(
    val method: CalculationMethod = CalculationMethod.UMM_AL_QURA,
    val madhab: Madhab = Madhab.SHAFI,
    val highLatitudeRule: HighLatitudeRule? = null,
    val place: Place? = null,
    val notificationsEnabled: Boolean = false,
    val notifiedPrayers: Set<Prayer> = DEFAULT_NOTIFIED_PRAYERS,
    val alertSound: AlertSound = AlertSound.DEFAULT,
    val iqamaMinutes: Map<Prayer, Int> = DEFAULT_IQAMA_MINUTES,
) {
    /** Minutes between the adhan and the iqama for [prayer]; zero when the prayer has none. */
    fun iqamaFor(prayer: Prayer): Int = iqamaMinutes[prayer] ?: 0

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

        /**
         * Customary gaps between the adhan and the iqama. There is no calculating these — they are
         * a decision each mosque makes — so these are the common defaults and the user is expected
         * to correct them to whatever their own mosque does. Maghrib is the short one everywhere.
         */
        val DEFAULT_IQAMA_MINUTES: Map<Prayer, Int> = mapOf(
            Prayer.FAJR to 20,
            Prayer.DHUHR to 15,
            Prayer.ASR to 15,
            Prayer.MAGHRIB to 5,
            Prayer.ISHA to 15,
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
    suspend fun setAlertSound(sound: AlertSound)
    suspend fun setIqamaMinutes(prayer: Prayer, minutes: Int)
}

/** Port for a one-shot device location fix. Returns null when unavailable or not permitted. */
interface DeviceLocationSource {
    suspend fun currentPlace(): Place?
}

/**
 * Port for auditioning an alert sound from the settings screen.
 *
 * A sound chosen from a list is a sound the user first hears at four in the morning. Being able to
 * play it on the spot is what turns the choice into an informed one.
 *
 * Implemented in `:app`, which owns the recording and the playback service.
 */
interface AlertSoundPreview {
    /**
     * True while an audition is actually sounding.
     *
     * Reported rather than assumed: the audition can end without being stopped — the recording
     * finishes, a call takes the audio away, Do Not Disturb silences it — and a button that tracked
     * only its own taps would sit there offering to stop something that stopped by itself.
     */
    val isPlaying: StateFlow<Boolean>

    /** Plays [sound], replacing anything already playing. [AlertSound.SILENT] plays nothing. */
    fun play(sound: AlertSound)

    /** Stops what [play] started. Safe to call when nothing is playing. */
    fun stop()
}
