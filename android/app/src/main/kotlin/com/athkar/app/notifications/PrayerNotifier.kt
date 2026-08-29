package com.athkar.app.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.athkar.app.MainActivity
import com.athkar.app.R
import com.athkar.core.prayer.Prayer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts the prayer-time alert.
 *
 * The alert is silent: it vibrates and appears, and makes no sound. Importance stays high so it
 * still surfaces over whatever is on screen — a prayer time the user has to go looking for is not
 * an alert.
 *
 * A channel's sound is fixed once Android has created it, so removing the tone meant a new channel
 * id and deleting the old one. Anything the user had customised on the old channel goes with it;
 * there is no API that would have let it carry over.
 */
@Singleton
class PrayerNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private val notificationManager: NotificationManager?
        get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    /** Idempotent; safe to call on every app start and before every post. */
    fun ensureChannel() {
        val manager = notificationManager ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "أوقات الصلاة",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "تنبيه صامت عند دخول وقت كل صلاة"
            enableVibration(true)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)

        // The channel that carried the alarm tone. Left in place it would keep appearing in the
        // system settings as a second, sounding "أوقات الصلاة" the user never asked for.
        manager.deleteNotificationChannel(LEGACY_SOUNDING_CHANNEL_ID)
    }

    /**
     * Shows the alert for [prayer]. Each prayer keeps its own notification id so a later prayer
     * replaces nothing — Fajr's alert should not vanish when Dhuhr's arrives.
     */
    fun notifyPrayer(prayer: Prayer, formattedTime: String, placeName: String?) {
        ensureChannel()
        val manager = notificationManager ?: return

        val contentIntent = PendingIntent.getActivity(
            context,
            prayer.ordinal,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val subtitle = listOfNotNull(formattedTime, placeName).joinToString(" — ")
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_prayer)
            .setContentTitle("حان الآن وقت صلاة ${prayer.arabicName}")
            .setContentText(subtitle)
            .setCategory(Notification.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        manager.notify(NOTIFICATION_ID_BASE + prayer.ordinal, notification)
    }

    private companion object {
        const val CHANNEL_ID = "prayer_times_silent"
        const val LEGACY_SOUNDING_CHANNEL_ID = "prayer_times"
        const val NOTIFICATION_ID_BASE = 4100
    }
}
