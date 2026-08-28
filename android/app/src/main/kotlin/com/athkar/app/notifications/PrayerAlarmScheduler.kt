package com.athkar.app.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.athkar.core.prayer.PolarDayException
import com.athkar.core.prayer.Prayer
import com.athkar.core.prayer.PrayerTimes
import com.athkar.domain.PrayerPreferences
import com.athkar.domain.PrayerPreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDate
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
                if (!at.isAfter(now)) continue
                schedule(manager, prayer, at, dayOffset)
            }
        }
    }

    fun cancelAll() {
        alarmManager?.let { cancelAll(it) }
    }

    private fun schedule(manager: AlarmManager, prayer: Prayer, at: Instant, dayOffset: Int) {
        val pendingIntent = pendingIntent(prayer, dayOffset, at) ?: return
        val triggerAt = at.toEpochMilli()
        // setExactAndAllowWhileIdle is the only variant that fires on time in Doze; without the
        // exact-alarm permission the inexact fallback still fires, just late.
        if (canScheduleExactAlarms()) {
            runCatching {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }.onFailure {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }

    private fun cancelAll(manager: AlarmManager) {
        for (dayOffset in 0..LAST_DAY_OFFSET) {
            for (prayer in Prayer.entries) {
                // NO_CREATE returns null when nothing is pending, so this cancels exactly what exists.
                val existing = PendingIntent.getBroadcast(
                    context,
                    requestCode(prayer, dayOffset),
                    alarmIntent(prayer, dayOffset, Instant.EPOCH),
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
                )
                if (existing != null) {
                    manager.cancel(existing)
                    existing.cancel()
                }
            }
        }
    }

    private fun pendingIntent(prayer: Prayer, dayOffset: Int, at: Instant): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            requestCode(prayer, dayOffset),
            alarmIntent(prayer, dayOffset, at),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun alarmIntent(prayer: Prayer, dayOffset: Int, at: Instant): Intent =
        Intent(context, PrayerAlarmReceiver::class.java).apply {
            action = "$ACTION_PRAYER_ALARM.${prayer.name}.$dayOffset"
            putExtra(PrayerAlarmReceiver.EXTRA_PRAYER, prayer.name)
            putExtra(PrayerAlarmReceiver.EXTRA_AT_MILLIS, at.toEpochMilli())
        }

    /** Unique per prayer *and* per day, so tomorrow's Fajr does not overwrite today's. */
    private fun requestCode(prayer: Prayer, dayOffset: Int): Int =
        REQUEST_CODE_BASE + dayOffset * Prayer.entries.size + prayer.ordinal

    companion object {
        const val ACTION_PRAYER_ALARM = "com.athkar.app.action.PRAYER_ALARM"

        /** Today and tomorrow: always at least one pending alarm, however long the app stays closed. */
        private const val LAST_DAY_OFFSET = 1
        private const val REQUEST_CODE_BASE = 7100
    }
}
