package com.athkar.app.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.athkar.core.prayer.Prayer
import com.athkar.domain.AlertSound
import com.athkar.domain.PrayerPreferences
import com.athkar.domain.PrayerPreferencesRepository
import dagger.hilt.android.AndroidEntryPoint
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Fires at a prayer time: sounds the alert, then re-registers the two-day window so the schedule
 * keeps rolling forward without the app ever being opened.
 *
 * The order of the two halves matters. Android allows an app to start a foreground service for ten
 * seconds after it delivers an exact alarm, and reading a preference off disk in a process that has
 * just been woken can take a meaningful share of that. So the adhan is started first, from what the
 * alarm itself carries, and everything that can wait waits.
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
        val at = if (atMillis > 0) Instant.ofEpochMilli(atMillis) else Instant.now()
        val formattedTime = formatTime(at)
        val placeName = intent.getStringExtra(EXTRA_PLACE_NAME)

        // What the alarm was registered with, which is also what the system based its foreground
        // allowance on. Asking the current settings here instead would be asking a different
        // question than the one the platform already answered for this broadcast.
        val minutesBefore = intent.getIntExtra(EXTRA_MINUTES_BEFORE, 0)
        val scheduledSound = AlertSound.fromName(intent.getStringExtra(EXTRA_ALERT_SOUND))
            .forPrayer(prayer)
        val wasExact = intent.getBooleanExtra(EXTRA_WAS_EXACT, false)

        // A warning never raises the adhan, whatever the sound setting says: the call belongs to
        // the time itself, and sounding it early announces a prayer whose time has not come.
        val playing = minutesBefore == 0 &&
            scheduledSound == AlertSound.ADHAN &&
            wasExact &&
            startAdhan(context, prayer, formattedTime, placeName)

        // The broadcast has seconds of guaranteed life; goAsync buys the rest for the reads.
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val preferences = preferencesRepository.observe().first()
                // Settings can change between scheduling and firing; a cancelled alarm that the OS
                // still delivers must not produce an alert the user has since turned off.
                val wanted = preferences.notificationsEnabled && prayer in preferences.notifiedPrayers
                val sound = preferences.alertSound.forPrayer(prayer)

                when {
                    // The warning is its own, quieter thing: it says a time is coming, so it never
                    // sounds the adhan and never claims the time has arrived.
                    minutesBefore > 0 -> {
                        if (wanted && preferences.preAdhanMinutes > 0) {
                            notifier.notifyPreAdhan(
                                prayer = prayer,
                                minutesBefore = minutesBefore,
                                formattedTime = formattedTime,
                            )
                        }
                    }

                    !wanted || (playing && sound != AlertSound.ADHAN) -> {
                        // Either the alert is no longer wanted at all, or it is wanted but no
                        // longer as the adhan. Both mean the recording that has just started is
                        // wrong, and stopping a service is allowed from anywhere.
                        if (playing) stopAdhan(context)
                        if (wanted) {
                            notifier.notifyPrayer(prayer, formattedTime, place(placeName, preferences), sound)
                        }
                    }

                    // The service owns the notification while it is sounding, and leaves it behind
                    // when it finishes. Posting here as well would announce the prayer twice.
                    playing -> Unit

                    else -> notifier.notifyPrayer(
                        prayer = prayer,
                        formattedTime = formattedTime,
                        placeName = place(placeName, preferences),
                        sound = sound,
                    )
                }

                scheduler.reschedule(preferences)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * Starts the adhan, reporting whether it actually began.
     *
     * A foreground start is refused outright when the alarm behind it was inexact, which is the
     * state every alarm is in once the user withholds the exact-alarm permission. The refusal
     * arrives as a `ForegroundServiceStartNotAllowedException`, and the caller falls back to a
     * notification whose channel carries the recording — played by the system rather than by us.
     */
    private fun startAdhan(
        context: Context,
        prayer: Prayer,
        formattedTime: String,
        placeName: String?,
    ): Boolean = runCatching {
        context.startForegroundService(
            AdhanPlayerService.playIntent(context, prayer, formattedTime, placeName),
        )
    }.onFailure {
        // Never rethrown: a crash inside an alarm receiver is a prayer missed and a crash report
        // the user cannot act on. The fallback path still alerts them.
        Log.w(TAG, "the adhan could not be started; falling back to a sounding notification", it)
    }.isSuccess

    /**
     * Stops an adhan this receiver started moments ago.
     *
     * Delivered as the service's own stop action rather than as `stopService`, so the service runs
     * its ordinary teardown. Starting it is allowed from here even though the app is in the
     * background: it already holds a foreground service, which is itself the licence to talk to it.
     * `stopService` remains as the fallback for the case where it is not.
     */
    private fun stopAdhan(context: Context) {
        val delivered = runCatching {
            context.startService(AdhanPlayerService.stopIntent(context))
        }.getOrNull() != null
        if (!delivered) {
            runCatching { context.stopService(AdhanPlayerService.stopIntent(context)) }
        }
    }

    /** The place the alarm was registered for, falling back to wherever the user is set to now. */
    private fun place(fromAlarm: String?, preferences: PrayerPreferences): String? =
        fromAlarm ?: preferences.place?.name

    private fun formatTime(at: Instant): String {
        val local = at.atZone(ZoneId.systemDefault())
        val hour12 = if (local.hour % 12 == 0) 12 else local.hour % 12
        val suffix = if (local.hour < 12) "ص" else "م"
        // Locale.ROOT, or Java localises the digits: on an ar device the notification would read
        // ٥:٠٧ while every date the app renders beside it stays in Latin figures.
        return "%d:%02d %s".format(Locale.ROOT, hour12, local.minute, suffix)
    }

    companion object {
        const val EXTRA_PRAYER = "prayer"
        const val EXTRA_AT_MILLIS = "at_millis"
        const val EXTRA_WAS_EXACT = "was_exact"
        const val EXTRA_ALERT_SOUND = "alert_sound"
        const val EXTRA_PLACE_NAME = "place_name"
        const val EXTRA_MINUTES_BEFORE = "minutes_before"

        private const val TAG = "AthkarAlarms"
    }
}
