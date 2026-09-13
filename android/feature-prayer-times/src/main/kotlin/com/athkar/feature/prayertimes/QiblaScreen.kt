package com.athkar.feature.prayertimes

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.athkar.core.prayer.QiblaBySun
import com.athkar.designsystem.LocalAthkarAccents
import com.athkar.designsystem.LocalSkyPhase
import com.athkar.designsystem.PatternedSurface
import com.athkar.designsystem.Sizing
import com.athkar.designsystem.Spacing
import com.athkar.domain.CompassCalibration
import com.athkar.domain.SightingMethod
import com.athkar.feature.prayertimes.QiblaViewModel.UiState
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Arabic counts degrees in three forms; "23 درجة" and "3 درجة" are not both right. */
private fun degreesLabel(degrees: Int): String = when {
    degrees == 1 -> "درجة"
    degrees == 2 -> "درجتين"
    degrees in 3..10 -> "$degrees درجات"
    else -> "$degrees درجة"
}

/** Within this many degrees the phone is treated as facing the qibla. */
private const val ALIGNMENT_TOLERANCE_DEGREES = 4f

/**
 * The tilt, in degrees, that pushes the level's bubble all the way to the edge of its ring.
 *
 * Twenty rather than ninety: the bubble has to visibly move for the small corrections that decide
 * whether the reading is trustworthy. Scaled to the full range it would barely stir for the ten
 * degrees that matter, which is a level that looks fine while the compass is wrong.
 */
private const val LEVEL_FULL_SCALE_DEGREES = 20f

@Composable
fun QiblaRoute(viewModel: QiblaViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    QiblaScreen(
        state = state,
        onSight = viewModel::sight,
        onClearCalibration = viewModel::clearCalibration,
    )
}

@Composable
private fun QiblaScreen(
    state: UiState,
    onSight: (SightingMethod) -> Unit,
    onClearCalibration: () -> Unit,
) {
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

            else -> QiblaContent(
                state = state,
                onSight = onSight,
                onClearCalibration = onClearCalibration,
            )
        }
    }
}

@Composable
private fun QiblaContent(
    state: UiState,
    onSight: (SightingMethod) -> Unit,
    onClearCalibration: () -> Unit,
) {
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
    val isPointing = offAngle != null && abs(offAngle) <= ALIGNMENT_TOLERANCE_DEGREES

    // Pointing the right way is only half of it. A magnetometer reads the horizontal component of
    // the field, so a phone held at a reading angle is projecting that field onto a plane that is
    // not the ground — and it reports the resulting bearing with no less confidence. Most people
    // have never been told this, so the screen only confirms the qibla when the level agrees.
    val isAligned = isPointing && state.isLevel

    // A pulse on crossing into alignment, not while inside it: the phone is held out and turned,
    // often at arm's length, and the moment it is right is the one thing the screen cannot tell
    // someone who is looking at the Kaaba's direction rather than at the glass.
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(isAligned) {
        if (isAligned) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // The dial alone is a full-width square, so on a phone it and the bearing card fill the
            // viewport and everything below is unreachable — including the warnings that tell the
            // user their magnetometer is being lied to and the sun method that is the way out.
            .verticalScroll(rememberScrollState())
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
                isLevel = state.isLevel,
                pitchDegrees = state.pitchDegrees,
                rollDegrees = state.rollDegrees,
                hasHeading = heading != null,
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
                        // Pointing the right way but leaning: naming that as the one remaining step
                        // is the difference between a user who levels the phone and one who decides
                        // the app cannot make up its mind.
                        isPointing -> "اتجاهك صحيح — سوِّ الجهاز أفقيًا ليثبت"
                        // Which way, and how far. "Turn until the marks line up" is a description
                        // of the screen, not an instruction — it leaves the user to guess the
                        // direction and discover it by turning the wrong way first.
                        offAngle != null -> {
                            val away = abs(offAngle).roundToInt()
                            val side = if (offAngle > 0) "يمينًا" else "يسارًا"
                            "أدِر الجهاز $side ${degreesLabel(away)}"
                        }
                        else -> "أدِر الجهاز حتى تنطبق العلامة الذهبية على المؤشر"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    // Spoken as it changes, so the dial is usable without seeing it.
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    color = if (isAligned) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    textAlign = TextAlign.Center,
                )
                if (state.hasCompass && heading != null) {
                    Spacer(Modifier.height(Spacing.md))
                    AccuracyRow(state = state)
                }
                if (state.needsCalibration && state.hasCompass) {
                    Spacer(Modifier.height(Spacing.sm))
                    Warning("دقّة البوصلة منخفضة — حرّك الجهاز على شكل الرقم 8 لمعايرته.")
                }
                if (state.isFieldDisturbed) {
                    Spacer(Modifier.height(Spacing.sm))
                    Warning(
                        "المجال المغناطيسي حولك مضطرب — ابتعد عن المعادن والحوامل المغناطيسية، " +
                            "أو استعمل طريقة الشمس أدناه."
                    )
                }
                if (state.isTooTilted) {
                    // Past the point where the bubble is merely off-centre: at this angle the
                    // bearing itself is not worth reading, so it is said in words as well.
                    Spacer(Modifier.height(Spacing.sm))
                    Warning("أمسك الجهاز أفقيًا كالصينية؛ الميل الشديد يفسد قراءة البوصلة.")
                }
            }
        }

        CalibrationCard(
            state = state,
            onSight = onSight,
            onClear = onClearCalibration,
        )

        // A sibling, not a card inset flush inside another card: nested, its corners stacked against
        // the parent's and it sat on a green slab whenever the qibla was aligned.
        SunMethodCard(alignment = state.sunAlignment)
    }
}

