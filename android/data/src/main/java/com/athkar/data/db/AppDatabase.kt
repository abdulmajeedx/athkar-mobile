package com.athkar.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.athkar.data.db.dao.AdhkarDao
import com.athkar.data.db.dao.NotificationDao
import com.athkar.data.db.dao.SyncDao
import com.athkar.data.db.entity.AdhkarEntity
import com.athkar.data.db.entity.NotificationEntity
import com.athkar.data.db.entity.OutboxEntity
import com.athkar.data.db.entity.SettingEntity
import com.athkar.data.db.entity.SyncCursorEntity
import com.athkar.data.db.entity.TombstoneEntity

/**
 * SQLite schema (WAL mode). The local DB is the single source of truth; every screen reads reactive
 * flows from here. Password is provided at open time by the security module (SQLCipher), which is
 * the only entity allowed to touch the key material.
 *
 * Migration numbering is sequential; migration tests use Room's MigrationTestHelper in :data
 * androidTest and fail the build if any in-memory schema diff is detected.
 */
@Database(
    entities = [
        AdhkarEntity::class,
        OutboxEntity::class,
        TombstoneEntity::class,
        SyncCursorEntity::class,
        SettingEntity::class,
        NotificationEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun adhkarDao(): AdhkarDao
    abstract fun syncDao(): SyncDao
    abstract fun notificationDao(): NotificationDao

    companion object {
        const val NAME = "athkar.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS tombstones (" +
                        "entityId TEXT NOT NULL PRIMARY KEY, " +
                        "tombstonedAtMillis INTEGER NOT NULL, serverHlc INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_tombstones_time ON tombstones(tombstonedAtMillis)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS notifications (" +
                        "id TEXT NOT NULL PRIMARY KEY, type TEXT NOT NULL, title TEXT NOT NULL, " +
                        "body TEXT NOT NULL, imageUrl TEXT, deepLink TEXT, threadId TEXT, " +
                        "actionCount INTEGER NOT NULL DEFAULT 0, isRead INTEGER NOT NULL DEFAULT 0, " +
                        "receivedAtMillis INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_notifications_read ON notifications(isRead)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_notifications_time ON notifications(receivedAtMillis)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_notifications_thread ON notifications(threadId)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS settings (" +
                        "key TEXT NOT NULL PRIMARY KEY, valueJson TEXT, hlc INTEGER NOT NULL, " +
                        "writerId TEXT NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_settings_hlc ON settings(hlc)")
            }
        }

        val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
    }
}
