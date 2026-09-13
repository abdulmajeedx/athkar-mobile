package com.athkar.feature.prayertimes

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.athkar.core.prayer.CalculationMethod
import com.athkar.core.prayer.HighLatitudeRule
import com.athkar.core.prayer.Madhab
import com.athkar.core.prayer.Prayer
import com.athkar.designsystem.LocalAthkarAccents
import com.athkar.designsystem.PatternedSurface
import com.athkar.designsystem.Sizing
import com.athkar.designsystem.SkyPhase
import com.athkar.designsystem.Spacing
import com.athkar.domain.AlertSound
import com.athkar.domain.Cities
import com.athkar.domain.Place
import com.athkar.feature.prayertimes.PrayerTimesViewModel.Countdown
import com.athkar.feature.prayertimes.PrayerTimesViewModel.UiState
import java.time.ZoneId

@Composable
fun PrayerTimesRoute(viewModel: PrayerTimesViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val countdown by viewModel.countdown.collectAsStateWithLifecycle()
    val isLocating by viewModel.isLocating.collectAsStateWithLifecycle()
    val locationError by viewModel.locationError.collectAsStateWithLifecycle()
    val isPreviewingAlertSound by viewModel.isPreviewingAlertSound.collectAsStateWithLifecycle()

    // The permission result drives the fix directly: asking and then not using the answer is the
    // classic way to leave a user staring at an unchanged screen after they granted it.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.useDeviceLocation() else viewModel.reportLocationPermissionDenied()
    }

    PrayerTimesScreen(
        state = state,
        countdown = countdown,
        isLocating = isLocating,
        locationError = locationError,
        onUseDeviceLocation = { permissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION) },
        onSelectPlace = viewModel::selectPlace,
        onSelectMethod = viewModel::selectMethod,
        onSelectMadhab = viewModel::selectMadhab,
        onSetNotificationsEnabled = viewModel::setNotificationsEnabled,
        onTogglePrayerNotification = viewModel::togglePrayerNotification,
        onSelectAlertSound = viewModel::selectAlertSound,
        onPreviewAlertSound = viewModel::previewAlertSound,
        onStopAlertSoundPreview = viewModel::stopAlertSoundPreview,
        onSelectHighLatitudeRule = viewModel::selectHighLatitudeRule,
        onSetPreAdhanMinutes = viewModel::setPreAdhanMinutes,
        onSetIqamaMinutes = viewModel::setIqamaMinutes,
        onDismissError = viewModel::dismissLocationError,
        isPreviewingAlertSound = isPreviewingAlertSound,
    )
}

@Composable
private fun PrayerTimesScreen(
    state: UiState,
    countdown: Countdown,
    isLocating: Boolean,
    locationError: String?,
    onUseDeviceLocation: () -> Unit,
    onSelectPlace: (Place) -> Unit,
    onSelectMethod: (CalculationMethod) -> Unit,
    onSelectMadhab: (Madhab) -> Unit,
    onSetNotificationsEnabled: (Boolean) -> Unit,
    onTogglePrayerNotification: (Prayer) -> Unit,
    onSelectAlertSound: (AlertSound) -> Unit,
    onPreviewAlertSound: (AlertSound) -> Unit,
    onStopAlertSoundPreview: () -> Unit,
    onSelectHighLatitudeRule: (HighLatitudeRule?) -> Unit,
    onSetPreAdhanMinutes: (Int) -> Unit,
    onSetIqamaMinutes: (Prayer, Int) -> Unit,
    onDismissError: () -> Unit,
    isPreviewingAlertSound: Boolean,
) {
    var showCityPicker by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            state.isLoading -> LoadingState()

            showSettings -> {
                // System back leaves the settings before it leaves the tab.
                BackHandler { showSettings = false }
                PrayerSettingsScreen(
                    state = state,
                    auditioning = isPreviewingAlertSound,
                    onBack = { showSettings = false },
                    onSelectMethod = onSelectMethod,
                    onSelectMadhab = onSelectMadhab,
                    onSetNotificationsEnabled = onSetNotificationsEnabled,
                    onTogglePrayerNotification = onTogglePrayerNotification,
                    onSelectAlertSound = onSelectAlertSound,
                    onPreviewAlertSound = onPreviewAlertSound,
                    onStopAlertSoundPreview = onStopAlertSoundPreview,
                    onSelectHighLatitudeRule = onSelectHighLatitudeRule,
                    onSetPreAdhanMinutes = onSetPreAdhanMinutes,
                    onSetIqamaMinutes = onSetIqamaMinutes,
                )
            }

            state.needsPlace -> PlacePrompt(
                isLocating = isLocating,
                onUseDeviceLocation = onUseDeviceLocation,
                onPickCity = { showCityPicker = true },
            )
            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                Spacer(Modifier.height(Spacing.lg))
                HeroCard(
                    state = state,
                    countdown = countdown,
                    isLocating = isLocating,
                    onChangePlace = { showCityPicker = true },
                    onUseDeviceLocation = onUseDeviceLocation,
                    onOpenSettings = { showSettings = true },
                )
                if (state.error != null) {
                    ErrorCard(state.error)
                } else {
                    PrayerList(state = state, countdown = countdown)
                }
                Spacer(Modifier.height(Spacing.xxl))
            }
        }
    }

    if (showCityPicker) {
        CityPickerDialog(
            isLocating = isLocating,
            onUseDeviceLocation = {
                showCityPicker = false
                onUseDeviceLocation()
            },
            onSelect = {
                showCityPicker = false
                onSelectPlace(it)
            },
            onDismiss = { showCityPicker = false },
        )
    }

    if (locationError != null) {
        val context = LocalContext.current
        AlertDialog(
            onDismissRequest = onDismissError,
            title = { Text("تعذّر تحديد الموقع") },
            text = { Text(locationError) },
            confirmButton = { TextButton(onClick = onDismissError) { Text("حسنًا") } },
            // Once the permission has been refused twice the system will not ask again, so the only
            // way back is the app's own settings page. Naming it beats leaving the user to find it.
            dismissButton = {
                TextButton(onClick = { openAppSettings(context) }) { Text("إعدادات التطبيق") }
            },
        )
    }
}

