package com.athkar.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.athkar.domain.AppearancePreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// The file keeps its old name: it already holds the reading size on every installed copy of the
// app, and renaming the store would silently reset that for everyone who has one.
private val Context.appearanceDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "reading_preferences",
)

/**
 * On DataStore for the same reason the prayer settings are: it is read before the first frame — the
 * theme decides the colour of that frame — and paying the SQLCipher open cost for two enums would
 * only delay it, with a flash of the wrong palette as the price.
 */
@Singleton
class AppearancePreferencesRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : AppearancePreferencesRepository {

    override fun observeReadingSizeName(): Flow<String?> =
        context.appearanceDataStore.data.map { it[KEY_READING_SIZE] }

    override suspend fun setReadingSizeName(name: String) {
        context.appearanceDataStore.edit { it[KEY_READING_SIZE] = name }
    }

    override fun observeThemeName(): Flow<String?> =
        context.appearanceDataStore.data.map { it[KEY_THEME] }

    override suspend fun setThemeName(name: String) {
        context.appearanceDataStore.edit { it[KEY_THEME] = name }
    }

    private companion object {
        val KEY_READING_SIZE = stringPreferencesKey("reading_size")
        val KEY_THEME = stringPreferencesKey("theme")
    }
}
