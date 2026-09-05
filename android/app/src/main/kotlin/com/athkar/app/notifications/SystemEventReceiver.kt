package com.athkar.app.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Re-registers the alarms after the events that silently destroy them.
 *
 * Android drops all pending alarms on reboot and on app replacement, a time-zone or manual clock
 * change moves every prayer without telling anyone, and revoking the exact-alarm permission deletes
 * every exact alarm outright. None of these reopen the app, so without this receiver the
 * notifications simply stop and the user never learns why.
 *
 * It only ever re-registers alarms. It must never start the adhan service: an app targeting
 * Android 15 may not launch a media-playback foreground service from a boot broadcast at all.
 */
@AndroidEntryPoint
class SystemEventReceiver : BroadcastReceiver() {

    @Inject lateinit var scheduler: PrayerAlarmScheduler

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            // Granting the permission is as important as losing it: alarms registered inexactly
            // while it was withheld can now be re-registered exactly, which is also what restores
            // the adhan.
            ACTION_EXACT_ALARM_PERMISSION_CHANGED,
            -> Unit

            else -> return
        }

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                scheduler.reschedule()
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        /**
         * `AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`, spelled out because
         * the constant is API 31 and the receiver is compiled against a lower minimum. On older
         * versions no such broadcast is ever sent and the branch is simply unreachable.
         */
        const val ACTION_EXACT_ALARM_PERMISSION_CHANGED =
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
    }
}
