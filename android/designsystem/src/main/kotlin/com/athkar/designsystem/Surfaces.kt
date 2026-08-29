package com.athkar.designsystem

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource

/**
 * A coloured surface with the girih pattern laid over it.
 *
 * The pattern is a tinted overlay rather than a picture: a photograph would fight the text on top of
 * it, need a scrim to stay readable, and date the app the moment tastes move. Geometry at low
 * opacity gives the surface depth and holds still under whatever sits on it.
 */
@Composable
fun PatternedSurface(
    sky: SkyPhase,
    modifier: Modifier = Modifier,
    shape: Shape? = null,
    patternAlpha: Float = 0.10f,
    content: @Composable BoxScope.() -> Unit,
) {
    val shaped = if (shape != null) modifier.clip(shape) else modifier
    Box(
        modifier = shaped.background(
            Brush.verticalGradient(listOf(sky.top, sky.bottom)),
        ),
    ) {
        Image(
            painter = painterResource(id = R.drawable.pattern_girih),
            contentDescription = null,
            // Cropping rather than fitting keeps the tile at its drawn scale, so the stars stay the
            // same size whatever the surface they cover.
            contentScale = ContentScale.Crop,
            colorFilter = ColorFilter.tint(sky.accent.copy(alpha = patternAlpha)),
            // matchParentSize, not fillMaxSize: fillMaxSize takes the largest height the parent
            // offers and *participates in measuring it*, so the surface grew to the full screen and
            // pushed everything below it out of view. matchParentSize takes the size the other
            // children have already settled on.
            modifier = Modifier.matchParentSize(),
        )
        content()
    }
}

/** A hairline of the sky's accent — used to mark the one row on a screen that matters. */
@Composable
fun accentEdge(sky: SkyPhase, alpha: Float = 0.55f): Color = sky.accent.copy(alpha = alpha)