/** Some OEM builds ship without this screen; a missing activity must not crash the app. */
private fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

@Composable
private fun LoadingState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun HeroCard(
    state: UiState,
    countdown: Countdown,
    isLocating: Boolean,
    onChangePlace: () -> Unit,
    onUseDeviceLocation: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val accents = LocalAthkarAccents.current
    val zone = remember { ZoneId.systemDefault() }

    // The sky of the prayer that has begun. An app that tells the time by the sun and stays one
    // colour all day is throwing away the most obvious thing it knows.
    val sky = SkyPhase.forPrayerOrdinal(countdown.current?.ordinal)

    PatternedSurface(
        sky = sky,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Place,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(Sizing.iconSm),
                )
                Spacer(Modifier.width(Spacing.xs))
                TextButton(onClick = onChangePlace) {
                    Text(
                        state.place?.name ?: "اختر موقعًا",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                    )
                }
                if (isLocating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(Sizing.iconSm),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                } else {
                    TextButton(onClick = onUseDeviceLocation) {
                        Icon(
                            Icons.Default.LocationOn,
                            contentDescription = "تحديد موقعي",
                            tint = sky.accent,
                            modifier = Modifier.size(Sizing.iconSm),
                        )
                    }
                }
                IconButton(onClick = onOpenSettings, modifier = Modifier.size(Sizing.touchTarget)) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "إعدادات الصلاة",
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(Sizing.iconSm),
                    )
                }
            }

            Text(
                state.hijriDate,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Text(
                state.gregorianDate,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.85f),
            )

            Spacer(Modifier.height(Spacing.lg))
            HorizontalDivider(color = Color.White.copy(alpha = 0.15f))
            Spacer(Modifier.height(Spacing.lg))

            val next = countdown.next
            if (next == null) {
                Text(
                    "انقضت صلوات اليوم",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                )
            } else {
                Text(
                    if (countdown.nextIsTomorrow) "فجر الغد" else "الصلاة القادمة",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.85f),
                )
                Text(
                    next.arabicName,
                    style = MaterialTheme.typography.displaySmall,
                    color = sky.accent,
                )
                countdown.nextAt?.let {
                    Text(
                        Formatting.time(it, zone),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                    )
                }
                countdown.remaining?.let {
                    Spacer(Modifier.height(Spacing.md))
                    // The clock time and the countdown were two bare numbers stacked, and nothing
                    // said which was which. The countdown is labelled and monospaced so its digits
                    // stop shifting the line every second.
                    Text(
                        "تبقّى",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                    Text(
                        Formatting.countdown(it),
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontFeatureSettings = "tnum",
                        ),
                        color = Color.White,
                    )
                }
            }

            CurrentPrayerBand(countdown = countdown, zone = zone, sky = sky)
        }
    }
}

/**
 * What is happening *now*, under the countdown to what is next.
 *
 * Between the adhan and the iqama this is the only number that matters, so it is given the emphasis;
 * once the iqama has passed it becomes the time elapsed since the call, which is the difference
 * between "I still have a moment" and "I have missed the congregation".
 */
@Composable
private fun CurrentPrayerBand(
    countdown: PrayerTimesViewModel.Countdown,
    zone: java.time.ZoneId,
    sky: SkyPhase,
) {
    val current = countdown.current ?: return

    Spacer(Modifier.height(Spacing.lg))
    HorizontalDivider(color = Color.White.copy(alpha = 0.15f))
    Spacer(Modifier.height(Spacing.md))

    val untilIqama = countdown.untilIqama
    if (untilIqama != null) {
        Text(
            "إقامة ${current.arabicName} بعد",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.85f),
        )
        Text(
            Formatting.countdown(untilIqama),
            style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"),
            color = sky.accent,
        )
        countdown.iqamaAt?.let {
            Text(
                Formatting.time(it, zone),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.85f),
            )
        }
    } else {
        countdown.sinceCurrent?.let { elapsed ->
            Text(
                "منذ أذان ${current.arabicName}",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.85f),
            )
            Text(
                Formatting.countdown(elapsed),
                style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
                color = Color.White.copy(alpha = 0.9f),
            )
        }
    }
}