/**
 * The solar correction: the one thing on this screen that can make the compass *right* rather than
 * merely report how wrong it might be.
 *
 * Everything else here is diagnosis — the field strength disagrees with the model, the phone is not
 * level, the sensor wants calibrating. None of it can recover a true bearing from a magnetometer
 * sitting next to a steel door. A sighting of the sun can, because the sun's azimuth at this place
 * and this second is arithmetic, and the difference between it and what the compass claims is the
 * compass's entire error in one number.
 */
@Composable
private fun CalibrationCard(
    state: UiState,
    onSight: (SightingMethod) -> Unit,
    onClear: () -> Unit,
) {
    if (!state.hasCompass) return
    val accents = LocalAthkarAccents.current
    var sighting by remember { mutableStateOf(false) }

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (state.isCalibrated) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Spacing.lg)) {
            val calibration = state.calibration
            if (calibration == null) {
                Text(
                    "اضبط البوصلة بالشمس",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    "الحديد والمغناطيس حولك يزيحان البوصلة عشرات الدرجات وهي تخبرك بالزاوية واثقة. " +
                        "سَمْت الشمس يُحسب فلكيًا لا يزيغ، فقياسٌ واحد عليه يكشف خطأ بوصلتك ويصحّحه.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.md))
                Button(
                    onClick = { sighting = true },
                    enabled = state.canSightSun && state.headingDegrees != null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("ابدأ الضبط") }
                sunUnavailableReason(state)?.let {
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                Text(
                    "البوصلة مضبوطة ${calibration.method.arabicName}",
                    style = MaterialTheme.typography.titleMedium,
                    color = accents.gold,
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    // The correction itself, because a user whose compass was twenty degrees out
                    // deserves to know that — it is the measure of how much the screen was lying
                    // to them a minute ago, and of what every other compass app still says.
                    "التصحيح ${Formatting.signedDegrees(calibration.signedOffset)} · " +
                        Formatting.ago(
                            java.time.Duration.between(calibration.takenAt, java.time.Instant.now()),
                        ),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    "الدقة المتوقعة نحو ${degreesLabel(calibration.method.expectedErrorDegrees.toInt())}. " +
                        "ينتهي الضبط بعد ست ساعات أو إذا انتقلت إلى مكان آخر.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.sm))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedButton(
                        onClick = { sighting = true },
                        enabled = state.canSightSun,
                        modifier = Modifier.weight(1f),
                    ) { Text("إعادة الضبط") }
                    TextButton(onClick = onClear, modifier = Modifier.weight(1f)) {
                        Text("إلغاء الضبط")
                    }
                }
            }
        }
    }

    if (sighting) {
        SightingDialog(
            state = state,
            onSight = {
                onSight(it)
                sighting = false
            },
            onDismiss = { sighting = false },
        )
    }
}

