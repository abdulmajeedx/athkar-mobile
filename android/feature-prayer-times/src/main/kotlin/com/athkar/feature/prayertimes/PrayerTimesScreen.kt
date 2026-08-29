package com.athkar.feature.prayertimes

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.athkar.core.prayer.CalculationMethod
import com.athkar.core.prayer.Madhab
import com.athkar.core.prayer.Prayer
import com.athkar.designsystem.LocalAthkarAccents
import com.athkar.designsystem.PatternedSurface
import com.athkar.designsystem.Sizing
import com.athkar.designsystem.SkyPhase
import com.athkar.designsystem.Spacing
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

    // The permission result drives the fix directly: asking and then not using the answer is the
    // classic way to leave a user staring at an unchanged screen after they granted it.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.useDeviceLocation()
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
        onSetIqamaMinutes = viewModel::setIqamaMinutes,
        onDismissError = viewModel::dismissLocationError,
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
    onSetIqamaMinutes: (Prayer, Int) -> Unit,
    onDismissError: () -> Unit,
) {
    var showCityPicker by remember { mutableStateOf(false) }
    var showMethodPicker by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            state.isLoading -> LoadingState()
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
                )
                if (state.error != null) {
                    ErrorCard(state.error)
                } else {
                    PrayerList(state = state, countdown = countdown)
                }
                IqamaSettings(
                    iqamaMinutes = state.iqamaMinutes,
                    onSet = onSetIqamaMinutes,
                )
                NotificationSettings(
                    enabled = state.notificationsEnabled,
                    notifiedPrayers = state.notifiedPrayers,
                    onSetEnabled = onSetNotificationsEnabled,
                    onTogglePrayer = onTogglePrayerNotification,
                )
                SettingsRow(
                    method = state.method,
                    madhab = state.madhab,
                    onOpenMethodPicker = { showMethodPicker = true },
                    onSelectMadhab = onSelectMadhab,
                )
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

    if (showMethodPicker) {
        MethodPickerDialog(
            selected = state.method,
            onSelect = {
                showMethodPicker = false
                onSelectMethod(it)
            },
            onDismiss = { showMethodPicker = false },
        )
    }

    if (locationError != null) {
        AlertDialog(
            onDismissRequest = onDismissError,
            title = { Text("تعذّر تحديد الموقع") },
            text = { Text(locationError) },
            confirmButton = { TextButton(onClick = onDismissError) { Text("حسنًا") } },
        )
    }
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
                color = Color.White.copy(alpha = 0.7f),
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
                    "الصلاة القادمة",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.7f),
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
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        Formatting.countdown(it),
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White.copy(alpha = 0.9f),
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
            color = Color.White.copy(alpha = 0.7f),
        )
        Text(
            Formatting.countdown(untilIqama),
            style = MaterialTheme.typography.headlineMedium,
            color = sky.accent,
        )
        countdown.iqamaAt?.let {
            Text(
                Formatting.time(it, zone),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f),
            )
        }
    } else {
        countdown.sinceCurrent?.let { elapsed ->
            Text(
                "مضى على أذان ${current.arabicName}",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.7f),
            )
            Text(
                Formatting.countdown(elapsed),
                style = MaterialTheme.typography.titleLarge,
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
    val background = if (isNext) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    // Past prayers fade rather than disappear: the schedule stays readable as a whole day.
    val contentAlpha = if (isPast && !isNext) 0.45f else 1f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .heightIn(min = Sizing.touchTarget)
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(Spacing.sm)
                .background(
                    color = if (isNext) accents.gold else MaterialTheme.colorScheme.outline,
                    shape = CircleShape,
                ),
        )
        Spacer(Modifier.width(Spacing.md))
        Text(
            prayer.arabicName,
            style = if (isNext) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            fontWeight = if (isNext) FontWeight.Bold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
            modifier = Modifier.weight(1f),
        )
        Text(
            time,
            style = MaterialTheme.typography.titleMedium,
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
private fun SettingsRow(
    method: CalculationMethod,
    madhab: Madhab,
    onOpenMethodPicker: () -> Unit,
    onSelectMadhab: (Madhab) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(
            "طريقة الحساب",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AssistChip(
            onClick = onOpenMethodPicker,
            label = { Text(method.arabicName) },
            leadingIcon = {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(AssistChipDefaults.IconSize),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            "وقت العصر",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.sm),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            MadhabChip("الجمهور", madhab == Madhab.SHAFI) { onSelectMadhab(Madhab.SHAFI) }
            MadhabChip("الحنفي", madhab == Madhab.HANAFI) { onSelectMadhab(Madhab.HANAFI) }
        }
    }
}

@Composable
private fun MadhabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label) }
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

