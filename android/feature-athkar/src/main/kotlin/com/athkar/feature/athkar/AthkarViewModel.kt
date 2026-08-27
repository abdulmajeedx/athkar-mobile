package com.athkar.feature.athkar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.athkar.core.domain.AdhkarReminder
import com.athkar.core.clock.Hlc
import com.athkar.domain.AdhkarRepository
import com.athkar.domain.SyncStateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Unidirectional data flow for the adhkar screen: an immutable [UiState] is derived by combining the
 * local repository flows (single source of truth) with sync status; user actions flow through the
 * closed [Intent] types. Every state transition is observable and therefore replayable in debug.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AthkarViewModel @Inject constructor(
    private val adhkarRepository: AdhkarRepository,
    syncStateRepository: SyncStateRepository,
) : ViewModel() {

    data class UiState(
        val items: List<AdhkarReminder> = emptyList(),
        val isLoading: Boolean = true,
        val lastSyncAtMillis: Long? = null,
        val isOffline: Boolean = false,
        val error: String? = null,
    )

    sealed interface Intent {
        data class TogglePinned(val id: String) : Intent
        data class StartDhikr(val id: String) : Intent
        data class Create(val title: String, val body: String, val targetCount: Int) : Intent
        data class Delete(val id: String) : Intent
        object Refresh : Intent
    }

    val uiState: StateFlow<UiState> = combine(
        adhkarRepository.observeAll(),
        syncStateRepository.observeState(),
    ) { items, sync ->
        UiState(
            items = items,
            isLoading = false,
            lastSyncAtMillis = sync.lastSyncAtMillis,
            isOffline = sync.connectivity == com.athkar.domain.Connectivity.OFFLINE,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    private val _events = MutableStateFlow<Intent?>(null)
    val events: StateFlow<Intent?> = _events

    fun dispatch(intent: Intent) {
        _events.value = null
        when (intent) {
            is Intent.TogglePinned -> viewModelScope.launch { togglePinned(intent.id) }
            is Intent.Delete -> viewModelScope.launch { adhkarRepository.delete(intent.id) }
            is Intent.Create -> viewModelScope.launch { create(intent.title, intent.body, intent.targetCount) }
            Intent.Refresh -> Unit // sync handled by WorkManager / pull-to-refresh
            is Intent.StartDhikr -> Unit
        }
    }

    private suspend fun togglePinned(id: String) {
        val current = adhkarRepository.getById(id) ?: return
        val updated = current.copy(pinned = !(current.pinned ?: false))
        adhkarRepository.upsert(updated)
    }

    private suspend fun create(title: String, body: String, targetCount: Int) {
        val id = com.athkar.core.crdt.UuidV7.random()
        val reminder = AdhkarReminder(
            id = id, title = title, body = body, targetCount = targetCount,
            times = setOf("morning", "evening"), catOrder = 0, pinned = false,
            hlc = Hlc.fromWallMillis(System.currentTimeMillis()), writerId = "local",
        )
        adhkarRepository.upsert(reminder)
    }
}