/** Why the button is dead, when it is — never a disabled control with no explanation beside it. */
private fun sunUnavailableReason(state: UiState): String? {
    val sun = state.sun ?: return null
    return when {
        state.headingDegrees == null -> "جارٍ قراءة البوصلة…"
        sun.altitude < CompassCalibration.MIN_USABLE_ALTITUDE ->
            "الشمس تحت الأفق الآن — الضبط متاح في النهار."
        sun.altitude > CompassCalibration.MAX_USABLE_ALTITUDE ->
            "الشمس قريبة من كبد السماء، والظل أقصر من أن يُقاس عليه. جرّب بعد ساعتين أو قبل الغروب."
        !state.isLevel -> "سوِّ الجهاز أفقيًا أولًا؛ القياس وهو مائل يثبّت خطأ الميل في الضبط."
        else -> null
    }
}

/**
 * Taking the sighting.
 *
 * The shadow is offered first and recommended, and not only because it is the more accurate of the
 * two: it is the one that does not ask anybody to point a phone at the sun and look along it. The
 * warning on the other method is there for the same reason, and is not decoration.
 */
@Composable
private fun SightingDialog(
    state: UiState,
    onSight: (SightingMethod) -> Unit,
    onDismiss: () -> Unit,
) {
    var method by remember { mutableStateOf(SightingMethod.SHADOW) }
    val sun = state.sun

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ضبط البوصلة بالشمس") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    MethodChip(
                        label = "بالظل",
                        selected = method == SightingMethod.SHADOW,
                        onClick = { method = SightingMethod.SHADOW },
                    )
                    MethodChip(
                        label = "بالشمس",
                        selected = method == SightingMethod.SUN,
                        onClick = { method = SightingMethod.SUN },
                    )
                }

                Spacer(Modifier.height(Spacing.md))
                Text(
                    when (method) {
                        SightingMethod.SHADOW ->
                            "١) ضع الجهاز على أرض مستوية تصلها الشمس.\n" +
                                "٢) أقِم شيئًا مستقيمًا بجانبه: قلمًا أو عصًا.\n" +
                                "٣) أدِر الجهاز حتى ينطبق طرفه الأعلى على الظلّ الممتدّ بعيدًا عن الشمس.\n" +
                                "٤) اضغط «ثبّت الآن» وأنت ممسك به ثابتًا."
                        SightingMethod.SUN ->
                            "١) أمسك الجهاز مستويًا أفقيًا كالصينية.\n" +
                                "٢) أدِر طرفه الأعلى نحو جهة الشمس — جهتها على الأرض، لا قرصها " +
                                "في السماء.\n" +
                                "٣) اضغط «ثبّت الآن» وهو ثابت."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )

                if (method == SightingMethod.SUN) {
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        "لا تنظر إلى الشمس مباشرة. وجّه الجهاز نحوها دون أن ترمقها ببصرك.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                if (sun != null) {
                    Spacer(Modifier.height(Spacing.md))
                    Text(
                        "الشمس الآن: سمتها ${Formatting.degrees(sun.azimuth)} " +
                            "وارتفاعها ${Formatting.degrees(sun.altitude)} فوق الأفق.",
                        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(Spacing.sm))
                Text(
                    "الدقة المتوقعة بعد الضبط: نحو " +
                        degreesLabel(method.expectedErrorDegrees.toInt()) + ".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!state.isLevel) {
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        "الجهاز مائل الآن — سوِّه حتى تستقرّ فقاعة الميزان، ثم ثبّت.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSight(method) },
                // Levelness gates the shutter, not the sheet: the user reads the instructions
                // first and levels the phone while following them.
                enabled = state.canSightSun && state.headingDegrees != null && state.isLevel,
            ) { Text("ثبّت الآن") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}

@Composable
private fun MethodChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) {
            {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                )
            }
        } else {
            null
        },
    )
}

