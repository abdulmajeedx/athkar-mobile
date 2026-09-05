package com.athkar.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.athkar.domain.ReadingPreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.readingDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "reading_preferences",
)

/**
 * On DataStore for the same reason the prayer settings are: it is read before the first frame of
 * the reader, and paying the SQLCipher open cost for one enum would only delay it.
 */
@Singleton
class ReadingPreferencesRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : ReadingPreferencesRepository {

    override fun observeReadingSizeName(): Flow<String?> =
        context.readingDataStore.data.map { it[KEY_READING_SIZE] }

    override suspend fun setReadingSizeName(name: String) {
        context.readingDataStore.edit { it[KEY_READING_SIZE] = name }
    }

    private companion object {
        val KEY_READING_SIZE = stringPreferencesKey("reading_size")
    }
}
