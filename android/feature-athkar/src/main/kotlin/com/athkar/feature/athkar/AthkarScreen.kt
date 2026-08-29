package com.athkar.feature.athkar

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.athkar.core.domain.AdhkarReminder
import com.athkar.designsystem.LocalAthkarAccents
import com.athkar.designsystem.PatternedSurface
import com.athkar.designsystem.SkyPhase
import com.athkar.designsystem.ReadingSize
import com.athkar.designsystem.Sizing
import com.athkar.designsystem.Spacing
import com.athkar.feature.athkar.AthkarViewModel.Intent
import com.athkar.feature.athkar.AthkarViewModel.UiState

/**
 * The bundled collection, two levels deep: the chapter index, then the readings of one chapter.
 *
 * A flat list is the obvious alternative and the wrong one — the collection runs to hundreds of
 * readings across 132 chapters, and finding "أذكار النوم" by scrolling past all of them is not
 * finding it. Tapping a reading advances its tally and wraps back to zero at the target, which is
 * the whole interaction the app exists for.
 */
@Composable
fun AthkarRoute(viewModel: AthkarViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    AthkarScreen(state = state, onIntent = viewModel::dispatch)
}

@Composable
private fun AthkarScreen(state: UiState, onIntent: (Intent) -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            state.isLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator()
            }

            state.openChapter != null -> {
                // System back closes the chapter before it leaves the tab.
                BackHandler { onIntent(Intent.CloseChapter) }
                ChapterScreen(state = state, onIntent = onIntent)
            }

            else -> ChapterIndex(state = state, onIntent = onIntent)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChapterIndex(state: UiState, onIntent: (Intent) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        // The same patterned sky as the prayer screen, so opening a chapter does not feel like
        // arriving in a different app.
        PatternedSurface(
            sky = SkyPhase.NIGHT,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.xl),
            ) {
                Text(
                    "الأذكار",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                )
                Text(
                    "حصن المسلم",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.65f),
                )
            }
        }

        OutlinedTextField(
            value = state.query,
            onValueChange = { onIntent(Intent.Search(it)) },
            label = { Text("ابحث في الأبواب") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        )

        if (state.chapters.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(Spacing.xxl),
                Alignment.Center,
            ) {
                Text(
                    "لا يوجد باب يطابق بحثك",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            return@Column
        }

        LazyColumn(
            contentPadding = PaddingValues(
                start = Spacing.lg,
                end = Spacing.lg,
                bottom = Spacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            if (state.favourites.isNotEmpty() && state.query.isBlank()) {
                item(key = AthkarViewModel.FAVOURITES_KEY) {
                    ChapterRow(
                        title = AthkarViewModel.FAVOURITES_TITLE,
                        count = state.favourites.size,
                        highlighted = true,
                        onClick = { onIntent(Intent.OpenFavourites) },
                    )
                }
            }
            items(state.chapters, key = { it.key }) { chapter ->
                ChapterRow(
                    title = chapter.title,
                    count = chapter.itemCount,
                    highlighted = false,
                    onClick = { onIntent(Intent.OpenChapter(chapter.key)) },
                )
            }
        }
    }
}

@Composable
private fun ChapterRow(title: String, count: Int, highlighted: Boolean, onClick: () -> Unit) {
    val accents = LocalAthkarAccents.current
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (highlighted) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (highlighted) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = null,
                    tint = accents.gold,
                    modifier = Modifier.size(Sizing.iconSm),
                )
                Spacer(Modifier.width(Spacing.sm))
            }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                count.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChapterScreen(state: UiState, onIntent: (Intent) -> Unit) {
    val chapter = state.openChapter ?: return
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(chapter.title, style = MaterialTheme.typography.titleMedium) },
            navigationIcon = {
                IconButton(onClick = { onIntent(Intent.CloseChapter) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                }
            },
            actions = {
                ReadingSizeMenu(
                    selected = state.readingSize,
                    onSelect = { onIntent(Intent.SetReadingSize(it)) },
                )
            },
        )

        if (state.openItems.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(Spacing.xxl),
                Alignment.Center,
            ) {
                Text(
                    "لم تضف أي ذكر إلى المفضلة بعد",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            return@Column
        }

        LazyColumn(
            contentPadding = PaddingValues(
                start = Spacing.lg,
                end = Spacing.lg,
                bottom = Spacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            items(state.openItems, key = { it.id }) { item ->
                DhikrCard(
                    item = item,
                    readingSize = state.readingSize,
                    count = state.counters[item.id] ?: 0,
                    onCount = { onIntent(Intent.Count(item.id, item.targetCount ?: 1)) },
                    onTogglePin = { onIntent(Intent.TogglePinned(item.id)) },
                )
            }
        }
    }
}

@Composable
private fun DhikrCard(
    item: AdhkarReminder,
    readingSize: ReadingSize,
    count: Int,
    onCount: () -> Unit,
    onTogglePin: () -> Unit,
) {
    val accents = LocalAthkarAccents.current
    val target = item.targetCount ?: 1
    val isComplete = count >= target

    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (isComplete) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onCount),
    ) {
        Column(Modifier.padding(Spacing.lg)) {
            Text(
                text = item.body ?: item.title.orEmpty(),
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = readingSize.fontSp.sp,
                    lineHeight = readingSize.lineHeightSp.sp,
                    // Content direction is resolved from the text itself: a supplication that opens
                    // with a Latin-scripted name would otherwise flip the whole paragraph.
                    textDirection = TextDirection.Content,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(Modifier.height(Spacing.md))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (target > 1) "$count / $target" else "$count",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isComplete) accents.gold else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(Spacing.md))
                LinearProgressIndicator(
                    progress = { (count.toFloat() / target.coerceAtLeast(1)).coerceIn(0f, 1f) },
                    color = if (isComplete) accents.gold else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp),
                )
                IconButton(onClick = onTogglePin, modifier = Modifier.size(Sizing.touchTarget)) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = if (item.pinned == true) {
                            "إزالة من المفضلة"
                        } else {
                            "إضافة إلى المفضلة"
                        },
                        tint = if (item.pinned == true) {
                            accents.gold
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                    )
                }
            }
        }
    }
}

/** Reading size, in the bar where the reader is rather than buried in a settings screen. */
@Composable
private fun ReadingSizeMenu(selected: ReadingSize, onSelect: (ReadingSize) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(Sizing.touchTarget)) {
            Icon(Icons.Default.TextFields, contentDescription = "حجم الخط")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ReadingSize.entries.forEach { size ->
                DropdownMenuItem(
                    text = {
                        Text(
                            size.label,
                            // Each option is shown at the size it sets, so the choice is made by
                            // looking rather than by guessing what a label means.
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = size.fontSp.sp),
                            color = if (size == selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    },
                    onClick = {
                        onSelect(size)
                        expanded = false
                    },
                )
            }
        }
    }
}