/**
 * The sun as a qibla reference, and the reason this screen has one.
 *
 * A magnetometer can be tens of degrees wrong beside anything ferrous and reports those degrees with
 * complete confidence — it has no way to know it is lying. The sun cannot be pulled off course by a
 * speaker magnet, and its position is computed here from the same solar model the prayer times come
 * from. At the moment below, facing the sun is facing the Kaaba to a fraction of a degree.
 */
@Composable
private fun SunMethodCard(alignment: QiblaBySun?) {
    if (alignment == null || (alignment.facingSun == null && alignment.facingShadow == null)) return
    val zone = remember { ZoneId.systemDefault() }

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Spacing.lg)) {
            Text(
                "القبلة بالشمس — الأدقّ",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                "لا يشوّشها معدن ولا مغناطيس، وتُحسب فلكيًا بدقة أجزاء من الدرجة.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            alignment.facingSun?.let {
                Spacer(Modifier.height(Spacing.md))
                SunMoment(
                    time = Formatting.time(it, zone),
                    instruction = "استقبل الشمس في هذه اللحظة فتكون مستقبلًا القبلة تمامًا.",
                )
            }
            alignment.facingShadow?.let {
                Spacer(Modifier.height(Spacing.md))
                SunMoment(
                    time = Formatting.time(it, zone),
                    instruction = "ظلّ أي شيء قائم في هذه اللحظة يشير إلى القبلة.",
                )
            }
        }
    }
}

@Composable
private fun SunMoment(time: String, instruction: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            time,
            style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
            color = LocalAthkarAccents.current.gold,
        )
        Spacer(Modifier.width(Spacing.md))
        Text(
            instruction,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The three conditions a magnetic bearing depends on, stated rather than assumed.
 *
 * A compass that is wrong looks exactly like a compass that is right — it has no way to know. These
 * are the checks the app *can* run, so it shows all three and their verdicts instead of a single
 * "accuracy" word that means nothing to the person holding the phone.
 */
@Composable
private fun AccuracyRow(state: UiState) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Check(label = "مستوٍ", satisfied = state.isLevel)
        Check(label = "مُعايَر", satisfied = !state.needsCalibration)
        Check(label = "بلا تشويش", satisfied = !state.isFieldDisturbed)
        // Shown only once it is true. As an unticked box it would read as a fault in the device
        // rather than as an action the user has not taken yet, and the card below already asks.
        if (state.isCalibrated) Check(label = "مضبوطة بالشمس", satisfied = true, gold = true)
    }
}

@Composable
private fun Check(label: String, satisfied: Boolean, gold: Boolean = false) {
    val colour = when {
        gold -> LocalAthkarAccents.current.gold
        satisfied -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.error
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (satisfied) Icons.Default.Check else Icons.Default.Close,
            contentDescription = if (satisfied) "متحقق" else "غير متحقق",
            tint = colour,
            modifier = Modifier.size(Sizing.iconSm),
        )
        Spacer(Modifier.width(Spacing.xxs))
        Text(label, style = MaterialTheme.typography.labelMedium, color = colour)
    }
}

