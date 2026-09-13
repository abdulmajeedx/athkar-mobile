package com.athkar.feature.prayertimes

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.athkar.core.prayer.CalculationMethod
import com.athkar.core.prayer.HighLatitudeRule
import com.athkar.core.prayer.Madhab
import com.athkar.core.prayer.Prayer
import com.athkar.designsystem.Sizing
import com.athkar.designsystem.Spacing
import com.athkar.domain.AlertSound
import kotlin.math.abs
import com.athkar.domain.PrayerPreferences
import com.athkar.feature.prayertimes.PrayerTimesViewModel.UiState

/** Above this latitude the high-latitude rules diverge enough to be worth choosing between. */
private const val HIGH_LATITUDE_THRESHOLD = 48.0

/**
 * Everything about how the times are computed and announced, on a screen of its own.
 *
 * It used to sit under the schedule, so reaching the day's times meant scrolling past five settings
 * sections, and reaching the settings meant scrolling past the times. Neither is what either reader
 * came for. The prayer screen now shows the prayer times and nothing else, and this opens from the
 * gear beside the place name.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PrayerSettingsScreen(
    state: UiState,
    auditioning: Boolean,
    onBack: () -> Unit,
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
) {
    var showMethodPicker by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("إعدادات الصلاة", style = MaterialTheme.typography.titleMedium) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                }
            },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Spacer(Modifier.height(Spacing.sm))
            NotificationSettings(
                enabled = state.notificationsEnabled,
                notifiedPrayers = state.notifiedPrayers,
                alertSound = state.alertSound,
                auditioning = auditioning,
                onSetEnabled = onSetNotificationsEnabled,
                onTogglePrayer = onTogglePrayerNotification,
                onSelectAlertSound = onSelectAlertSound,
                onPreviewAlertSound = onPreviewAlertSound,
                onStopAlertSoundPreview = onStopAlertSoundPreview,
            )
            PreAdhanSettings(
                minutes = state.preAdhanMinutes,
                onSet = onSetPreAdhanMinutes,
            )
            IqamaSettings(
                iqamaMinutes = state.iqamaMinutes,
                onSet = onSetIqamaMinutes,
            )
            state.place?.let { place ->
                // Below 48 degrees the rules are indistinguishable, and a control nobody needs is
                // one more thing to read past.
                if (abs(place.coordinates.latitude) > HIGH_LATITUDE_THRESHOLD) {
                    HighLatitudeSettings(
                        selected = state.highLatitudeRule,
                        recommended = HighLatitudeRule.recommendedFor(place.coordinates),
                        onSelect = onSelectHighLatitudeRule,
                    )
                }
            }
            SettingsRow(
                method = state.method,
                madhab = state.madhab,
                onOpenMethodPicker = { showMethodPicker = true },
                onSelectMadhab = onSelectMadhab,
            )
            Spacer(Modifier.height(Spacing.xxl))
        }
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
}

@Composable
private fun SettingsRow(
    method: CalculationMethod,
    madhab: Madhab,
    onOpenMethodPicker: () -> Unit,
    onSelectMadhab: (Madhab) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SectionLabel("طريقة الحساب")
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

        SectionLabel("وقت العصر", modifier = Modifier.padding(top = Spacing.sm))
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
    alertSound: AlertSound,
    auditioning: Boolean,
    onSetEnabled: (Boolean) -> Unit,
    onTogglePrayer: (Prayer) -> Unit,
    onSelectAlertSound: (AlertSound) -> Unit,
    onPreviewAlertSound: (AlertSound) -> Unit,
    onStopAlertSoundPreview: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var exactAlarmsAllowed by remember { mutableStateOf(canScheduleExactAlarms(context)) }
    var alarmVolumeSilent by remember { mutableStateOf(isAlarmVolumeSilent(context)) }

    // Both of these are changed outside the app — one in system settings, the other with the volume
    // keys — so returning to this screen is the only moment either can honestly be re-read.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                exactAlarmsAllowed = canScheduleExactAlarms(context)
                alarmVolumeSilent = isAlarmVolumeSilent(context)
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
        SectionLabel("التنبيهات")

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
                            alertSound.description,
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
                    Spacer(Modifier.height(Spacing.md))

                    AlertSoundPicker(
                        selected = alertSound,
                        alarmVolumeSilent = alarmVolumeSilent,
                        auditioning = auditioning,
                        onSelect = onSelectAlertSound,
                        onPreview = onPreviewAlertSound,
                        onStopPreview = onStopAlertSoundPreview,
                    )

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
                        // Without the exact-alarm permission the app is not allowed to start the
                        // player at all, so the adhan falls back to a notification tone the system
                        // cuts off at the first touch of the screen. Saying only "قد يتأخر" would
                        // understate what the user actually loses.
                        Text(
                            if (alertSound == AlertSound.ADHAN) {
                                "التنبيهات الدقيقة غير مسموح بها لهذا التطبيق، فقد يتأخر التنبيه " +
                                    "عن وقته بدقائق ولن يُرفع الأذان كاملًا. امنح الإذن ليصل في " +
                                    "وقته ويُرفع الأذان تامًّا."
                            } else {
                                "التنبيهات الدقيقة غير مسموح بها لهذا التطبيق، لذا قد يتأخر " +
                                    "التنبيه عن وقته بدقائق. امنح الإذن ليصل في وقته تمامًا."
                            },
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

/**
 * What the alert sounds like.
 *
 * Each choice can be heard on the spot, because a sound picked from a list is otherwise first heard
 * at four in the morning — and because the alarm volume being at zero is invisible until something
 * fails to play. The audition uses the same service the prayer alarm uses, so what is heard here is
 * exactly what will arrive then.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlertSoundPicker(
    selected: AlertSound,
    alarmVolumeSilent: Boolean,
    auditioning: Boolean,
    onSelect: (AlertSound) -> Unit,
    onPreview: (AlertSound) -> Unit,
    onStopPreview: () -> Unit,
) {
    // Nothing should keep sounding once this section is gone from the screen.
    DisposableEffect(Unit) { onDispose { onStopPreview() } }

    Text("صوت التنبيه", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(Spacing.sm))

    // Three filled buttons do not fit a 360dp screen on one line, and the one that fell off the end
    // was صامت — the control that silences the adhan.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        AlertSound.entries.forEach { sound ->
            MadhabChip(sound.label, sound == selected) {
                onSelect(sound)
                onStopPreview()
            }
        }
    }

    if (selected != AlertSound.SILENT) {
        Spacer(Modifier.height(Spacing.sm))
        Row(
            modifier = Modifier.heightIn(min = Sizing.touchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { if (auditioning) onStopPreview() else onPreview(selected) },
            ) {
                Text(if (auditioning) "إيقاف" else "استمع")
            }
        }
    }

    if (alarmVolumeSilent) {
        Spacer(Modifier.height(Spacing.xs))
        Text(
            "صوت المنبّه مكتوم في جهازك، فلن تسمع التنبيه مهما اخترت هنا. ارفعه من إعدادات " +
                "الصوت في النظام.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** Below Android 12 exact alarms need no permission, so the gate does not exist. */
