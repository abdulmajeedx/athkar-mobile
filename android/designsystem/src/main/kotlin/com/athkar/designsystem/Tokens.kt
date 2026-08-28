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
