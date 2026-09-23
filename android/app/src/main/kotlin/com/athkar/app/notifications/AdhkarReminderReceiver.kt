package com.athkar.app.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.athkar.domain.DailyAdhkar
import com.athkar.domain.PrayerPreferencesRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Fires at a daily adhkar reminder: posts it, then re-registers the two-day window.
 *
 * The rescheduling is not optional. A user with the reminders on and every prayer alert off has no
 * prayer alarm firing to roll the window forward, so without it the reminders would stop two days
 * after the app was last opened.
 */
@AndroidEntryPoint
class AdhkarReminderReceiver : BroadcastReceiver() {

    @Inject lateinit var notifier: PrayerNotifier

    @Inject lateinit var scheduler: PrayerAlarmScheduler

    @Inject lateinit var preferencesRepository: PrayerPreferencesRepository

    override fun onReceive(context: Context, intent: Intent) {
        val kind = intent.getStringExtra(EXTRA_KIND)
            ?.let { name -> DailyAdhkar.entries.firstOrNull { it.name == name } }
            ?: return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val preferences = preferencesRepository.observe().first()
                // A cancelled alarm the OS still delivers must not remind someone who said stop.
                if (kind in preferences.adhkarReminders) notifier.notifyAdhkar(kind)
                scheduler.reschedule(preferences)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val EXTRA_KIND = "adhkar_kind"
    }
}
