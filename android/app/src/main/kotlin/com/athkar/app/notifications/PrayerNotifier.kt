package com.athkar.app.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import androidx.core.net.toUri
import com.athkar.app.MainActivity
import com.athkar.app.R
import com.athkar.core.prayer.Prayer
import com.athkar.domain.AlertSound
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts the prayer-time alert.
 *
 * A channel's sound is frozen the moment Android creates it — `createNotificationChannel` on an
 * existing id updates the name, the description and little else, and deleting a channel to change
 * its tone throws away everything the user had customised on it. So the sound is not a property of
 * one channel here; each choice has a channel of its own, and the choice picks between them at the
 * moment the alert is posted.
 *
 * Only the channels a user actually reaches are created, because every channel that exists is a row
 * they have to read in the system settings.
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
        ensureSilentChannel(manager)

        // The channel that carried the alarm tone before the sound became a setting. Left in place
        // it would keep appearing in the system settings as a second, sounding "أوقات الصلاة" that
        // no setting in the app controls.
        manager.deleteNotificationChannel(LEGACY_SOUNDING_CHANNEL_ID)
    }

    /** The channel that makes no sound of its own. */
    fun silentChannelId(): String {
        notificationManager?.let(::ensureSilentChannel)
        return CHANNEL_SILENT
    }

    /**
     * The channel to post [sound] on, created if this is the first time it is needed.
     *
     * [AlertSound.ADHAN] maps to a channel that carries the recording as its own tone. That is the
     * degraded path: normally [AdhanPlayerService] plays it, and the alert is posted silently over
     * the top. It is used when the service cannot be started at all — when the user has withheld
     * the exact-alarm permission, the system grants no foreground start, and a notification the
     * platform sounds by itself is the only thing left that still works.
     */
    fun channelFor(sound: AlertSound): String {
        val manager = notificationManager ?: return CHANNEL_SILENT
        return when (sound) {
            AlertSound.SILENT -> silentChannelId()

            // The tone is resolved once, when the channel is first created, and a channel's sound
            // cannot be rewritten afterwards. So a user who later changes their device's default
            // alarm tone keeps hearing the old one here — and changes it, if they want to, on this
            // channel in the system settings, which is the one place Android does allow it.
            AlertSound.DEVICE_ALARM -> CHANNEL_DEVICE_ALARM.also {
                ensureChannel(
                    manager = manager,
                    id = it,
                    name = "أوقات الصلاة — نغمة المنبّه",
                    description = "تنبيه بنغمة المنبّه المضبوطة في جهازك عند دخول وقت كل صلاة",
                    sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                )
            }

            AlertSound.ADHAN -> CHANNEL_ADHAN.also {
                ensureChannel(
                    manager = manager,
                    id = it,
                    name = "أوقات الصلاة — الأذان",
                    description = "الأذان عند دخول وقت كل صلاة",
                    sound = adhanUri(),
                )
            }
        }
    }

    /**
     * The notification id for [prayer].
     *
     * One id per prayer, so a later prayer replaces nothing — Fajr's alert should not vanish when
     * Dhuhr's arrives. [AdhanPlayerService] posts on the same id, which is what keeps the ongoing
     * "playing" alert and the alert that outlives it from ever appearing as two.
     */
    fun notificationId(prayer: Prayer): Int = NOTIFICATION_ID_BASE + prayer.ordinal

    /**
     * The id the settings audition posts under.
     *
     * Its own, and not any prayer's: auditioning a sound must not be able to replace the alert for
     * a prayer whose time has actually come.
     */
    fun previewNotificationId(): Int = PREVIEW_NOTIFICATION_ID

    /**
     * The warning that a prayer is near — [minutesBefore] minutes away.
     *
     * Deliberately not an alarm: its own channel at default importance, so it appears without
     * taking over the screen or overriding silent mode the way a prayer time does. A reminder that
     * shouts is a reminder people turn off.
     */
    fun notifyPreAdhan(prayer: Prayer, minutesBefore: Int, formattedTime: String) {
        val manager = notificationManager ?: return
        ensureWarningChannel(manager)

        val contentIntent = PendingIntent.getActivity(
            context,
            PRE_ADHAN_REQUEST_CODE + prayer.ordinal,
            Intent(Intent.ACTION_VIEW, PRAYER_TAB_URI, context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = Notification.Builder(context, CHANNEL_WARNING)
            .setSmallIcon(R.drawable.ic_notification_prayer)
            .setContentTitle("اقترب وقت صلاة ${prayer.arabicName}")
            .setContentText("بقي ${minutesLabel(minutesBefore)} — الأذان $formattedTime")
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        manager.notify(PRE_ADHAN_NOTIFICATION_ID_BASE + prayer.ordinal, notification)
    }

    /** Arabic counts its minutes in three forms, and "بقي 2 دقيقة" is not one of them. */
    private fun minutesLabel(minutes: Int): String = when {
        minutes == 1 -> "دقيقة"
        minutes == 2 -> "دقيقتان"
        minutes in 3..10 -> "$minutes دقائق"
        else -> "$minutes دقيقة"
    }

    private fun ensureWarningChannel(manager: NotificationManager) {
        ensureChannel(
            manager = manager,
            id = CHANNEL_WARNING,
            name = "اقتراب وقت الصلاة",
            description = "تنبيه قبل دخول الوقت بالمدة التي تختارها",
            sound = null,
            importance = NotificationManager.IMPORTANCE_DEFAULT,
        )
    }

    /** Shows the alert for [prayer] on the channel belonging to [sound]. */
    fun notifyPrayer(
        prayer: Prayer,
        formattedTime: String,
        placeName: String?,
        sound: AlertSound,
    ) {
        val manager = notificationManager ?: return
        manager.notify(
            notificationId(prayer),
            buildAlert(
                prayer = prayer,
                formattedTime = formattedTime,
                placeName = placeName,
                channelId = channelFor(sound),
                playing = false,
            ),
        )
    }

    /**
     * Builds the alert.
     *
     * When [playing] the adhan is sounding and the notification carries the button that stops it,
     * and stays put rather than being swiped away by accident. When it is not, this is the ordinary
     * notice that the time has come.
     *
     * A [preview] says so plainly. The same notification announcing a prayer time would be a lie
     * the app told because the settings screen happened to reuse the player.
     */
    fun buildAlert(
        prayer: Prayer,
        formattedTime: String,
        placeName: String?,
        channelId: String,
        playing: Boolean,
        preview: Boolean = false,
    ): Notification {
        // Addressed at the prayer tab, not just at the app. Tapping "حان الآن وقت صلاة الفجر" used
        // to open the 133-chapter adhkar index, leaving the user to go and find the time they had
        // just been told about.
        val contentIntent = PendingIntent.getActivity(
            context,
            prayer.ordinal,
            Intent(Intent.ACTION_VIEW, PRAYER_TAB_URI, context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val subtitle = if (preview) {
            "معاينة من الإعدادات"
        } else {
            listOfNotNull(formattedTime.takeIf { it.isNotBlank() }, placeName).joinToString(" — ")
        }

        val builder = Notification.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification_prayer)
            .setContentTitle(
                if (preview) "تجربة صوت التنبيه" else "حان الآن وقت صلاة ${prayer.arabicName}",
            )
            .setContentText(subtitle)
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(contentIntent)
            .setAutoCancel(!playing)

        if (playing) {
            val stop = PendingIntent.getForegroundService(
                context,
                STOP_REQUEST_CODE,
                AdhanPlayerService.stopIntent(context),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder
                .setOngoing(true)
                .setUsesChronometer(false)
                .addAction(
                    Notification.Action.Builder(null, "إيقاف الأذان", stop).build(),
                )
                // Swiping it away means the same thing as pressing stop; on Android 14 and later
                // an ongoing notification can be swiped, and it would be a strange app that kept
                // playing after the user dismissed the only thing on screen mentioning it.
                .setDeleteIntent(stop)
        }

        return builder.build()
    }

    private fun ensureSilentChannel(manager: NotificationManager) {
        ensureChannel(
            manager = manager,
            id = CHANNEL_SILENT,
            name = "أوقات الصلاة",
            description = "تنبيه عند دخول وقت كل صلاة",
            sound = null,
        )
    }

    /**
     * Creates the channel if it is missing.
     *
     * Existing channels are left completely alone rather than being rewritten with the current
     * name: `createNotificationChannel` on a live id silently discards everything except the name,
     * description and group, and calling it is how an app resurrects a channel the user deleted.
     */
    private fun ensureChannel(
        manager: NotificationManager,
        id: String,
        name: String,
        description: String,
        sound: Uri?,
        importance: Int = NotificationManager.IMPORTANCE_HIGH,
    ) {
        if (manager.getNotificationChannel(id) != null) return

        val channel = NotificationChannel(id, name, importance).apply {
            this.description = description
            enableVibration(true)
            if (sound == null) {
                setSound(null, null)
            } else {
                setSound(
                    sound,
                    AudioAttributes.Builder()
                        // The alarm usage is what carries a prayer time past silent and vibrate
                        // mode. An alert the ringer swallows has failed at the one thing it exists
                        // for.
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
            }
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * The bundled recording, as a URI the system's own notification player can read.
     *
     * Built from the generated id rather than the resource name so that the reference to
     * `R.raw.adhan` is a real one in code: resource shrinking strips a raw resource that only ever
     * appears inside a string.
     */
    private fun adhanUri(): Uri =
        "${ContentResolver.SCHEME_ANDROID_RESOURCE}://${context.packageName}/${R.raw.adhan}".toUri()

    private companion object {
        const val CHANNEL_SILENT = "prayer_times_silent"
        const val CHANNEL_DEVICE_ALARM = "prayer_times_device_alarm_v1"
        const val CHANNEL_ADHAN = "prayer_times_adhan_v1"
        const val CHANNEL_WARNING = "prayer_times_warning_v1"
        const val LEGACY_SOUNDING_CHANNEL_ID = "prayer_times"
        val PRAYER_TAB_URI: Uri = "athkar://prayer".toUri()
        const val NOTIFICATION_ID_BASE = 4100
        const val PREVIEW_NOTIFICATION_ID = 4150

        /** Its own id space, so a warning never replaces the alert for the prayer it warned about. */
        const val PRE_ADHAN_NOTIFICATION_ID_BASE = 4160
        const val PRE_ADHAN_REQUEST_CODE = 4300
        const val STOP_REQUEST_CODE = 4200
    }
}
