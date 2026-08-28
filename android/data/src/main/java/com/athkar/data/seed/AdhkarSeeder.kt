package com.athkar.data.seed

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.athkar.core.clock.Hlc
import com.athkar.data.db.dao.AdhkarDao
import com.athkar.data.db.entity.AdhkarEntity
import com.athkar.data.db.entity.TimesCodec
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.contentDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "athkar_content",
)

/**
 * Loads the bundled adhkar — the full text of *Hisn al-Muslim*, 132 chapters — into the database.
 *
 * Rows are written straight through the DAO rather than the repository: bundled content is not a
 * user edit, so it must not enter the sync outbox and be replayed to a server as one. Every row
 * carries [SEED_WRITER], which is also what makes a content update safe: a newer bundle replaces
 * only rows this seeder wrote.
 */
@Singleton
class AdhkarSeeder @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val adhkarDao: AdhkarDao,
) {

    /**
     * Installs the bundled adhkar when the database is empty or when a newer bundle ships with an
     * app update, and returns how many rows were written.
     *
     * The stored version is the guard rather than emptiness alone: without it, an update that fixes
     * or extends the text would never reach anyone who already had the old set.
     */
    suspend fun seedIfNeeded(nowMillis: Long = System.currentTimeMillis()): Int {
        val bundle = readBundled()
        val installedVersion = context.contentDataStore.data.first()[KEY_SEED_VERSION] ?: 0
        val databaseIsEmpty = adhkarDao.count() == 0
        if (!databaseIsEmpty && installedVersion >= bundle.version) return 0

        val titles = bundle.categories.associate { it.key to it.title }
        // Replacing the bundle would otherwise silently drop every favourite the user has marked.
        val pinned = adhkarDao.pinnedIds().toSet()
        val entities = bundle.items.map { item ->
            item.toEntity(titles[item.category].orEmpty(), nowMillis)
                .let { if (it.id in pinned) it.copy(pinned = true) else it }
        }

        adhkarDao.deleteByWriter(SEED_WRITER)
        adhkarDao.insertAll(entities)
        context.contentDataStore.edit { it[KEY_SEED_VERSION] = bundle.version }
        return entities.size
    }

    private fun readBundled(): SeedFile {
        val raw = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        return json.decodeFromString(SeedFile.serializer(), raw)
    }

    @Serializable
    private data class SeedFile(
        val version: Int,
        val source: SeedSource,
        val categories: List<SeedCategory>,
        val items: List<SeedItem>,
    )

    @Serializable
    private data class SeedSource(val name: String, val url: String, val fetchedAt: String)

    @Serializable
    private data class SeedCategory(val key: String, val title: String, val order: Int, val count: Int)

    @Serializable
    private data class SeedItem(
        val id: String,
        val category: String,
        val body: String,
        val count: Int,
        @SerialName("order") val order: Int,
    ) {
        /**
         * The chapter title is stored on every row and the chapter key in `times`, so the screen can
         * rebuild the 132 chapters from the one list flow it already observes — no second table, no
         * migration, no join.
         */
        fun toEntity(categoryTitle: String, nowMillis: Long) = AdhkarEntity(
            id = id,
            title = categoryTitle,
            body = body,
            targetCount = count,
            timesJson = TimesCodec.encode(setOf(category)),
            catOrder = order,
            pinned = false,
            // HLC zero means "never seen by a server", so any synced copy outranks bundled content.
            serverHlc = Hlc.decode(0L).value,
            writerId = SEED_WRITER,
            tombstoned = false,
            updatedAtMillis = nowMillis,
        )
    }

    companion object {
        const val SEED_WRITER = "bundled"
        private const val ASSET_NAME = "athkar_seed.json"
        private val KEY_SEED_VERSION = intPreferencesKey("seed_version")
        private val json = Json { ignoreUnknownKeys = true }
    }
}
