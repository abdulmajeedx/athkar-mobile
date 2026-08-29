package com.athkar.feature.prayertimes

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.athkar.designsystem.LocalAthkarAccents
import com.athkar.designsystem.Spacing
import com.athkar.feature.prayertimes.QiblaViewModel.UiState
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Within this many degrees the phone is treated as facing the qibla. */
private const val ALIGNMENT_TOLERANCE_DEGREES = 4f

@Composable
fun QiblaRoute(viewModel: QiblaViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    QiblaScreen(state)
}

@Composable
private fun QiblaScreen(state: UiState) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            state.isLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator()
            }

            state.needsPlace -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(Spacing.xxl),
                Alignment.Center,
            ) {
                Text(
                    "حدّد موقعك من شاشة «الصلاة» أولًا حتى يُحسب اتجاه القبلة.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            else -> QiblaContent(state)
        }
    }
}

@Composable
private fun QiblaContent(state: UiState) {
    val qibla = state.qiblaBearing ?: return
    val heading = state.headingDegrees

    // The dial turns to keep true north pointing north, so the target sits at the qibla bearing
    // within the dial and the fixed marker at the top of the screen reads the device's own heading.
    var unwrappedRotation by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(heading) {
        if (heading == null) return@LaunchedEffect
        val target = -heading
        // Accumulate the shortest signed step so the needle never spins the long way round 0/360.
        val delta = (((target - unwrappedRotation) % 360f) + 540f) % 360f - 180f
        unwrappedRotation += delta
    }
    val rotation by animateFloatAsState(
        targetValue = unwrappedRotation,
        animationSpec = tween(durationMillis = 180),
        label = "compass-rotation",
    )

    val offAngle = heading?.let { shortestDelta(qibla.toFloat(), it) }
    val isAligned = offAngle != null && abs(offAngle) <= ALIGNMENT_TOLERANCE_DEGREES

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Spacer(Modifier.height(Spacing.sm))
        Text(
            state.place?.name.orEmpty(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        CompassDial(
            rotationDegrees = if (heading == null) 0f else rotation,
            qiblaBearing = qibla.toFloat(),
            offAngle = offAngle,
            isAligned = isAligned,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        )

        Card(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(
                containerColor = if (isAligned) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    Formatting.bearing(qibla),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "اتجاه القبلة من الشمال الحقيقي",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.distanceKm?.let {
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        "المسافة إلى الكعبة: ${Formatting.distanceKm(it)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(Modifier.height(Spacing.md))
                Text(
                    text = when {
                        !state.hasCompass ->
                            "لا يوجد حسّاس بوصلة في هذا الجهاز — استعمل الزاوية أعلاه مع بوصلة أخرى."
                        heading == null -> "جارٍ قراءة البوصلة…"
                        isAligned -> "أنت تواجه القبلة"
                        else -> "أدِر الجهاز حتى تنطبق العلامة الذهبية على المؤشر"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isAligned) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    textAlign = TextAlign.Center,
                )
                if (state.needsCalibration && state.hasCompass) {
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        "دقّة البوصلة منخفضة — حرّك الجهاز على شكل الرقم 8 لمعايرته.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun CompassDial(
    rotationDegrees: Float,
    qiblaBearing: Float,
    offAngle: Float?,
    isAligned: Boolean,
    modifier: Modifier = Modifier,
) {
    val accents = LocalAthkarAccents.current
    val ringColor = MaterialTheme.colorScheme.outlineVariant
    val tickColor = MaterialTheme.colorScheme.outline
    val cardinalColor = MaterialTheme.colorScheme.onSurfaceVariant
    val northColor = MaterialTheme.colorScheme.error
    val pointerColor = MaterialTheme.colorScheme.primary
    val targetColor = if (isAligned) accents.gold else MaterialTheme.colorScheme.secondary
    val faceColor = MaterialTheme.colorScheme.surface
    val textMeasurer = rememberTextMeasurer()

    val cardinalStyle = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold, color = cardinalColor)
    val northStyle = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold, color = northColor)
    val degreeStyle = TextStyle(fontSize = 10.sp, color = cardinalColor.copy(alpha = 0.7f))

    Canvas(modifier = modifier) {
        val radius = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        val ringRadius = radius * 0.84f

        // A filled face lifts the dial off the page and gives the ticks something to sit on; a bare
        // outline reads as a diagram rather than an instrument.
        drawCircle(color = faceColor, radius = ringRadius + radius * 0.06f, center = center)
        drawCircle(
            color = ringColor,
            radius = ringRadius,
            center = center,
            style = Stroke(width = 2.dp.toPx()),
        )

        // The arc from where the phone points to where the qibla is: the size of the error, shown
        // rather than left to be inferred from two marks.
        if (offAngle != null && !isAligned) {
            val sweep = offAngle
            drawArc(
                color = targetColor.copy(alpha = 0.25f),
                startAngle = -90f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(center.x - ringRadius, center.y - ringRadius),
                size = Size(ringRadius * 2, ringRadius * 2),
                style = Stroke(width = radius * 0.09f),
            )
        }

        drawPointer(center = center, radius = radius, color = pointerColor)

        rotate(degrees = rotationDegrees, pivot = center) {
            repeat(72) { index ->
                val angle = index * 5f
                val isMajor = index % 18 == 0
                val isMinor = index % 6 == 0
                if (!isMajor && !isMinor) return@repeat
                val length = if (isMajor) radius * 0.11f else radius * 0.05f
                drawLine(
                    color = if (isMajor) cardinalColor else tickColor.copy(alpha = 0.6f),
                    start = center.polar(angle, ringRadius - length),
                    end = center.polar(angle, ringRadius),
                    strokeWidth = if (isMajor) 3.dp.toPx() else 1.5f.dp.toPx(),
                )
            }

            // Degree labels every 30 degrees turn the dial into something readable, not decorative.
            for (angle in 30 until 360 step 30) {
                if (angle % 90 == 0) continue
                val layout = textMeasurer.measure("$angle", degreeStyle)
                val position = center.polar(angle.toFloat(), ringRadius - radius * 0.18f)
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(
                        position.x - layout.size.width / 2f,
                        position.y - layout.size.height / 2f,
                    ),
                )
            }

            listOf(0f to "ش", 90f to "ق", 180f to "ج", 270f to "غ").forEach { (angle, label) ->
                // North is the reference every other reading hangs off, so it is the one that is
                // coloured rather than merely bold.
                val layout = textMeasurer.measure(label, if (angle == 0f) northStyle else cardinalStyle)
                val position = center.polar(angle, ringRadius - radius * 0.19f)
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(
                        position.x - layout.size.width / 2f,
                        position.y - layout.size.height / 2f,
                    ),
                )
            }

            drawQiblaMarker(
                center = center,
                radius = ringRadius,
                bearing = qiblaBearing,
                color = targetColor,
                aligned = isAligned,
            )
        }
    }
}

/** The screen-fixed reference mark the user aligns the qibla marker with. */
private fun DrawScope.drawPointer(center: Offset, radius: Float, color: Color) {
    val top = Offset(center.x, center.y - radius * 0.96f)
    drawLine(
        color = color,
        start = top,
        end = Offset(center.x, center.y - radius * 0.72f),
        strokeWidth = 4.dp.toPx(),
    )
    drawCircle(color = color, radius = 4.dp.toPx(), center = center)
}

/**
 * The direction of the Kaaba: a stem out to the rim and a solid block at its end, with a halo once
 * the phone is pointing at it. A plain dot reads as one more tick among seventy-two; a block that
 * grows a ring when you arrive is legible at a glance and while moving.
 */
private fun DrawScope.drawQiblaMarker(
    center: Offset,
    radius: Float,
    bearing: Float,
    color: Color,
    aligned: Boolean,
) {
    val tip = center.polar(bearing, radius * 0.90f)
    drawLine(
        color = color,
        start = center.polar(bearing, radius * 0.22f),
        end = center.polar(bearing, radius * 0.80f),
        strokeWidth = 5.dp.toPx(),
    )
    if (aligned) {
        drawCircle(color = color.copy(alpha = 0.25f), radius = 18.dp.toPx(), center = tip)
    }
    val block = 11.dp.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(tip.x - block, tip.y - block),
        size = Size(block * 2, block * 2),
        cornerRadius = CornerRadius(3.dp.toPx()),
    )
}

/**
 * Point at [degrees] clockwise from screen-up, [distance] from this centre — the compass convention,
 * which is 90 degrees off the mathematical one and clockwise rather than anticlockwise.
 */
private fun Offset.polar(degrees: Float, distance: Float): Offset {
    val radians = Math.toRadians((degrees - 90f).toDouble())
    return Offset(
        x = x + (distance * cos(radians)).toFloat(),
        y = y + (distance * sin(radians)).toFloat(),
    )
}

/** Signed difference from [from] to [to], mapped onto (-180, 180]. */
private fun shortestDelta(to: Float, from: Float): Float = (((to - from) % 360f) + 540f) % 360f - 180f
