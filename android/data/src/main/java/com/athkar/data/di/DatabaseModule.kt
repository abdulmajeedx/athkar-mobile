package com.athkar.data.di

import android.content.Context
import androidx.room.migration.Migration
import com.athkar.data.db.AppDatabase
import com.athkar.data.db.AthkarDbFactory
import com.athkar.data.db.DbKeyProvider
import com.athkar.data.db.dao.AdhkarDao
import com.athkar.data.db.dao.NotificationDao
import com.athkar.data.db.dao.SyncDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides the encrypted, single-source-of-truth Room database. The DB passphrase comes from
 * [DbKeyProvider] (implemented against Android Keystore / StrongBox in the security package), so
 * the key never lives in user-visible storage.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        keyProvider: DbKeyProvider,
    ): AppDatabase = AthkarDbFactory(context, keyProvider, AppDatabase.ALL_MIGRATIONS).build()

    @Provides
    fun provideAdhkarDao(db: AppDatabase): AdhkarDao = db.adhkarDao()
    @Provides
    fun provideSyncDao(db: AppDatabase): SyncDao = db.syncDao()
    @Provides
    fun provideNotificationDao(db: AppDatabase): NotificationDao = db.notificationDao()
}