@Composable
private fun Warning(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun CompassDial(
    rotationDegrees: Float,
    qiblaBearing: Float,
    offAngle: Float?,
    isAligned: Boolean,
    isLevel: Boolean,
    pitchDegrees: Float,
    rollDegrees: Float,
    hasHeading: Boolean,
    modifier: Modifier = Modifier,
) {
    val accents = LocalAthkarAccents.current
    val sky = LocalSkyPhase.current
    // The dial sits on the sky disc, so every mark is drawn in light rather than in the scheme's
    // on-surface colours, which are meant for the page behind it.
    val hairline = Color.White.copy(alpha = 0.22f)
    val tickColor = Color.White.copy(alpha = 0.30f)
    val majorTick = Color.White.copy(alpha = 0.55f)
    val cardinalColor = Color.White.copy(alpha = 0.90f)
    val northColor = Color(0xFFFF8A80)
    val needleColor = if (isAligned) accents.gold else sky.accent
    val tailColor = Color.White.copy(alpha = 0.28f)
    val textMeasurer = rememberTextMeasurer()

    // The bubble is eased for the same reason the needle is: a level fed raw sensor values twitches
    // continuously, and a bubble that will not settle reads as a phone that cannot be held still.
    val bubblePitch by animateFloatAsState(
        targetValue = pitchDegrees.coerceIn(-LEVEL_FULL_SCALE_DEGREES, LEVEL_FULL_SCALE_DEGREES),
        animationSpec = tween(durationMillis = 160),
        label = "level-pitch",
    )
    val bubbleRoll by animateFloatAsState(
        targetValue = rollDegrees.coerceIn(-LEVEL_FULL_SCALE_DEGREES, LEVEL_FULL_SCALE_DEGREES),
        animationSpec = tween(durationMillis = 160),
        label = "level-roll",
    )

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

            // The Kaaba at the end of the needle, on the rim. The dial already says *where*; this
            // says what is there, which is the only reason anyone is holding the phone out.
            drawKaaba(
                centre = center.polar(qiblaBearing, rim - radius * 0.02f),
                size = radius * 0.11f,
                body = Color(0xFF14110E),
                band = needleColor,
            )
        }

        // The level and the fixed mark, drawn outside the rotation: they belong to the phone, not
        // to the dial, and must not turn with it.
        drawLevel(
            centre = center,
            radius = radius,
            pitch = bubblePitch,
            roll = bubbleRoll,
            level = isLevel && hasHeading,
            bubbleColor = if (isLevel) accents.gold else Color.White.copy(alpha = 0.75f),
        )
        drawIndex(center = center, radius = radius, color = Color.White)
    }
}

/**
 * A spirit level where the hub of the compass used to be.
 *
 * The hub was a dot that said nothing. This is the one thing a magnetic compass needs from its user
 * and never asks for: the sensor reads the horizontal component of the field, so a phone held at a
 * reading angle projects that field onto the wrong plane and reports the resulting bearing with
 * complete confidence. Almost nobody knows that. A bubble does not have to be explained.
 *
 * The bubble floats to the *raised* side, as one in glass would: with the screen's +y toward the top
 * of the device and +z out of it, a positive pitch means the top edge has gone down, so the bubble
 * runs to the bottom of the screen — and a positive roll drops the right edge, so it runs left.
 */
private fun DrawScope.drawLevel(
    centre: Offset,
    radius: Float,
    pitch: Float,
    roll: Float,
    level: Boolean,
    bubbleColor: Color,
) {
    val well = radius * 0.15f
    val bubble = radius * 0.055f
    val travel = well - bubble

    drawCircle(
        color = Color.Black.copy(alpha = 0.35f),
        radius = well,
        center = centre,
    )
    drawCircle(
        color = if (level) bubbleColor.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.35f),
        radius = well,
        center = centre,
        style = Stroke(width = radius * 0.012f),
    )

    val offset = Offset(
        x = centre.x - (roll / LEVEL_FULL_SCALE_DEGREES) * travel,
        y = centre.y + (pitch / LEVEL_FULL_SCALE_DEGREES) * travel,
    )
    drawCircle(color = bubbleColor, radius = bubble, center = offset)
}

/**
 * The Kaaba, as a cube with its band: a square with a horizontal stripe across the upper third.
 *
 * Drawn rather than bundled as an image so it takes the needle's colour when the direction is
 * confirmed, and so it stays sharp at whatever size the dial ends up on a given screen.
 */
private fun DrawScope.drawKaaba(centre: Offset, size: Float, body: Color, band: Color) {
    val half = size / 2f
    drawRoundRect(
        color = body,
        topLeft = Offset(centre.x - half, centre.y - half),
        size = Size(size, size),
        cornerRadius = CornerRadius(size * 0.12f, size * 0.12f),
    )
    drawRect(
        color = band,
        topLeft = Offset(centre.x - half, centre.y - half + size * 0.28f),
        size = Size(size, size * 0.16f),
    )
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
