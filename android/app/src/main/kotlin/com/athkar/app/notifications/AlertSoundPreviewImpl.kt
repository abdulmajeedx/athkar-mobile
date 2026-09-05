package com.athkar.app.notifications

import android.content.Context
import android.util.Log
import com.athkar.domain.AlertSound
import com.athkar.domain.AlertSoundPreview
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plays an alert sound on demand, through the same service that plays it at a prayer time.
 *
 * Deliberately the same path rather than a second, simpler one: a preview that used its own player
 * would be able to sound perfect while the real thing was broken, which is the opposite of what a
 * preview is for.
 */
@Singleton
class AlertSoundPreviewImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : AlertSoundPreview {

    override fun play(sound: AlertSound) {
        if (sound == AlertSound.SILENT) return
        runCatching {
            context.startForegroundService(AdhanPlayerService.previewIntent(context, sound))
        }.onFailure {
            // The app is in the foreground when this is called, which is itself a licence to start
            // a foreground service, so this should not fail. If it does, silence is the failure.
            Log.w(TAG, "the preview could not be played", it)
        }
    }

    override fun stop() {
        runCatching { context.stopService(AdhanPlayerService.stopIntent(context)) }
    }

    private companion object {
        const val TAG = "AthkarAdhan"
    }
}
