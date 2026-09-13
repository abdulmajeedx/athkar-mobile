package com.athkar.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.athkar.designsystem.AppTheme
import com.athkar.designsystem.ReadingSize
import com.athkar.designsystem.Sizing
import com.athkar.designsystem.SkyPhase
import com.athkar.designsystem.Spacing
import com.athkar.feature.prayertimes.PrayerSettingsRoute

/**
 * Everything adjustable in the app, in one place.
 *
 * It was in three: a gear on the prayer header for the times and the adhan, a type menu in the
 * reader's toolbar for the font, and nowhere at all for the theme. Someone looking for a setting had
 * to already know which screen owned it, which is the same as not having settings. A tab costs one
 * slot in the bar and ends the hunt — and unlike a banner pinned across every screen, it takes no
 * room away from the adhkar on the screens that are not it.
 */
@Composable
fun SettingsRoute(viewModel: AppearanceViewModel = hiltViewModel()) {
    val chrome by viewModel.chrome.collectAsStateWithLifecycle()
    var showPrayerSettings by remember { mutableStateOf(false) }

    if (showPrayerSettings) {
        // System back leaves the prayer settings before it leaves the tab.
        BackHandler { showPrayerSettings = false }
        PrayerSettingsRoute(onBack = { showPrayerSettings = false })
        return
    }

    SettingsScreen(
        theme = chrome.theme,
        sky = chrome.sky,
        readingSize = chrome.readingSize,
        onSelectTheme = viewModel::setTheme,
        onSelectReadingSize = viewModel::setReadingSize,
        onOpenPrayerSettings = { showPrayerSettings = true },
    )
}

@Composable
private fun SettingsScreen(
    theme: AppTheme,
    sky: SkyPhase,
    readingSize: ReadingSize,
    onSelectTheme: (AppTheme) -> Unit,
    onSelectReadingSize: (ReadingSize) -> Unit,
    onOpenPrayerSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Spacer(Modifier.height(Spacing.lg))
        Text("الإعدادات", style = MaterialTheme.typography.headlineSmall)

        ThemeSettings(theme = theme, sky = sky, onSelect = onSelectTheme)
        ReadingSettings(size = readingSize, onSelect = onSelectReadingSize)
        PrayerSettingsRow(onOpen = onOpenPrayerSettings)
        About()

        Spacer(Modifier.height(Spacing.xxl))
    }
}

/**
 * The theme, previewed rather than described.
 *
 * "By time" means nothing as a sentence — the swatches under it are the six skies the app will
 * actually wear, with the current hour marked, so the choice is made by looking.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemeSettings(theme: AppTheme, sky: SkyPhase, onSelect: (AppTheme) -> Unit) {
    SettingsCard {
        Column(Modifier.padding(Spacing.lg)) {
            Text("المظهر", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(Spacing.sm))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                AppTheme.entries.forEach { option ->
                    FilterChip(
                        selected = option == theme,
                        onClick = { onSelect(option) },
                        label = { Text(option.label) },
                        leadingIcon = if (option == theme) {
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
            }
            Spacer(Modifier.height(Spacing.sm))
            Text(
                theme.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (theme == AppTheme.BY_TIME) {
                Spacer(Modifier.height(Spacing.md))
                SkyStrip(current = sky)
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    "الآن: ${sky.arabicName}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** The day's skies in a row, the one in force marked with its own accent. */
@Composable
private fun SkyStrip(current: SkyPhase) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        SkyPhase.entries.forEach { phase ->
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(Spacing.xxxl)
                        .clip(MaterialTheme.shapes.small)
                        .background(Brush.verticalGradient(listOf(phase.top, phase.bottom))),
                    contentAlignment = Alignment.Center,
                ) {
                    if (phase == current) {
                        Box(
                            Modifier
                                .size(Spacing.sm)
                                .clip(CircleShape)
                                .background(phase.accent),
                        )
                    }
                }
                Text(
                    phase.arabicName,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = if (phase == current) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Reading size, still in the reader's toolbar too — this is the copy that can be found. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReadingSettings(size: ReadingSize, onSelect: (ReadingSize) -> Unit) {
    SettingsCard {
        Column(Modifier.padding(Spacing.lg)) {
            Text("حجم قراءة الأذكار", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(Spacing.sm))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                ReadingSize.entries.forEach { option ->
                    FilterChip(
                        selected = option == size,
                        onClick = { onSelect(option) },
                        label = { Text(option.label) },
                    )
                }
            }
            Spacer(Modifier.height(Spacing.md))
            // Set at the chosen size: the setting shows its own effect rather than a label for it.
            Text(
                "رَبَّنَا آتِنَا فِي الدُّنْيَا حَسَنَةً",
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = size.fontSp.sp,
                    lineHeight = size.lineHeightSp.sp,
                ),
            )
        }
    }
}

@Composable
private fun PrayerSettingsRow(onOpen: () -> Unit) {
    SettingsCard(onClick = onOpen) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("الصلاة والتنبيهات", style = MaterialTheme.typography.titleMedium)
                Text(
                    "طريقة الحساب، والمذهب، وصوت الأذان، والتنبيه قبل الوقت، ووقت الإقامة.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Sizing.iconMd),
            )
        }
    }
}

@Composable
private fun About() {
    SettingsCard {
        Column(Modifier.padding(Spacing.lg)) {
            Text("عن أذكاري", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(Spacing.xs))
            Text(
                "الإصدار ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                "يعمل التطبيق دون إنترنت: المواقيت والقبلة تُحسبان على جهازك، والأذكار مخزّنة فيه، " +
                    "ولا يُرسل موقعك إلى أي خادم.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsCard(onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        content()
    }
}
