package com.athkar.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Spacing, elevation and hit-target scale. Screens reference these instead of literals so the rhythm
 * stays consistent across three features written at different times.
 */
object Spacing {
    val none = 0.dp
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
    val huge = 48.dp
}

object Elevation {
    val flat = 0.dp
    val raised = 2.dp
    val floating = 8.dp
}

object Sizing {
    /** Android's minimum accessible touch target. */
    val touchTarget = 48.dp
    val iconSm = 18.dp
    val iconMd = 24.dp
    val iconLg = 32.dp
    val hairline = 1.dp
}

/** Generous corner radii — the rounded geometry reads as calm rather than utilitarian. */
val AthkarShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)


/**
 * Reading size for the adhkar text.
 *
 * A fixed size cannot serve both a reader holding the phone at arm's length after Fajr and one
 * reading closely at night, and Arabic with full tashkeel loses its marks first as text shrinks.
 * The leading scales with the size rather than staying fixed, because the marks sit above and below
 * the baseline and it is the gap between lines, not the glyph height, that decides legibility.
 */
enum class ReadingSize(val label: String, val fontSp: Int, val lineHeightSp: Int) {
    SMALL("صغير", 17, 34),
    MEDIUM("متوسط", 20, 42),
    LARGE("كبير", 24, 50),
    HUGE("كبير جدًا", 28, 58),
    ;

    companion object {
        val DEFAULT = MEDIUM

        /** A size written by a later version of the app reads as absent rather than as a crash. */
        fun fromName(name: String?): ReadingSize = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
