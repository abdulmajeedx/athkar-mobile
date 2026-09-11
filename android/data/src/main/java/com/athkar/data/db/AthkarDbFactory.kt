package com.athkar.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Provides the encrypted Room database via SQLCipher (AES-256-GCM keyed by SQLCipher 4 defaults).
 *
 * The [DbKeyProvider] yields the raw 256-bit key from secure hardware (Android Keystore / StrongBox),
 * freshly provisioned when absent, so the key never resides in the DB process or on disk in plaintext.
 */
class AthkarDbFactory(
    private val context: Context,
    private val keyProvider: DbKeyProvider,
    private val migrations: Array<androidx.room.migration.Migration>,
) {
    fun build(): AppDatabase {
        // sqlcipher-android, not the retired android-database-sqlcipher: the old artifact ships
        // native libraries that are not 16 KB page-aligned, which Android 16 devices using 16 KB
        // pages cannot load at all.
        // sqlcipher-android does not load its native library on its own, unlike the retired
        // artifact's SQLiteDatabase.loadLibs(). Without this the first open fails with
        // UnsatisfiedLinkError: No implementation found for SQLiteConnection.nativeOpen.
        System.loadLibrary("sqlcipher")
        val factory = SupportOpenHelperFactory(keyProvider.provideKeyBytes())
        return Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .openHelperFactory(factory)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING) // WAL
            .addMigrations(*migrations)
            .build()
    }
}

/** Abstraction over the Android Keystore so the Data layer never touches key material directly. */
interface DbKeyProvider {
    /** Raw 256-bit key as a byte array. Provisioned in secure hardware on first use. */
    fun provideKeyBytes(): ByteArray
}
