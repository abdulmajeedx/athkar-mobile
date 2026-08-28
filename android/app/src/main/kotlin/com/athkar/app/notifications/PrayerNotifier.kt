package com.athkar.app.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import com.athkar.app.MainActivity
import com.athkar.app.R
import com.athkar.core.prayer.Prayer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts the prayer-time alert.
 *
 * The channel uses alarm audio attributes and the device's alarm tone: a prayer alert that the
 * ringer's silent mode swallows has failed at the one thing it exists for. Everything about the
 * channel — tone, vibration, importance — remains the user's to change in system settings, which is
 * the only place Android allows it to change after the channel is created.
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
            description = "تنبيه عند دخول وقت كل صلاة"
            enableVibration(true)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
        }
        manager.createNotificationChannel(channel)
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
        const val CHANNEL_ID = "prayer_times"
        const val NOTIFICATION_ID_BASE = 4100
    }
}
