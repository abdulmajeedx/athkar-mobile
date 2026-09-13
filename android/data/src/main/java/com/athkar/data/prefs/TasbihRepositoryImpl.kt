package com.athkar.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.athkar.domain.Dhikr
import com.athkar.domain.TasbihRepository
import com.athkar.domain.TasbihState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.tasbihDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "tasbih",
)

/**
 * The tasbih on DataStore rather than in the encrypted database: it is written on every single tap,
 * and opening SQLCipher to record that someone said سبحان الله once more would be a strange price
 * to pay. Nothing here is private in any sense the database exists to protect.
 */
@Singleton
class TasbihRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : TasbihRepository {

    override fun observe(): Flow<TasbihState> = context.tasbihDataStore.data.map { prefs ->
        val dhikr = Dhikr.fromName(prefs[KEY_DHIKR])
        TasbihState(
            dhikr = dhikr,
            // A stored target of zero — however it got there — would make every tap complete a
            // round, so it falls back to the phrase's own.
            target = (prefs[KEY_TARGET] ?: dhikr.defaultTarget).coerceAtLeast(1),
            count = (prefs[KEY_COUNT] ?: 0).coerceAtLeast(0),
            rounds = (prefs[KEY_ROUNDS] ?: 0).coerceAtLeast(0),
        )
    }

    override suspend fun save(state: TasbihState) {
        context.tasbihDataStore.edit { prefs ->
            prefs[KEY_DHIKR] = state.dhikr.name
            prefs[KEY_TARGET] = state.target
            prefs[KEY_COUNT] = state.count
            prefs[KEY_ROUNDS] = state.rounds
        }
    }

    private companion object {
        val KEY_DHIKR = stringPreferencesKey("dhikr")
        val KEY_TARGET = intPreferencesKey("target")
        val KEY_COUNT = intPreferencesKey("count")
        val KEY_ROUNDS = intPreferencesKey("rounds")
    }
}
