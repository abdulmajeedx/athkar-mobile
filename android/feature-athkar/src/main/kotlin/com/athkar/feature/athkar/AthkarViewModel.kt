package com.athkar.feature.athkar

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.athkar.core.domain.AdhkarReminder
import com.athkar.designsystem.ReadingSize
import com.athkar.domain.AdhkarRepository
import com.athkar.domain.ReadingPreferencesRepository
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
 * Repetition counters stay out of the database: they belong to *this sitting*, not to the dhikr,
 * and storing them would mean yesterday's half-finished tasbih greeting the user this morning. They
 * do live in saved state, which is a different thing — it survives Android reclaiming the process
 * mid-reading, and dies with the sitting as intended.
 */
@HiltViewModel
class AthkarViewModel @Inject constructor(
    private val adhkarRepository: AdhkarRepository,
    private val readingPreferences: ReadingPreferencesRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    data class UiState(
        val chapters: List<Chapter> = emptyList(),
        val favourites: List<AdhkarReminder> = emptyList(),
        val openChapter: Chapter? = null,
        val openItems: List<AdhkarReminder> = emptyList(),
        val query: String = "",
        val counters: Map<String, Int> = emptyMap(),
        val readingSize: ReadingSize = ReadingSize.MEDIUM,
        /** False when the corpus itself is missing, as opposed to filtered away by a search. */
        val hasAnyContent: Boolean = true,
        val isLoading: Boolean = true,
    )

    sealed interface Intent {
        data class OpenChapter(val key: String) : Intent
        object CloseChapter : Intent
        object OpenFavourites : Intent
        data class Search(val query: String) : Intent
        data class Count(val id: String, val target: Int) : Intent

        data class ResetCount(val id: String) : Intent
        data class TogglePinned(val id: String) : Intent
        data class SetReadingSize(val size: ReadingSize) : Intent
    }

    private val openChapterKey = MutableStateFlow<String?>(savedState[KEY_OPEN_CHAPTER])
    private val query = MutableStateFlow("")

    /**
     * The tallies of the current sitting.
     *
     * Still not in the database — a count belongs to the sitting, not to the app — but held in
     * saved state rather than plain memory, because Android reclaiming the process during a
     * fifteen-minute reading interrupted by a phone call is not the user starting a new sitting.
     * It is the same sitting, and the count should still be there.
     */
    private val counters = MutableStateFlow<Map<String, Int>>(
        savedState.get<HashMap<String, Int>>(KEY_COUNTERS).orEmpty(),
    )

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
            hasAnyContent = chapters.isNotEmpty(),
            isLoading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    init {
        // The reading size is a preference, not sitting state: it was the one setting in the app
        // that a restart threw away, sending the reader back to the type menu every time.
        viewModelScope.launch {
            readingPreferences.observeReadingSizeName().collect { stored ->
                readingSize.value = ReadingSize.entries.firstOrNull { it.name == stored }
                    ?: ReadingSize.MEDIUM
            }
        }
    }

    fun dispatch(intent: Intent) {
        when (intent) {
            is Intent.OpenChapter -> openChapter(intent.key)
            Intent.OpenFavourites -> openChapter(FAVOURITES_KEY)
            Intent.CloseChapter -> openChapter(null)
            is Intent.Search -> query.value = intent.query
            is Intent.Count -> count(intent.id, intent.target)
            is Intent.ResetCount -> resetCount(intent.id)
            is Intent.TogglePinned -> viewModelScope.launch { togglePinned(intent.id) }
            is Intent.SetReadingSize -> viewModelScope.launch {
                readingPreferences.setReadingSizeName(intent.size.name)
            }
        }
    }

    private fun openChapter(key: String?) {
        openChapterKey.value = key
        savedState[KEY_OPEN_CHAPTER] = key
    }

    /**
     * Advances the tally, and stops at the target.
     *
     * It used to wrap back to zero on the next tap, which meant the tap that completed a tasbih of
     * thirty-three and the tap that destroyed it were the same tap on the same target — and the
     * counting is done rhythmically, without looking. Starting over is now a deliberate gesture of
     * its own, [Intent.ResetCount].
     */
    private fun count(id: String, target: Int) {
        val current = counters.value[id] ?: 0
        if (current >= target) return
        publishCounters(counters.value + (id to current + 1))
    }

    private fun resetCount(id: String) {
        publishCounters(counters.value - id)
    }

    private fun publishCounters(next: Map<String, Int>) {
        counters.value = next
        // HashMap rather than the read-only view: saved state is a Bundle, which needs something
        // it knows how to write.
        savedState[KEY_COUNTERS] = HashMap(next)
    }

    private suspend fun togglePinned(id: String) {
        val current = adhkarRepository.getById(id) ?: return
        adhkarRepository.upsert(current.copy(pinned = !(current.pinned ?: false)))
    }

    /** The bundle stores exactly one chapter key per row in `times`. */
    private fun AdhkarReminder.chapterKey(): String? = times?.firstOrNull()

    companion object {
        const val FAVOURITES_KEY = "__favourites"
        private const val KEY_COUNTERS = "counters"
        private const val KEY_OPEN_CHAPTER = "open_chapter"
        const val FAVOURITES_TITLE = "المفضلة"
    }
}
