package com.athkar.feature.prayertimes

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Path
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
import com.athkar.designsystem.PatternedSurface
import com.athkar.designsystem.SkyPhase
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

        PatternedSurface(
            sky = SkyPhase.NIGHT,
            shape = CircleShape,
            patternAlpha = 0.07f,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        ) {
            CompassDial(
                rotationDegrees = if (heading == null) 0f else rotation,
                qiblaBearing = qibla.toFloat(),
                offAngle = offAngle,
                isAligned = isAligned,
                modifier = Modifier.fillMaxSize(),
            )
        }

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
    // The dial sits on the night disc, so every mark is drawn in light rather than in the scheme's
    // on-surface colours, which are meant for the ivory page behind it.
    val hairline = Color.White.copy(alpha = 0.22f)
    val tickColor = Color.White.copy(alpha = 0.30f)
    val majorTick = Color.White.copy(alpha = 0.55f)
    val cardinalColor = Color.White.copy(alpha = 0.90f)
    val northColor = Color(0xFFFF8A80)
    val needleColor = if (isAligned) accents.gold else SkyPhase.NIGHT.accent
    val tailColor = Color.White.copy(alpha = 0.28f)
    val textMeasurer = rememberTextMeasurer()

    val cardinalStyle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = cardinalColor)
    val northStyle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = northColor)
    val degreeStyle = TextStyle(fontSize = 10.sp, color = Color.White.copy(alpha = 0.45f))

    Canvas(modifier = modifier) {
        val radius = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        val rim = radius * 0.88f

        drawCircle(color = hairline, radius = rim, center = center, style = Stroke(1.5f.dp.toPx()))
        drawCircle(color = hairline, radius = rim * 0.70f, center = center, style = Stroke(1f.dp.toPx()))

        // How far off the phone is, drawn as the arc between where it points and where the qibla is.
        // Two marks leave the reader to estimate that gap; an arc states it.
        if (offAngle != null && !isAligned) {
            drawArc(
                color = needleColor.copy(alpha = 0.22f),
                startAngle = -90f,
                sweepAngle = offAngle,
                useCenter = false,
                topLeft = Offset(center.x - rim, center.y - rim),
                size = Size(rim * 2, rim * 2),
                style = Stroke(width = radius * 0.07f),
            )
        }

        rotate(degrees = rotationDegrees, pivot = center) {
            repeat(72) { index ->
                val angle = index * 5f
                val isCardinal = index % 18 == 0
                val isMajor = index % 6 == 0
                val length = when {
                    isCardinal -> radius * 0.10f
                    isMajor -> radius * 0.055f
                    else -> radius * 0.028f
                }
                drawLine(
                    color = if (isCardinal) majorTick else tickColor,
                    start = center.polar(angle, rim - length),
                    end = center.polar(angle, rim),
                    strokeWidth = if (isCardinal) 2.5f.dp.toPx() else 1f.dp.toPx(),
                )
            }

            // Only the quarters carry a number. Every thirty degrees turned the rim into a ruler
            // and buried the four letters that actually orient the reader.
            for (angle in listOf(45, 135, 225, 315)) {
                val layout = textMeasurer.measure("$angle", degreeStyle)
                val position = center.polar(angle.toFloat(), rim - radius * 0.155f)
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(
                        position.x - layout.size.width / 2f,
                        position.y - layout.size.height / 2f,
                    ),
                )
            }

            listOf(0f to "ش", 90f to "ق", 180f to "ج", 270f to "غ").forEach { (angle, label) ->
                val layout = textMeasurer.measure(label, if (angle == 0f) northStyle else cardinalStyle)
                val position = center.polar(angle, rim - radius * 0.175f)
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(
                        position.x - layout.size.width / 2f,
                        position.y - layout.size.height / 2f,
                    ),
                )
            }

            drawNeedle(
                center = center,
                radius = rim,
                bearing = qiblaBearing,
                color = needleColor,
                tailColor = tailColor,
                aligned = isAligned,
            )
        }

        // Hub and the fixed mark, drawn outside the rotation: they belong to the phone, not the dial.
        drawCircle(color = needleColor, radius = radius * 0.055f, center = center)
        drawCircle(color = SkyPhase.NIGHT.bottom, radius = radius * 0.022f, center = center)
        drawIndex(center = center, radius = radius, color = Color.White)
    }
}

/**
 * A compass needle rather than a spoke: a tapered head to the rim and a short counterweight behind
 * the hub. The taper is what makes the direction readable at a glance and while the phone moves —
 * a line of even width reads as a radius, not as a pointer.
 */
private fun DrawScope.drawNeedle(
    center: Offset,
    radius: Float,
    bearing: Float,
    color: Color,
    tailColor: Color,
    aligned: Boolean,
) {
    val halfWidth = radius * 0.055f
    val left = center.polar(bearing + 90f, halfWidth)
    val right = center.polar(bearing - 90f, halfWidth)
    val tip = center.polar(bearing, radius * 0.90f)
    val tail = center.polar(bearing + 180f, radius * 0.30f)

    if (aligned) {
        drawCircle(color = color.copy(alpha = 0.20f), radius = radius * 0.16f, center = tip)
    }
    drawPath(
        path = Path().apply {
            moveTo(tip.x, tip.y); lineTo(left.x, left.y); lineTo(right.x, right.y); close()
        },
        color = color,
    )
    drawPath(
        path = Path().apply {
            moveTo(tail.x, tail.y); lineTo(left.x, left.y); lineTo(right.x, right.y); close()
        },
        color = tailColor,
    )
}

/** The fixed mark at the top: the direction the phone itself is facing, for the needle to meet. */
private fun DrawScope.drawIndex(center: Offset, radius: Float, color: Color) {
    val tipY = center.y - radius * 0.92f
    val width = radius * 0.045f
    drawPath(
        path = Path().apply {
            moveTo(center.x, tipY + width * 1.6f)
            lineTo(center.x - width, tipY)
            lineTo(center.x + width, tipY)
            close()
        },
        color = color,
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
