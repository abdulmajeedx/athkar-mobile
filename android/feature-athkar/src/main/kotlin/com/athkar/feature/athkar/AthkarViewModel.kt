package com.athkar.feature.athkar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.athkar.core.domain.AdhkarReminder
import com.athkar.designsystem.ReadingSize
import com.athkar.domain.AdhkarRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One chapter of the bundled collection. */
data class Chapter(
    val key: String,
    val title: String,
    val itemCount: Int,
)

/**
 * Drives the adhkar screens: the chapter index, and the readings inside one chapter.
 *
 * The chapters are rebuilt from the single list flow the repository already exposes rather than
 * stored in their own table — the collection is a few hundred rows, so grouping it in memory costs
 * nothing and spares the schema a second table and a migration.
 *
 * Repetition counters live here rather than in the database: they belong to *this sitting*, not to
 * the dhikr, and persisting them would mean yesterday's half-finished tasbih greeting the user this
 * morning.
 */
@HiltViewModel
class AthkarViewModel @Inject constructor(
    private val adhkarRepository: AdhkarRepository,
) : ViewModel() {

    data class UiState(
        val chapters: List<Chapter> = emptyList(),
        val favourites: List<AdhkarReminder> = emptyList(),
        val openChapter: Chapter? = null,
        val openItems: List<AdhkarReminder> = emptyList(),
        val query: String = "",
        val counters: Map<String, Int> = emptyMap(),
        val readingSize: ReadingSize = ReadingSize.MEDIUM,
        val isLoading: Boolean = true,
    )

    sealed interface Intent {
        data class OpenChapter(val key: String) : Intent
        object CloseChapter : Intent
        object OpenFavourites : Intent
        data class Search(val query: String) : Intent
        data class Count(val id: String, val target: Int) : Intent
        data class TogglePinned(val id: String) : Intent
        data class SetReadingSize(val size: ReadingSize) : Intent
    }

    private val openChapterKey = MutableStateFlow<String?>(null)
    private val query = MutableStateFlow("")
    private val counters = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val readingSize = MutableStateFlow(ReadingSize.MEDIUM)

    val uiState: StateFlow<UiState> = combine(
        adhkarRepository.observeAll(),
        openChapterKey,
        query,
        counters,
        readingSize,
    ) { items, chapterKey, searchQuery, counts, size ->
        // Chapter order comes from catOrder, which the bundle assigns as chapterIndex * 1000 + n,
        // so first-seen order over the sorted list is the published order of Hisn al-Muslim.
        val grouped = LinkedHashMap<String, MutableList<AdhkarReminder>>()
        items.sortedBy { it.catOrder ?: Int.MAX_VALUE }.forEach { item ->
            val key = item.chapterKey() ?: return@forEach
            grouped.getOrPut(key) { mutableListOf() }.add(item)
        }
        val chapters = grouped.map { (key, chapterItems) ->
            Chapter(
                key = key,
                title = chapterItems.first().title.orEmpty(),
                itemCount = chapterItems.size,
            )
        }
        val favourites = items.filter { it.pinned == true }
        val open = when (chapterKey) {
            null -> null
            FAVOURITES_KEY -> Chapter(FAVOURITES_KEY, FAVOURITES_TITLE, favourites.size)
            else -> chapters.firstOrNull { it.key == chapterKey }
        }

        UiState(
            chapters = chapters.filter { searchQuery.isBlank() || it.title.contains(searchQuery.trim()) },
            favourites = favourites,
            openChapter = open,
            openItems = when (chapterKey) {
                null -> emptyList()
                FAVOURITES_KEY -> favourites
                else -> grouped[chapterKey].orEmpty()
            },
            query = searchQuery,
            counters = counts,
            readingSize = size,
            isLoading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    fun dispatch(intent: Intent) {
        when (intent) {
            is Intent.OpenChapter -> openChapterKey.value = intent.key
            Intent.OpenFavourites -> openChapterKey.value = FAVOURITES_KEY
            Intent.CloseChapter -> openChapterKey.value = null
            is Intent.Search -> query.value = intent.query
            is Intent.Count -> count(intent.id, intent.target)
            is Intent.TogglePinned -> viewModelScope.launch { togglePinned(intent.id) }
            is Intent.SetReadingSize -> readingSize.value = intent.size
        }
    }

    /** Advances the tally, wrapping back to zero once the target has been reached. */
    private fun count(id: String, target: Int) {
        val current = counters.value[id] ?: 0
        val next = if (current >= target) 0 else current + 1
        counters.value = counters.value + (id to next)
    }

    private suspend fun togglePinned(id: String) {
        val current = adhkarRepository.getById(id) ?: return
        adhkarRepository.upsert(current.copy(pinned = !(current.pinned ?: false)))
    }

    /** The bundle stores exactly one chapter key per row in `times`. */
    private fun AdhkarReminder.chapterKey(): String? = times?.firstOrNull()

    companion object {
        const val FAVOURITES_KEY = "__favourites"
        const val FAVOURITES_TITLE = "المفضلة"
    }
}
