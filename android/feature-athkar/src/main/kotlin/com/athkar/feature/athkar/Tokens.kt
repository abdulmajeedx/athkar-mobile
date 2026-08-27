package com.athkar.feature.athkar

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Design tokens (delivery brief): 8 spacing levels as multiples of 4, 5 text sizes, 3 elevation
 * levels. Screen composables MUST reference these tokens and never write literal colors or
 * dimensions — enforced by code review and (in a stricter setup) a lint rule.
 */
object Tokens {
    // Spacing: multiples of 4
    val sp0 = 0.dp
    val sp1 = 4.dp
    val sp2 = 8.dp
    val sp3 = 12.dp
    val sp4 = 16.dp
    val sp5 = 20.dp
    val sp6 = 24.dp
    val sp8 = 32.dp

    // Text sizes (5 scale steps)
    val cap = 12.sp
    val body = 16.sp
    val title = 20.sp
    val headline = 28.sp
    val display = 36.sp

    // Elevation (3 levels)
    val elev0 = 0.dp
    val elev1 = 3.dp
    val elev3 = 8.dp

    // Touch targets (Android minimum 48dp, 8dp separation)
    val touchTarget = 48.dp
    val targetGap = 8.dp

    // Color tokens (semantic, theme-sensitive via MaterialTheme where available)
    val primary = Color(0xFF0B6E4F)
    val onPrimary = Color(0xFFFFFFFF)
    val background = Color(0xFFFAF9F6)
    val surface = Color(0xFFFFFFFF)
    val textPrimary = Color(0xFF1A1A1A)
    val textSecondary = Color(0xFF6B7280)
    val error = Color(0xFFB3261E)
    val offlineAmber = Color(0xFFB45309)
}

/** Minimal theme hook so screens stay token-driven. */
@Composable
fun AhkarTokensTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}
