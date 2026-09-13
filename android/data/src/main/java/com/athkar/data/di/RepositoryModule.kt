package com.athkar.data.di

import com.athkar.data.location.AndroidLocationSource
import com.athkar.data.prefs.AppearancePreferencesRepositoryImpl
import com.athkar.data.prefs.CompassCalibrationRepositoryImpl
import com.athkar.data.prefs.PrayerPreferencesRepositoryImpl
import com.athkar.data.prefs.TasbihRepositoryImpl
import com.athkar.data.repository.AdhkarRepositoryImpl
import com.athkar.data.repository.SettingsRepositoryImpl
import com.athkar.data.repository.SyncStateRepositoryImpl
import com.athkar.domain.AdhkarRepository
import com.athkar.domain.AppearancePreferencesRepository
import com.athkar.domain.CompassCalibrationRepository
import com.athkar.domain.Connectivity
import com.athkar.domain.DeviceLocationSource
import com.athkar.domain.PrayerPreferencesRepository
import com.athkar.domain.SettingsRepository
import com.athkar.domain.SyncStateRepository
import com.athkar.domain.TasbihRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAdhkarRepository(impl: AdhkarRepositoryImpl): AdhkarRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindSyncStateRepository(impl: SyncStateRepositoryImpl): SyncStateRepository

    @Binds
    @Singleton
    abstract fun bindPrayerPreferencesRepository(
        impl: PrayerPreferencesRepositoryImpl,
    ): PrayerPreferencesRepository

    @Binds
    @Singleton
    abstract fun bindAppearancePreferencesRepository(
        impl: AppearancePreferencesRepositoryImpl,
    ): AppearancePreferencesRepository

    @Binds
    @Singleton
    abstract fun bindTasbihRepository(impl: TasbihRepositoryImpl): TasbihRepository

    @Binds
    @Singleton
    abstract fun bindCompassCalibrationRepository(
        impl: CompassCalibrationRepositoryImpl,
    ): CompassCalibrationRepository

    @Binds
    @Singleton
    abstract fun bindDeviceLocationSource(impl: AndroidLocationSource): DeviceLocationSource

    companion object {
        @Provides
        @Singleton
        fun provideInitialConnectivity(): Connectivity = Connectivity.ONLINE

        @Provides
        @Singleton
        fun provideClock(): () -> Long = System::currentTimeMillis
    }
}
