package com.athkar.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.athkar.core.prayer.CalculationMethod
import com.athkar.core.prayer.Coordinates
import com.athkar.core.prayer.HighLatitudeRule
import com.athkar.core.prayer.Madhab
import com.athkar.core.prayer.Prayer
import com.athkar.domain.AlertSound
import com.athkar.domain.Place
import com.athkar.domain.PrayerPreferences
import com.athkar.domain.PrayerPreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.prayerDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "prayer_preferences",
)

/**
 * Prayer settings on DataStore rather than in the encrypted database: they are needed before the
 * first frame (the times screen cannot render without them) and contain nothing sensitive, so
 * paying the SQLCipher open cost for them would only delay startup.
 */
@Singleton
class PrayerPreferencesRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : PrayerPreferencesRepository {

    override fun observe(): Flow<PrayerPreferences> = context.prayerDataStore.data.map { prefs ->
        PrayerPreferences(
            method = prefs[KEY_METHOD].toMethod(),
            madhab = prefs[KEY_MADHAB].toMadhab(),
            highLatitudeRule = prefs[KEY_HIGH_LATITUDE].toHighLatitudeRule(),
            place = prefs.toPlace(),
            notificationsEnabled = prefs[KEY_NOTIFICATIONS_ENABLED] ?: false,
            notifiedPrayers = prefs[KEY_NOTIFIED_PRAYERS].toPrayers(),
            alertSound = AlertSound.fromName(prefs[KEY_ALERT_SOUND]),
            preAdhanMinutes = (prefs[KEY_PRE_ADHAN] ?: 0).coerceIn(0, MAX_PRE_ADHAN_MINUTES),
            iqamaMinutes = prefs.toIqamaMinutes(),
        )
    }

    override suspend fun setMethod(method: CalculationMethod) {
        context.prayerDataStore.edit { it[KEY_METHOD] = method.name }
    }

    override suspend fun setMadhab(madhab: Madhab) {
        context.prayerDataStore.edit { it[KEY_MADHAB] = madhab.name }
    }

    override suspend fun setHighLatitudeRule(rule: HighLatitudeRule?) {
        context.prayerDataStore.edit { prefs ->
            if (rule == null) prefs.remove(KEY_HIGH_LATITUDE) else prefs[KEY_HIGH_LATITUDE] = rule.name
        }
    }

    override suspend fun setPlace(place: Place) {
        context.prayerDataStore.edit { prefs ->
            prefs[KEY_LATITUDE] = place.coordinates.latitude
            prefs[KEY_LONGITUDE] = place.coordinates.longitude
            prefs[KEY_PLACE_NAME] = place.name
            prefs[KEY_PLACE_AUTOMATIC] = place.isAutomatic
        }
    }

    override suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.prayerDataStore.edit { it[KEY_NOTIFICATIONS_ENABLED] = enabled }
    }

    override suspend fun setNotifiedPrayers(prayers: Set<Prayer>) {
        context.prayerDataStore.edit { prefs ->
            // An empty set is stored as a marker rather than removed, so "none selected" survives a
            // restart instead of reverting to the default five.
            prefs[KEY_NOTIFIED_PRAYERS] = if (prayers.isEmpty()) {
                setOf(NONE_SELECTED)
            } else {
                prayers.map { it.name }.toSet()
            }
        }
    }

    override suspend fun setAlertSound(sound: AlertSound) {
        context.prayerDataStore.edit { it[KEY_ALERT_SOUND] = sound.name }
    }

    override suspend fun setPreAdhanMinutes(minutes: Int) {
        context.prayerDataStore.edit { it[KEY_PRE_ADHAN] = minutes.coerceIn(0, MAX_PRE_ADHAN_MINUTES) }
    }

    override suspend fun setIqamaMinutes(prayer: Prayer, minutes: Int) {
        context.prayerDataStore.edit { prefs ->
            prefs[iqamaKey(prayer)] = minutes.coerceIn(0, MAX_IQAMA_MINUTES)
        }
    }

    // A value written by another version of the app must not crash this one; an unrecognised name
    // falls back to the default exactly as an absent one does.
    private fun String?.toMethod(): CalculationMethod =
        CalculationMethod.entries.firstOrNull { it.name == this } ?: CalculationMethod.UMM_AL_QURA

    private fun String?.toMadhab(): Madhab =
        Madhab.entries.firstOrNull { it.name == this } ?: Madhab.SHAFI

    private fun String?.toHighLatitudeRule(): HighLatitudeRule? =
        HighLatitudeRule.entries.firstOrNull { it.name == this }

    private fun Set<String>?.toPrayers(): Set<Prayer> = when {
        this == null -> PrayerPreferences.DEFAULT_NOTIFIED_PRAYERS
        contains(NONE_SELECTED) -> emptySet()
        else -> mapNotNull { name -> Prayer.entries.firstOrNull { it.name == name } }.toSet()
    }

    /** One key per prayer rather than an encoded map: a single malformed entry cannot lose the rest. */
    private fun iqamaKey(prayer: Prayer) = intPreferencesKey("iqama_${prayer.name}")

    private fun Preferences.toIqamaMinutes(): Map<Prayer, Int> =
        Prayer.entries.mapNotNull { prayer ->
            val stored = this[iqamaKey(prayer)] ?: PrayerPreferences.DEFAULT_IQAMA_MINUTES[prayer]
            stored?.let { prayer to it.coerceIn(0, MAX_IQAMA_MINUTES) }
        }.toMap()

    private fun Preferences.toPlace(): Place? {
        val latitude = this[KEY_LATITUDE] ?: return null
        val longitude = this[KEY_LONGITUDE] ?: return null
        val name = this[KEY_PLACE_NAME] ?: return null
        // A stored pair that is out of range would throw inside Coordinates; treat it as absent so
        // the app falls back to the picker instead of failing to start.
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        return Place(
            coordinates = Coordinates(latitude, longitude),
            name = name,
            isAutomatic = this[KEY_PLACE_AUTOMATIC] ?: false,
        )
    }

    private companion object {
        val KEY_METHOD = stringPreferencesKey("method")
        val KEY_MADHAB = stringPreferencesKey("madhab")
        val KEY_HIGH_LATITUDE = stringPreferencesKey("high_latitude_rule")
        val KEY_LATITUDE = doublePreferencesKey("place_latitude")
        val KEY_LONGITUDE = doublePreferencesKey("place_longitude")
        val KEY_PLACE_NAME = stringPreferencesKey("place_name")
        val KEY_PLACE_AUTOMATIC = booleanPreferencesKey("place_automatic")
        val KEY_NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val KEY_NOTIFIED_PRAYERS = stringSetPreferencesKey("notified_prayers")
        val KEY_ALERT_SOUND = stringPreferencesKey("alert_sound")
        val KEY_PRE_ADHAN = intPreferencesKey("pre_adhan_minutes")
        const val NONE_SELECTED = "__none__"

        /** An hour is already implausible; the cap only keeps a bad write from rendering absurdly. */
        const val MAX_IQAMA_MINUTES = 60

        /** Beyond this the warning would land before the previous prayer in a short winter day. */
        const val MAX_PRE_ADHAN_MINUTES = 60
    }
}