@Composable
private fun PrayerList(state: UiState, countdown: Countdown) {
    val zone = remember { ZoneId.systemDefault() }
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = Spacing.sm)) {
            state.rows.forEachIndexed { index, row ->
                PrayerRowItem(
                    prayer = row.prayer,
                    time = Formatting.time(row.at, zone),
                    isNext = row.prayer == countdown.next,
                    isPast = countdown.current != null &&
                        row.prayer.ordinal <= countdown.current.ordinal,
                )
                if (index != state.rows.lastIndex) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(horizontal = Spacing.lg),
                    )
                }
            }
        }
    }
}

@Composable
private fun PrayerRowItem(prayer: Prayer, time: String, isNext: Boolean, isPast: Boolean) {
    val accents = LocalAthkarAccents.current
    // Past prayers fade rather than disappear: the schedule stays readable as a whole day.
    // 0.42 put the past prayers at 2.6:1 on the light surface — below the 4.5:1 a body size needs,
    // and every row is a past row for the hours after Isha. Still clearly receded at 0.62.
    val contentAlpha = if (isPast && !isNext) 0.62f else 1f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isNext) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
            )
            .heightIn(min = Sizing.touchTarget)
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A spine on the leading edge for the next prayer, a dot for the others: one shape says
        // "this one", the rest say "and these".
        Box(
            Modifier
                .width(if (isNext) Sizing.hairline * 3 else Spacing.sm)
                .height(if (isNext) Spacing.xxl else Spacing.sm)
                .background(
                    color = when {
                        isNext -> accents.gold
                        else -> MaterialTheme.colorScheme.outline.copy(alpha = contentAlpha)
                    },
                    shape = MaterialTheme.shapes.extraSmall,
                ),
        )
        Spacer(Modifier.width(Spacing.md))
        Text(
            prayer.arabicName,
            // The emphasised row was the smallest text in the list: titleMedium is 17sp against
            // bodyLarge's 19sp, so the next prayer was set smaller than the ones already gone.
            style = if (isNext) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
            fontWeight = if (isNext) FontWeight.Bold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
            modifier = Modifier.weight(1f),
        )
        Text(
            time,
            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
            fontWeight = if (isNext) FontWeight.Bold else FontWeight.Medium,
            color = if (isNext) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha)
            },
        )
    }
}


@Composable
private fun ErrorCard(message: String) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(Spacing.lg),
        )
    }
}

@Composable
private fun PlacePrompt(
    isLocating: Boolean,
    onUseDeviceLocation: () -> Unit,
    onPickCity: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.Place,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(Sizing.iconLg),
        )
        Spacer(Modifier.height(Spacing.lg))
        Text("أين أنت؟", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(Spacing.sm))
        Text(
            "مواقيت الصلاة واتجاه القبلة يُحسبان من موقعك. لن تُعرض مواقيت قبل تحديده حتى لا تكون خاطئة.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.xxl))
        Button(
            onClick = onUseDeviceLocation,
            enabled = !isLocating,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (isLocating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(Sizing.iconSm),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Icon(Icons.Default.LocationOn, contentDescription = null)
                Spacer(Modifier.width(Spacing.sm))
                Text("تحديد موقعي")
            }
        }
        Spacer(Modifier.height(Spacing.sm))
        OutlinedButton(onClick = onPickCity, modifier = Modifier.fillMaxWidth()) {
            Text("اختيار مدينة")
        }
    }
}

@Composable
private fun CityPickerDialog(
    isLocating: Boolean,
    onUseDeviceLocation: () -> Unit,
    onSelect: (Place) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val results = remember(query) { Cities.search(query) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("اختر مدينتك") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("ابحث") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(Spacing.sm))
                TextButton(onClick = onUseDeviceLocation, enabled = !isLocating) {
                    Icon(Icons.Default.LocationOn, contentDescription = null)
                    Spacer(Modifier.width(Spacing.xs))
                    Text("استخدام موقعي الحالي")
                }
                HorizontalDivider()
                LazyColumn(
                    modifier = Modifier.heightIn(max = 320.dp),
                    contentPadding = PaddingValues(vertical = Spacing.xs),
                ) {
                    items(results, key = { it.name }) { place ->
                        TextButton(
                            onClick = { onSelect(place) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                place.name,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Start,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = null)
                Spacer(Modifier.width(Spacing.xs))
                Text("إغلاق")
            }
        },
    )
}
