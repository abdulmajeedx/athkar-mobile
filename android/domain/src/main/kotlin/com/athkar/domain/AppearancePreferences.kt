package com.athkar.domain

import kotlinx.coroutines.flow.Flow

/**
 * How the app looks, remembered between sittings: the size the adhkar are set at, and the theme.
 *
 * Both are carried as the plain name of a design-system enum rather than the enum itself: the type
 * scale and the palette are typographic decisions that belong to `:designsystem`, and this module
 * may not import anything Android. An unrecognised name reads as absent, so a value written by
 * another version of the app cannot break this one.
 */
interface AppearancePreferencesRepository {
    fun observeReadingSizeName(): Flow<String?>
    suspend fun setReadingSizeName(name: String)

    fun observeThemeName(): Flow<String?>
    suspend fun setThemeName(name: String)
}
