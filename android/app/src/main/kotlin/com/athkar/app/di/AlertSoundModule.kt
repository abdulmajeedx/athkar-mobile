package com.athkar.app.di

import com.athkar.app.notifications.AlertSoundPreviewImpl
import com.athkar.domain.AlertSoundPreview
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The recording and the player live in `:app`; the settings screen that offers a listen lives in
 * `:feature-prayer-times`, which `:app` depends on rather than the other way round. The port in
 * `:domain` is what lets the lower module ask for something the upper one provides.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AlertSoundModule {

    @Binds
    @Singleton
    abstract fun bindAlertSoundPreview(impl: AlertSoundPreviewImpl): AlertSoundPreview
}
