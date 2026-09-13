package com.athkar.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Both appearance settings are stored as plain names, so both have to survive reading back a name
 * this version has never heard of — the one written by a newer build after an install is rolled
 * back, which is a downgrade, not a corruption, and must not be met with a crash on launch.
 */
class AppearanceChoiceTest {

    @Test
    fun `a stored theme round-trips`() {
        for (theme in AppTheme.entries) assertEquals(theme, AppTheme.fromName(theme.name))
    }

    @Test
    fun `an absent or unknown theme falls back to following the time`() {
        assertEquals(AppTheme.BY_TIME, AppTheme.fromName(null))
        assertEquals(AppTheme.BY_TIME, AppTheme.fromName("SEPIA_FROM_A_LATER_VERSION"))
        assertEquals(AppTheme.BY_TIME, AppTheme.DEFAULT)
    }

    @Test
    fun `a stored reading size round-trips`() {
        for (size in ReadingSize.entries) assertEquals(size, ReadingSize.fromName(size.name))
    }

    @Test
    fun `an absent or unknown reading size falls back to the middle one`() {
        assertEquals(ReadingSize.MEDIUM, ReadingSize.fromName(null))
        assertEquals(ReadingSize.MEDIUM, ReadingSize.fromName("ENORMOUS"))
    }

    @Test
    fun `every theme and size can be shown to the user`() {
        for (theme in AppTheme.entries) {
            assertTrue(theme.label.isNotBlank(), "${theme.name} has no label")
            assertTrue(theme.description.isNotBlank(), "${theme.name} explains nothing")
        }
        for (size in ReadingSize.entries) {
            assertTrue(size.label.isNotBlank(), "${size.name} has no label")
            assertTrue(size.lineHeightSp > size.fontSp, "${size.name} would collide between lines")
        }
    }
}