private fun canScheduleExactAlarms(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
    return manager?.canScheduleExactAlarms() == true
}

/**
 * True when the alarm stream is muted.
 *
 * Prayer alerts play on the alarm stream precisely so that silent mode does not swallow them, but
 * that stream has a volume of its own, and a user who has slid it to zero has silenced every alert
 * this app can make without touching anything in the app.
 */
private fun isAlarmVolumeSilent(context: Context): Boolean {
    val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
    return manager.getStreamVolume(AudioManager.STREAM_ALARM) == 0
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
/**
 * How much warning to give before each prayer.
 *
 * A row of choices rather than a stepper: the useful values are few and well known, and "off" has
 * to be one tap away — this is the setting a user turns off at the first alert they did not want.
 */
/**
 * The rule that decides Fajr and Isha where the sun never dips far enough below the horizon.
 *
 * Shown only above 48°, because below it the three rules differ by a minute or two and the choice
 * is noise. Above it they diverge by tens of minutes, and the app was picking for the user with no
 * way to disagree — for someone in Stockholm that is the setting that decides whether the times
 * are usable at all.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HighLatitudeSettings(
    selected: HighLatitudeRule?,
    recommended: HighLatitudeRule,
    onSelect: (HighLatitudeRule?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SectionLabel("خطوط العرض العليا")
        Card(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(Spacing.lg)) {
                Text(
                    (selected ?: recommended).arabicDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.md))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    MadhabChip("تلقائي", selected == null) { onSelect(null) }
                    HighLatitudeRule.entries.forEach { rule ->
                        MadhabChip(rule.arabicName, rule == selected) { onSelect(rule) }
                    }
                }
                if (selected == null) {
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        "المختار تلقائيًّا لموقعك: ${recommended.arabicName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreAdhanSettings(minutes: Int, onSet: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SectionLabel("تنبيه قبل الأذان")
        Card(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(Spacing.lg)) {
                Text(
                    if (minutes == 0) {
                        "لا تنبيه قبل الوقت"
                    } else {
                        "تنبيه قبل كل صلاة بـ$minutes دقيقة، بلا أذان"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.md))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    PrayerPreferences.PRE_ADHAN_CHOICES.forEach { choice ->
                        MadhabChip(
                            label = if (choice == 0) "بدون" else "$choice د",
                            selected = choice == minutes,
                        ) { onSet(choice) }
                    }
                }
            }
        }
    }
}

@Composable
private fun IqamaSettings(
    iqamaMinutes: Map<Prayer, Int>,
    onSet: (Prayer, Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SectionLabel("وقت الإقامة بعد الأذان")
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

/** One heading style for every section, so the settings read as a list rather than as a pile. */
@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = Spacing.xs),
    )
}
