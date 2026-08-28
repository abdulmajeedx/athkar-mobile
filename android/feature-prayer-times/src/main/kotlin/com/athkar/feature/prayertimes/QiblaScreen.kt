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
    isAligned: Boolean,
    modifier: Modifier = Modifier,
) {
    val accents = LocalAthkarAccents.current
    val ringColor = MaterialTheme.colorScheme.outlineVariant
    val tickColor = MaterialTheme.colorScheme.outline
    val cardinalColor = MaterialTheme.colorScheme.onSurfaceVariant
    val pointerColor = MaterialTheme.colorScheme.primary
    val targetColor = if (isAligned) accents.gold else MaterialTheme.colorScheme.secondary
    val textMeasurer = rememberTextMeasurer()
    val cardinalStyle = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        color = cardinalColor,
    )

    Canvas(modifier = modifier) {
        val radius = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        val ringRadius = radius * 0.86f

        drawCircle(color = ringColor, radius = ringRadius, center = center, style = Stroke(width = 2.dp.toPx()))

        // Fixed pointer at the top: the direction the phone itself is facing.
        drawPointer(center = center, radius = radius, color = pointerColor)

        rotate(degrees = rotationDegrees, pivot = center) {
            repeat(72) { index ->
                val angle = index * 5f
                val isMajor = index % 18 == 0
                val isMinor = index % 6 == 0
                if (!isMajor && !isMinor) return@repeat
                val length = if (isMajor) radius * 0.10f else radius * 0.05f
                val outer = center.polar(angle, ringRadius)
                val inner = center.polar(angle, ringRadius - length)
                drawLine(
                    color = if (isMajor) cardinalColor else tickColor,
                    start = inner,
                    end = outer,
                    strokeWidth = if (isMajor) 3.dp.toPx() else 1.5f.dp.toPx(),
                )
            }

            listOf(0f to "ش", 90f to "ق", 180f to "ج", 270f to "غ").forEach { (angle, label) ->
                val layout = textMeasurer.measure(label, cardinalStyle)
                val position = center.polar(angle, ringRadius - radius * 0.20f)
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

/** A radial spoke plus a filled disc marking the direction of the Kaaba on the dial. */
private fun DrawScope.drawQiblaMarker(center: Offset, radius: Float, bearing: Float, color: Color) {
    val tip = center.polar(bearing, radius * 0.94f)
    drawLine(
        color = color,
        start = center,
        end = center.polar(bearing, radius * 0.78f),
        strokeWidth = 6.dp.toPx(),
    )
    drawCircle(color = color, radius = 10.dp.toPx(), center = tip)
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
