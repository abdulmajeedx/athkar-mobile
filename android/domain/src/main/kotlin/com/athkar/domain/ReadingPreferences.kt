package com.athkar.domain

import kotlinx.coroutines.flow.Flow

/**
 * How the adhkar text is set, remembered between sittings.
 *
 * The size is carried as the plain name of a design-system enum rather than the enum itself: the
 * scale is a typographic decision that belongs to `:designsystem`, and this module may not import
 * anything Android. An unrecognised name reads as absent, so a value written by another version of
 * the app cannot break this one.
 */
interface ReadingPreferencesRepository {
    fun observeReadingSizeName(): Flow<String?>
    suspend fun setReadingSizeName(name: String)
}
