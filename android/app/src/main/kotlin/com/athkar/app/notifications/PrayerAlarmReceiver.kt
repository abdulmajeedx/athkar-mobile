package com.athkar.app.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.athkar.core.prayer.Prayer
import com.athkar.domain.PrayerPreferencesRepository
import dagger.hilt.android.AndroidEntryPoint
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Fires at a prayer time: posts the alert, then re-registers the two-day window so the schedule
 * keeps rolling forward without the app ever being opened.
 */
@AndroidEntryPoint
class PrayerAlarmReceiver : BroadcastReceiver() {

    @Inject lateinit var notifier: PrayerNotifier

    @Inject lateinit var scheduler: PrayerAlarmScheduler

    @Inject lateinit var preferencesRepository: PrayerPreferencesRepository

    override fun onReceive(context: Context, intent: Intent) {
        val prayer = intent.getStringExtra(EXTRA_PRAYER)
            ?.let { name -> Prayer.entries.firstOrNull { it.name == name } }
            ?: return
        val atMillis = intent.getLongExtra(EXTRA_AT_MILLIS, 0L)

        // The broadcast has ~10 seconds of foreground time; goAsync buys the rest for the reads.
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val preferences = preferencesRepository.observe().first()
                // Settings can change between scheduling and firing; a cancelled alarm that the OS
                // still delivers must not produce an alert the user has since turned off.
                if (preferences.notificationsEnabled && prayer in preferences.notifiedPrayers) {
                    val at = if (atMillis > 0) Instant.ofEpochMilli(atMillis) else Instant.now()
                    notifier.notifyPrayer(
                        prayer = prayer,
                        formattedTime = formatTime(at),
                        placeName = preferences.place?.name,
                    )
                }
                scheduler.reschedule(preferences)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun formatTime(at: Instant): String {
        val local = at.atZone(ZoneId.systemDefault())
        val hour12 = if (local.hour % 12 == 0) 12 else local.hour % 12
        val suffix = if (local.hour < 12) "ص" else "م"
        return "%d:%02d %s".format(hour12, local.minute, suffix)
    }

    companion object {
        const val EXTRA_PRAYER = "prayer"
        const val EXTRA_AT_MILLIS = "at_millis"
    }
}
