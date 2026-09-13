package com.athkar.feature.tasbih

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.athkar.domain.Dhikr
import com.athkar.domain.TasbihRepository
import com.athkar.domain.TasbihState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives the tasbih.
 *
 * The count lives in memory and is mirrored to storage, rather than being read back from it: a tap
 * has to move the number on the next frame, and routing every one through a DataStore round trip
 * would make a counter that lags behind the finger — which for a device people use without looking
 * is the one unforgivable fault.
 */
@HiltViewModel
class TasbihViewModel @Inject constructor(
    private val repository: TasbihRepository,
) : ViewModel() {

    private val local = MutableStateFlow<TasbihState?>(null)

    val state: StateFlow<TasbihState> = local
        .let { flow ->
            kotlinx.coroutines.flow.combine(flow, repository.observe()) { inMemory, stored ->
                inMemory ?: stored
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TasbihState())

    /** True on the tap that completed a round, so the screen can mark it. */
    private val _roundJustCompleted = MutableStateFlow(0)
    val roundJustCompleted: StateFlow<Int> = _roundJustCompleted

    fun count() = update { current ->
        current.increment().also {
            if (it.rounds > current.rounds) _roundJustCompleted.value += 1
        }
    }

    fun reset() = update { it.reset() }

    fun select(dhikr: Dhikr) = update { it.select(dhikr) }

    fun retarget(target: Int) = update { it.retarget(target) }

    private fun update(transform: (TasbihState) -> TasbihState) {
        val next = transform(state.value)
        local.value = next
        viewModelScope.launch { repository.save(next) }
    }
}