@Composable
private fun MethodPickerDialog(
    selected: CalculationMethod,
    onSelect: (CalculationMethod) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("طريقة الحساب") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                items(CalculationMethod.entries, key = { it.name }) { method ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = Sizing.touchTarget),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = { onSelect(method) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                method.arabicName,
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.Start,
                            )
                            if (method == selected) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "المختار",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("إغلاق") } },
    )
}

/**
 * Alert settings.
 *
 * Two separate platform gates stand between the switch and an actual notification, and both fail
 * silently: on Android 13+ the app must hold POST_NOTIFICATIONS, and on Android 12+ it must be
 * allowed to set exact alarms or the alert drifts with Doze. Both are surfaced here rather than
 * discovered by the user missing Fajr.
 */
@Composable
private fun NotificationSettings(
    enabled: Boolean,
    notifiedPrayers: Set<Prayer>,
    onSetEnabled: (Boolean) -> Unit,
    onTogglePrayer: (Prayer) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var exactAlarmsAllowed by remember { mutableStateOf(canScheduleExactAlarms(context)) }

    // The exact-alarm switch is flipped in system settings, so its state can only be re-read when
    // the user comes back to this screen.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                exactAlarmsAllowed = canScheduleExactAlarms(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // Enabling only on a grant keeps the switch honest: it is never on while muted by the OS.
        onSetEnabled(granted)
    }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(
            "التنبيهات",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(Spacing.lg)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("تنبيه عند دخول الوقت", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "بنغمة المنبّه الافتراضية في جهازك",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { wantsEnabled ->
                            if (!wantsEnabled) {
                                onSetEnabled(false)
                            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notificationPermissionLauncher.launch(
                                    Manifest.permission.POST_NOTIFICATIONS,
                                )
                            } else {
                                onSetEnabled(true)
                            }
                        },
                    )
                }

                if (enabled) {
                    Spacer(Modifier.height(Spacing.md))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Prayer.entries.forEach { prayer ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = Sizing.touchTarget),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                prayer.arabicName,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = prayer in notifiedPrayers,
                                onCheckedChange = { onTogglePrayer(prayer) },
                            )
                        }
                    }

                    if (!exactAlarmsAllowed) {
                        Spacer(Modifier.height(Spacing.sm))
                        Text(
                            "التنبيهات الدقيقة غير مسموح بها لهذا التطبيق، لذا قد يتأخر التنبيه عن " +
                                "وقته بدقائق. امنح الإذن ليصل في وقته تمامًا.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(Spacing.xs))
                        OutlinedButton(onClick = { openExactAlarmSettings(context) }) {
                            Text("السماح بالتنبيهات الدقيقة")
                        }
                    }
                }
            }
        }
    }
}

/** Below Android 12 exact alarms need no permission, so the gate does not exist. */
private fun canScheduleExactAlarms(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
    return manager?.canScheduleExactAlarms() == true
}

private fun openExactAlarmSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val intent = Intent(
        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
        Uri.fromParts("package", context.packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    // Some OEM builds ship without this settings screen; a missing activity must not crash the app.
    runCatching { context.startActivity(intent) }
}

/**
 * How long after each adhan the congregation stands.
 *
 * There is nothing to calculate here — it is a decision each mosque makes — so the app ships the
 * customary gaps and lets the user correct them to their own. Sunrise is absent because it has no
 * congregation to call.
 */
@Composable
private fun IqamaSettings(
    iqamaMinutes: Map<Prayer, Int>,
    onSet: (Prayer, Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(
            "وقت الإقامة بعد الأذان",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(vertical = Spacing.sm)) {
                Prayer.entries.filter { it != Prayer.SUNRISE }.forEachIndexed { index, prayer ->
                    val minutes = iqamaMinutes[prayer] ?: 0
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = Sizing.touchTarget)
                            .padding(horizontal = Spacing.lg),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            prayer.arabicName,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { onSet(prayer, (minutes - 5).coerceAtLeast(0)) },
                            enabled = minutes > 0,
                            modifier = Modifier.size(Sizing.touchTarget),
                        ) {
                            Icon(Icons.Default.Remove, contentDescription = "أنقص خمس دقائق")
                        }
                        Text(
                            if (minutes == 0) "—" else "$minutes د",
                            style = MaterialTheme.typography.titleSmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.width(Spacing.huge),
                        )
                        IconButton(
                            onClick = { onSet(prayer, minutes + 5) },
                            modifier = Modifier.size(Sizing.touchTarget),
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "زد خمس دقائق")
                        }
                    }
                    if (index != Prayer.entries.size - 2) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(horizontal = Spacing.lg),
                        )
                    }
                }
            }
        }
    }
}
