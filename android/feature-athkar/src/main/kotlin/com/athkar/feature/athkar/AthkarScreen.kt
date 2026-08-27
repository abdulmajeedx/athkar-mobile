package com.athkar.feature.athkar

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.athkar.core.domain.AdhkarReminder
import com.athkar.feature.athkar.AthkarViewModel.Intent
import com.athkar.feature.athkar.AthkarViewModel.UiState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Adhkar (remembrances) home screen. Renders the four mandatory states — skeleton, empty, error,
 * success — plus a persistent offline banner showing locally-held data. The screen reads only the
 * local reactive [UiState] flow; it never touches a network response. All colors/dimensions come
 * from [Tokens]. Touch targets >= 48dp with >= 8dp separation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AthkarRoute(
    onBack: () -> Unit,
    viewModel: AthkarViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    AthkarScreen(
        state = state,
        onIntent = viewModel::dispatch,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AthkarScreen(
    state: UiState,
    onIntent: (Intent) -> Unit,
    onBack: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = Tokens.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("الأذكار", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                    }
                },
            )

            if (state.isOffline) {
                OfflineBanner(lastSyncAt = state.lastSyncAtMillis)
            }

            when {
                state.isLoading -> Skeleton()
                state.error != null -> ErrorState(state.error!!, onRetry = { onIntent(Intent.Refresh) })
                state.items.isEmpty() -> EmptyState(onCreate = { })
                else -> ListState(state.items, onIntent)
            }
        }
    }
}

@Composable
private fun OfflineBanner(lastSyncAt: Long?) {
    Surface(color = Tokens.offlineAmber) {
        Text(
            text = "وضع عدم الاتصال — آخر مزامنة: ${lastSyncAt?.let { java.text.SimpleDateFormat("HH:mm").format(java.util.Date(it)) } ?: "—"}",
            color = Tokens.onPrimary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(Tokens.sp2),
        )
    }
}

@Composable
private fun Skeleton() {
    Column(modifier = Modifier.padding(Tokens.sp4), verticalArrangement = Arrangement.spacedBy(Tokens.sp4)) {
        repeat(6) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(Tokens.sp8)
                    .padding(Tokens.sp1),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(modifier = Modifier.size(Tokens.sp4)) }
        }
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(Tokens.sp6),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, style = MaterialTheme.typography.bodyLarge, color = Tokens.textSecondary)
        Spacer(Modifier.height(Tokens.sp4))
        Button(onClick = onRetry) { Text("إعادة المحاولة") }
    }
}

@Composable
private fun EmptyState(onCreate: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(Tokens.sp6),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("لا توجد أذكار بعد", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(Tokens.sp2))
        Text("أضف ذكرك الأول للبدء", color = Tokens.textSecondary)
        Spacer(Modifier.height(Tokens.sp4))
        Button(onClick = onCreate) { Text("أضف ذكر") }
    }
}

@Composable
private fun ListState(items: List<AdhkarReminder>, onIntent: (Intent) -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(Tokens.sp4),
        verticalArrangement = Arrangement.spacedBy(Tokens.sp2),
    ) {
        items(items, key = { it.id }) { item ->
            ReminderRow(item) {
                onIntent(Intent.TogglePinned(item.id))
            }
        }
    }
}

@Composable
private fun ReminderRow(item: AdhkarReminder, onTogglePin: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(Tokens.elev1),
        color = Tokens.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(Tokens.sp4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(item.title ?: "بلا عنوان", style = MaterialTheme.typography.titleMedium)
                item.body?.let { body ->
                    Text(
                        body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tokens.textSecondary,
                        maxLines = 2,
                    )
                }
                item.targetCount?.let { count ->
                    Text("التكرار: $count", style = MaterialTheme.typography.bodySmall, color = Tokens.textSecondary)
                }
            }
            IconButton(
                onClick = onTogglePin,
                modifier = Modifier.size(Tokens.touchTarget),
            ) {
                Icon(
                    Icons.Default.PushPin,
                    contentDescription = if (item.pinned == true) "إلغاء التثبيت" else "تثبيت",
                    tint = if (item.pinned == true) Tokens.primary else Tokens.textSecondary,
                )
            }
        }
    }
}
