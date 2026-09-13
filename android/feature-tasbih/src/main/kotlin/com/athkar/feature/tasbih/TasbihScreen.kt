package com.athkar.feature.tasbih

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.athkar.designsystem.LocalAthkarAccents
import com.athkar.designsystem.Sizing
import com.athkar.designsystem.Spacing
import com.athkar.domain.Dhikr
import com.athkar.domain.TasbihState

@Composable
fun TasbihRoute(viewModel: TasbihViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val completed by viewModel.roundJustCompleted.collectAsStateWithLifecycle()

    val haptics = LocalHapticFeedback.current
    // A different, heavier pulse when a round closes, so the hand knows without the eyes.
    LaunchedEffect(completed) {
        if (completed > 0) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    TasbihScreen(
        state = state,
        onCount = {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            viewModel.count()
        },
        onReset = viewModel::reset,
        onSelect = viewModel::select,
        onRetarget = viewModel::retarget,
    )
}

/**
 * The tasbih.
 *
 * Its own surface rather than the app's parchment: this is the one screen held in the dark, counted
 * on without being read, and a night-deep ground with a single lit ring is both easier on the eye
 * then and unmistakably a different tool from the pages behind it.
 *
 * The whole area above the controls counts. A tasbih is used without looking — the thumb should
 * find it anywhere, not hunt for a button.
 */
@Composable
private fun TasbihScreen(
    state: TasbihState,
    onCount: () -> Unit,
    onReset: () -> Unit,
    onSelect: (Dhikr) -> Unit,
    onRetarget: (Int) -> Unit,
) {
    val accents = LocalAthkarAccents.current

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(accents.nightGradientTop, accents.nightGradientBottom),
                    ),
                ),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // No ripple: a circle flashing under the thumb a hundred times is noise, and
                    // the ring and the number already answer every tap.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = "عُدّ",
                        onClick = onCount,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                CounterRing(state = state)
            }

            Controls(
                state = state,
                onReset = onReset,
                onSelect = onSelect,
                onRetarget = onRetarget,
            )
        }
    }
}

@Composable
private fun CounterRing(state: TasbihState) {
    val accents = LocalAthkarAccents.current
    // Eased rather than snapped: the ring is the only thing on screen that moves, and a step
    // change reads as a glitch where a sweep reads as progress.
    val progress by animateFloatAsState(
        targetValue = state.progress,
        animationSpec = tween(durationMillis = 220),
        label = "tasbih-progress",
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            state.dhikr.arabic,
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = Spacing.xl),
        )

        Spacer(Modifier.height(Spacing.xl))

        Box(contentAlignment = Alignment.Center) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth(0.72f)
                    .aspectRatio(1f),
            ) {
                val stroke = size.minDimension * 0.055f
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(
                    color = Color.White.copy(alpha = 0.14f),
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                drawArc(
                    color = accents.gold,
                    startAngle = -90f,
                    sweepAngle = 360f * progress.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "${state.count}",
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontFeatureSettings = "tnum",
                        fontWeight = FontWeight.Bold,
                    ),
                    color = Color.White,
                    // Spoken as it changes, so the count is available without sight.
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = "${state.count} من ${state.target}"
                    },
                )
                Text(
                    "من ${state.target}",
                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
        }

        Spacer(Modifier.height(Spacing.lg))

        Text(
            if (state.rounds > 0) "أتممت ${roundsLabel(state.rounds)}" else "اضغط في أي مكان للعدّ",
            style = MaterialTheme.typography.bodyMedium,
            color = if (state.rounds > 0) accents.gold else Color.White.copy(alpha = 0.6f),
        )
    }
}

/** Arabic counts its rounds in three forms; "أتممت 2 دورة" is not one of them. */
private fun roundsLabel(rounds: Int): String = when {
    rounds == 1 -> "دورة"
    rounds == 2 -> "دورتين"
    rounds in 3..10 -> "$rounds دورات"
    else -> "$rounds دورة"
}

@Composable
private fun Controls(
    state: TasbihState,
    onReset: () -> Unit,
    onSelect: (Dhikr) -> Unit,
    onRetarget: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.18f))
            .padding(vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        // Scrolled rather than wrapped: six phrases and five targets would otherwise push the ring
        // off a small screen, and the ring is the screen.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Dhikr.entries.forEach { dhikr ->
                Chip(dhikr.arabic, dhikr == state.dhikr) { onSelect(dhikr) }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                TasbihState.TARGETS.forEach { target ->
                    Chip("$target", target == state.target) { onRetarget(target) }
                }
            }
            IconButton(
                onClick = onReset,
                modifier = Modifier
                    .size(Sizing.touchTarget)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.10f)),
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "تصفير العدّ",
                    tint = Color.White,
                )
            }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val accents = LocalAthkarAccents.current
    if (selected) {
        Button(
            onClick = onClick,
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = accents.gold,
                contentColor = accents.onGold,
            ),
        ) { Text(label) }
    } else {
        OutlinedButton(
            onClick = onClick,
            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                contentColor = Color.White.copy(alpha = 0.85f),
            ),
        ) { Text(label) }
    }
}
