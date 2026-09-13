package com.athkar.app.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.athkar.core.prayer.PolarDayException
import com.athkar.core.prayer.Prayer
import com.athkar.core.prayer.PrayerTimes
import com.athkar.domain.AlertSound
import com.athkar.domain.PrayerPreferences
import com.athkar.domain.PrayerPreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Keeps the platform alarms in step with the user's settings.
 *
 * Alarms for *every* remaining prayer in a two-day window are registered at once, rather than
 * chaining one alarm to the next. Chaining is a single point of failure: one alarm deferred by Doze
 * or dropped by a force-stop silently ends every future notification, and the user discovers this by
 * missing a prayer. A dozen pending alarms cost nothing and degrade one at a time.
 */
@Singleton
class PrayerAlarmScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val preferencesRepository: PrayerPreferencesRepository,
) {

    private val alarmManager: AlarmManager?
        get() = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    /** True when the OS will honour exact timing. Inexact alarms can drift by many minutes. */
    fun canScheduleExactAlarms(): Boolean {
        val manager = alarmManager ?: return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
    }

    suspend fun reschedule() = reschedule(preferencesRepository.observe().first())

    fun reschedule(preferences: PrayerPreferences) {
        val manager = alarmManager ?: return
        cancelAll(manager)

        if (!preferences.notificationsEnabled) return
        val place = preferences.place ?: return
        if (preferences.notifiedPrayers.isEmpty()) return

        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        val today = LocalDate.now(zone)

        for (dayOffset in 0..LAST_DAY_OFFSET) {
            val date = today.plusDays(dayOffset.toLong())
            val times = try {
                PrayerTimes.calculate(place.coordinates, date, preferences.calculationParameters())
            } catch (e: PolarDayException) {
                // No sunrise means no derived times to alert on; the screen already explains why.
                continue
            }
            for (prayer in preferences.notifiedPrayers) {
                val at = times.timeFor(prayer)

                // The warning is its own alarm rather than a delayed branch of the prayer's: it
                // fires before it, so it cannot be derived from an alarm that has not gone off yet.
                val warnMinutes = preferences.preAdhanMinutes
                if (warnMinutes > 0) {
                    val warnAt = at.minus(warnMinutes.toLong(), ChronoUnit.MINUTES)
                    if (warnAt.isAfter(now)) {
                        schedule(
                            manager = manager,
                            prayer = prayer,
                            at = warnAt,
                            dayOffset = dayOffset,
                            // Never the adhan: the call belongs to the time itself, and raising it
                            // early would announce a prayer whose time has not come.
                            sound = AlertSound.SILENT,
                            placeName = place.name,
                            minutesBefore = warnMinutes,
                        )
                    }
                }

                if (!at.isAfter(now)) continue
                schedule(
                    manager = manager,
                    prayer = prayer,
                    at = at,
                    dayOffset = dayOffset,
                    // Written into the alarm so the receiver can act on it without waiting on a
                    // disk read it has no time for. Every settings change reschedules, so what an
                    // alarm carries is never older than the last change the user made.
                    sound = preferences.alertSound.forPrayer(prayer),
                    placeName = place.name,
                )
            }
        }
    }

    fun cancelAll() {
        alarmManager?.let { cancelAll(it) }
    }

    /**
     * Registers one alarm, recording in it whether it was registered exactly.
     *
     * That flag is not a diagnostic — the receiver cannot play the adhan without it. Android grants
     * a brief foreground-service allowance to the delivery of an *exact* alarm and none at all to
     * an inexact one, and the permission can be revoked between scheduling and firing, so asking
     * the alarm manager at delivery time can give a different answer than the one the system
     * actually attached to this broadcast. The alarm has to carry its own provenance.
     */
    private fun schedule(
        manager: AlarmManager,
        prayer: Prayer,
        at: Instant,
        dayOffset: Int,
        sound: AlertSound,
        placeName: String,
        minutesBefore: Int = 0,
    ) {
        val triggerAt = at.toEpochMilli()
        // setExactAndAllowWhileIdle is the only variant that fires on time in Doze; without the
        // exact-alarm permission the inexact fallback still fires, just late.
        if (canScheduleExactAlarms()) {
            val exactIntent = pendingIntent(prayer, dayOffset, at, true, sound, placeName, minutesBefore)
            val scheduled = exactIntent != null && runCatching {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, exactIntent)
            }.isSuccess
            if (scheduled) return
        }
        // Re-created rather than reused: the flag the receiver reads must describe the call that
        // actually registered the alarm, not the one that was attempted first.
        val inexactIntent = pendingIntent(prayer, dayOffset, at, false, sound, placeName, minutesBefore) ?: return
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, inexactIntent)
    }

    private fun cancelAll(manager: AlarmManager) {
        for (dayOffset in 0..LAST_DAY_OFFSET) {
            for (prayer in Prayer.entries) {
                // Both kinds, because a warning the user has just switched off is still pending and
                // would otherwise fire from a schedule that no longer exists.
                for (minutesBefore in listOf(0, 1)) {
                    // NO_CREATE returns null when nothing is pending, so this cancels exactly what
                    // exists. Only the component, action and request code decide what it matches;
                    // the extras are ignored, so placeholders here cancel the real alarm.
                    val existing = PendingIntent.getBroadcast(
                        context,
                        requestCode(prayer, dayOffset, minutesBefore > 0),
                        alarmIntent(
                            prayer, dayOffset, Instant.EPOCH, false, AlertSound.SILENT, null,
                            minutesBefore,
                        ),
                        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
                    )
                    if (existing != null) {
                        manager.cancel(existing)
                        existing.cancel()
                    }
                }
            }
        }
    }

    private fun pendingIntent(
        prayer: Prayer,
        dayOffset: Int,
        at: Instant,
        wasExact: Boolean,
        sound: AlertSound,
        placeName: String?,
        minutesBefore: Int,
    ): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            requestCode(prayer, dayOffset, minutesBefore > 0),
            alarmIntent(prayer, dayOffset, at, wasExact, sound, placeName, minutesBefore),
            // Extras are what change between the two calls, and only UPDATE_CURRENT rewrites them:
            // intents that differ solely by extras are the same intent as far as matching goes.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun alarmIntent(
        prayer: Prayer,
        dayOffset: Int,
        at: Instant,
        wasExact: Boolean,
        sound: AlertSound,
        placeName: String?,
        minutesBefore: Int,
    ): Intent =
        Intent(context, PrayerAlarmReceiver::class.java).apply {
            // The warning and the prayer are distinct alarms for the same prayer on the same day,
            // so the action has to separate them too — PendingIntent matching ignores extras. It
            // records *which kind*, never how many minutes: the minute count is a setting that
            // changes, and cancelAll has to be able to name an alarm it did not schedule.
            action = "$ACTION_PRAYER_ALARM.${prayer.name}.$dayOffset." +
                if (minutesBefore > 0) "warning" else "adhan"
            putExtra(PrayerAlarmReceiver.EXTRA_PRAYER, prayer.name)
            putExtra(PrayerAlarmReceiver.EXTRA_AT_MILLIS, at.toEpochMilli())
            putExtra(PrayerAlarmReceiver.EXTRA_WAS_EXACT, wasExact)
            putExtra(PrayerAlarmReceiver.EXTRA_ALERT_SOUND, sound.name)
            putExtra(PrayerAlarmReceiver.EXTRA_PLACE_NAME, placeName)
            putExtra(PrayerAlarmReceiver.EXTRA_MINUTES_BEFORE, minutesBefore)
        }

    /** Unique per prayer *and* per day, so tomorrow's Fajr does not overwrite today's. */
    private fun requestCode(prayer: Prayer, dayOffset: Int, isWarning: Boolean): Int {
        val base = if (isWarning) WARNING_REQUEST_CODE_BASE else REQUEST_CODE_BASE
        return base + dayOffset * Prayer.entries.size + prayer.ordinal
    }

    companion object {
        const val ACTION_PRAYER_ALARM = "com.athkar.app.action.PRAYER_ALARM"

        /** Today and tomorrow: always at least one pending alarm, however long the app stays closed. */
        private const val LAST_DAY_OFFSET = 1
        private const val REQUEST_CODE_BASE = 7100

        /** Far enough from [REQUEST_CODE_BASE] that the two blocks cannot overlap as days grow. */
        private const val WARNING_REQUEST_CODE_BASE = 7200
    }
}
