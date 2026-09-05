package com.athkar.app.notifications

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import com.athkar.app.R
import com.athkar.core.prayer.Prayer
import com.athkar.domain.AlertSound
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Plays the call to prayer.
 *
 * The adhan runs for two and a half minutes. A broadcast receiver lives for tens of seconds and its
 * process becomes killable the moment it returns, so playing from the receiver produces the classic
 * bug where the adhan cuts off after twenty seconds on whichever device is under memory pressure —
 * silently, and differently every time. A foreground service is the only construct that keeps the
 * process alive for the length of the recording.
 *
 * Two platform clocks bound the start and neither is generous. [PrayerAlarmReceiver] may call
 * `startForegroundService` only inside the ten-second allowlist the alarm manager attaches to an
 * *exact* alarm, and this service must reach [ServiceCompat.startForeground] before the system's
 * start-foreground timeout or the process is killed outright — an uncatchable crash, not an
 * exception. So [onStartCommand] posts the notification as its first act, before it reads a
 * preference, opens the asset, or touches the player.
 */
@AndroidEntryPoint
class AdhanPlayerService : Service() {

    @Inject lateinit var notifier: PrayerNotifier

    /**
     * The settings screen's audition button reads its label from here.
     *
     * The service is the only thing that knows whether sound is actually coming out: an audition
     * ends on its own when the recording finishes, and never starts at all during a call or under
     * total silence. A button tracking only its own taps would be wrong in all three cases.
     */
    @Inject lateinit var preview: AlertSoundPreviewImpl

    private val audioManager: AudioManager?
        get() = getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val notificationManager: NotificationManager?
        get() = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()

    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null
    private var mediaSession: MediaSession? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Invalidates the callbacks of a player that has been replaced.
     *
     * A completion listener or watchdog belonging to a torn-down player would otherwise fire later
     * and stop the one that replaced it. Every callback compares the generation it captured against
     * this before acting.
     */
    private var generation = 0

    /** The alert this service is currently sounding, so it can be left behind when playback ends. */
    private var current: Alert? = null

    /** Set once teardown has run, so a second pass cannot undo the first. */
    private var stopped = false

    private data class Alert(
        val prayer: Prayer,
        val sound: AlertSound,
        val formattedTime: String,
        val placeName: String?,
        val isPreview: Boolean,
    )

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }

        val alert = intent.toAlert()
        // Foreground first, before anything that can fail or block. The system kills the process if
        // this does not land in time, and that kill cannot be caught.
        val posted = runCatching { goForeground(alert) }.onFailure {
            Log.w(TAG, "could not enter the foreground for the adhan", it)
        }.isSuccess
        if (!posted) {
            stopSelf()
            return START_NOT_STICKY
        }

        synchronized(lock) {
            // A start can arrive after a previous one has torn everything down but before the
            // system has actually destroyed the instance. Re-arming here is what keeps the new
            // adhan stoppable; without it the teardown guard would swallow every later stop.
            stopped = false
            // Last request wins: a preview tapped during a real adhan, or a prayer arriving during
            // a preview, should replace what is playing rather than overlap it.
            generation++
            val generationNow = generation
            releasePlayerLocked()
            current = alert
            startPlaybackLocked(alert, generationNow)
        }
        return START_NOT_STICKY
    }

    /**
     * Posts the ongoing notification and enters the foreground.
     *
     * The notification is the alert itself, carrying the same id [PrayerNotifier] would use, so the
     * user never sees the prayer announced twice. It adds the one control that matters while a
     * two-and-a-half-minute recording is playing: a way to stop it.
     */
    private fun goForeground(alert: Alert) {
        val notification = notifier.buildAlert(
            prayer = alert.prayer,
            formattedTime = alert.formattedTime,
            placeName = alert.placeName,
            channelId = notifier.silentChannelId(),
            playing = true,
            preview = alert.isPreview,
        )
        ServiceCompat.startForeground(
            this,
            notificationIdFor(alert),
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                0
            },
        )
    }

    private fun notificationIdFor(alert: Alert): Int =
        if (alert.isPreview) notifier.previewNotificationId() else notifier.notificationId(alert.prayer)

    private fun startPlaybackLocked(alert: Alert, generationNow: Int) {
        val manager = audioManager
        if (manager == null) {
            finishPlayback(generationNow)
            return
        }

        // Total silence mutes USAGE_ALARM audio at the app-ops layer: the player would run its full
        // length and report success having produced nothing. Better to know now and leave the
        // notification, which the user can still see, than to pretend the adhan was heard.
        if (isSilencedByDoNotDisturb()) {
            Log.i(TAG, "do not disturb is set to total silence; showing the alert without the adhan")
            finishPlayback(generationNow)
            return
        }

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        // A rejected focus request is the permission-free way to learn the user is on a call: the
        // audio server refuses focus while one is in progress or ringing. Answering a call with two
        // and a half minutes of adhan on the alarm stream is not a thing to do to anyone.
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            // Without this a request made during a call is granted when the call ends, and the
            // adhan arrives forty minutes after the prayer it was announcing.
            .setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener({ change ->
                // Loss of any kind ends it. There is no resuming a sound that marks a moment: by
                // the time focus came back the moment would have passed.
                if (change == AudioManager.AUDIOFOCUS_LOSS ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
                ) {
                    finishPlayback(generationNow)
                }
            }, handler)
            .build()
        focusRequest = request

        if (manager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.i(TAG, "audio focus refused — a call is most likely in progress")
            finishPlayback(generationNow)
            return
        }

        val source = sourceFor(alert)
        val started = runCatching { openPlayer(source, attributes, generationNow) }
            .onFailure { Log.w(TAG, "the adhan could not be opened", it) }
            .getOrNull()
        if (started == null) {
            finishPlayback(generationNow)
            return
        }
        player = started
        acquireWakeLock()
        startMediaSession()
        if (alert.isPreview) preview.onPreviewPlaybackChanged(true)
    }

    private fun openPlayer(
        source: Source,
        attributes: AudioAttributes,
        generationNow: Int,
    ): MediaPlayer = MediaPlayer().apply {
        setAudioAttributes(attributes)
        // Belt and braces with the service's own lock: on most devices the audio path holds the SoC
        // awake by itself, but that has never been an API contract.
        setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
        setLooping(false)
        when (source) {
            is Source.Raw -> resources.openRawResourceFd(source.resourceId).use { descriptor ->
                setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
            }
            is Source.DeviceAlarm -> setDataSource(applicationContext, source.uri)
        }
        setOnPreparedListener { prepared ->
            if (generationNow != generation) {
                prepared.release()
                return@setOnPreparedListener
            }
            armWatchdog(prepared.duration, generationNow)
            prepared.start()
        }
        setOnCompletionListener { if (generationNow == generation) finishPlayback(generationNow) }
        setOnErrorListener { _, what, extra ->
            Log.w(TAG, "the adhan failed to play (what=$what extra=$extra)")
            if (generationNow == generation) finishPlayback(generationNow)
            // Handled, so the completion listener does not also fire for the same failure.
            true
        }
        // Asynchronous: a corrupt asset must not block the main thread inside onStartCommand.
        prepareAsync()
    }

    private sealed interface Source {
        data class Raw(val resourceId: Int) : Source
        data class DeviceAlarm(val uri: android.net.Uri) : Source
    }

    /**
     * The recording to play.
     *
     * The adhan is referenced as a literal `R.raw.adhan` rather than looked up by name: resource
     * shrinking keeps a raw resource only when the generated id is used in code, and a name lookup
     * would both be stripped from the release build and fail lint.
     *
     * Fajr is called differently — it carries *الصلاة خير من النوم*, which no other prayer does.
     * The app ships one recording today, so Fajr gets that one; when a Fajr recording is added this
     * is the single place that changes.
     *
     * The device tone reaches here only from the settings audition. At a real prayer time it is the
     * notification channel that carries it, and this service is never started.
     */
    private fun sourceFor(alert: Alert): Source = when (alert.sound) {
        AlertSound.DEVICE_ALARM ->
            Source.DeviceAlarm(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
        else -> Source.Raw(R.raw.adhan)
    }

    /**
     * Stops playback after the recording's own length plus a margin.
     *
     * The completion listener is the normal end; this is what makes "plays forever" unreachable if
     * the player never reports one. The wake lock carries the same deadline, so even a total
     * failure of this handler cannot cost the user a battery.
     */
    private fun armWatchdog(durationMillis: Int, generationNow: Int) {
        val cap = durationMillis
            .takeIf { it > 0 }
            ?.plus(WATCHDOG_MARGIN_MILLIS)
            ?.coerceAtMost(MAX_PLAYBACK_MILLIS)
            ?: DEFAULT_PLAYBACK_CAP_MILLIS
        handler.postDelayed({
            if (generationNow == generation) {
                Log.w(TAG, "the adhan outlived its own length; stopping it")
                finishPlayback(generationNow)
            }
        }, cap.toLong())
    }

    private fun acquireWakeLock() {
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            // Always with a deadline. A lock without one is a battery bug waiting for an edge case.
            acquire(MAX_PLAYBACK_MILLIS.toLong())
        }
    }

    /**
     * Routes the volume keys to this service so a press stops the adhan.
     *
     * Reaching for the volume key is what everyone does to silence a sound they did not expect, and
     * without a session holding remote volume the press only moves the alarm slider while the adhan
     * keeps going. The alternative — a full-screen alarm activity — needs a permission Android 14
     * grants only to apps whose stated purpose is alarms.
     */
    private fun startMediaSession() {
        mediaSession = runCatching {
            MediaSession(this, MEDIA_SESSION_TAG).apply {
                setPlaybackState(
                    PlaybackState.Builder()
                        .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
                        .setActions(PlaybackState.ACTION_STOP)
                        .build(),
                )
                setPlaybackToRemote(object : android.media.VolumeProvider(
                    VOLUME_CONTROL_ABSOLUTE,
                    1,
                    0,
                ) {
                    override fun onAdjustVolume(direction: Int) = stopEverything()
                    override fun onSetVolumeTo(volume: Int) = stopEverything()
                })
                isActive = true
            }
        }.onFailure { Log.w(TAG, "the volume keys will not stop the adhan", it) }.getOrNull()
    }

    /**
     * True when Do Not Disturb would mute [AudioAttributes.USAGE_ALARM] audio outright.
     *
     * Total silence is the only case asked about. Under the priority filter whether alarms are
     * allowed is readable only with notification-policy access — a special access this app has no
     * business holding, and which would not help even if it did: Android exempts a fixed list of
     * system packages from the mute and there is no way onto it. So under priority DND the adhan is
     * simply attempted. If the system mutes it the user still gets the notification, which is the
     * same outcome as guessing wrong would have produced.
     */
    private fun isSilencedByDoNotDisturb(): Boolean =
        notificationManager?.currentInterruptionFilter ==
            NotificationManager.INTERRUPTION_FILTER_NONE

    /**
     * Ends playback but leaves the alert on screen.
     *
     * The adhan finishing does not mean the prayer time has passed, so the notification stays —
     * detached from the service — with its stop button removed.
     */
    private fun finishPlayback(generationNow: Int) {
        synchronized(lock) {
            if (generationNow != generation) return
            stopEverything()
        }
    }

    /**
     * Tears everything down and leaves the alert on screen.
     *
     * Silencing the adhan is not the same as dismissing the prayer — pressing إيقاف means "I have
     * heard it", not "this time never came" — so every exit but a settings audition detaches the
     * notification and reposts it without the button. Only the audition, which announces nothing,
     * takes its notification with it.
     *
     * Idempotent, and it has to be: the normal path runs this once when the recording ends and
     * again from [onDestroy] moments later. Without the guard the second run would remove the
     * notification the first had just left behind, and the alert would vanish the instant the adhan
     * finished.
     */
    private fun stopEverything() = synchronized(lock) {
        if (stopped) return@synchronized
        stopped = true

        generation++
        handler.removeCallbacksAndMessages(null)
        releasePlayerLocked()

        focusRequest?.let { request -> runCatching { audioManager?.abandonAudioFocusRequest(request) } }
        focusRequest = null

        mediaSession?.let { session ->
            runCatching {
                session.isActive = false
                session.release()
            }
        }
        mediaSession = null

        // Released before leaving the foreground: a Doze transition between the two would otherwise
        // suspend the CPU with the lock still held.
        wakeLock?.takeIf { it.isHeld }?.let { runCatching { it.release() } }
        wakeLock = null

        val alert = current
        current = null
        preview.onPreviewPlaybackChanged(false)
        if (alert != null && !alert.isPreview) {
            leaveAlertBehind(alert)
        } else {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }

    /** Detaches the ongoing notification and reposts it as the ordinary alert, without the button. */
    private fun leaveAlertBehind(alert: Alert) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        runCatching {
            notificationManager?.notify(
                notificationIdFor(alert),
                notifier.buildAlert(
                    prayer = alert.prayer,
                    formattedTime = alert.formattedTime,
                    placeName = alert.placeName,
                    channelId = notifier.silentChannelId(),
                    playing = false,
                ),
            )
        }
    }

    private fun releasePlayerLocked() {
        player?.let { existing ->
            runCatching {
                existing.setOnCompletionListener(null)
                existing.setOnErrorListener(null)
                existing.setOnPreparedListener(null)
                if (existing.isPlaying) existing.stop()
                existing.reset()
                existing.release()
            }
        }
        player = null
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }

    private fun Intent?.toAlert(): Alert {
        val prayer = this?.getStringExtra(EXTRA_PRAYER)
            ?.let { name -> Prayer.entries.firstOrNull { it.name == name } }
            ?: Prayer.FAJR
        return Alert(
            prayer = prayer,
            sound = AlertSound.fromName(this?.getStringExtra(EXTRA_SOUND)),
            formattedTime = this?.getStringExtra(EXTRA_FORMATTED_TIME).orEmpty(),
            placeName = this?.getStringExtra(EXTRA_PLACE_NAME),
            isPreview = this?.getBooleanExtra(EXTRA_PREVIEW, false) == true,
        )
    }

    companion object {
        const val ACTION_PLAY = "com.athkar.app.action.PLAY_ADHAN"
        const val ACTION_STOP = "com.athkar.app.action.STOP_ADHAN"
        const val EXTRA_PRAYER = "prayer"
        const val EXTRA_SOUND = "sound"
        const val EXTRA_FORMATTED_TIME = "formatted_time"
        const val EXTRA_PLACE_NAME = "place_name"
        const val EXTRA_PREVIEW = "preview"

        private const val TAG = "AthkarAdhan"
        private const val WAKE_LOCK_TAG = "athkar:adhan"
        private const val MEDIA_SESSION_TAG = "athkar-adhan"

        /** Slack after the recording ends, for a device that reports its length a little short. */
        private const val WATCHDOG_MARGIN_MILLIS = 15_000

        /** Used only when the player cannot report a duration at all. */
        private const val DEFAULT_PLAYBACK_CAP_MILLIS = 300_000

        /** No adhan is five minutes long. Past this, something has gone wrong and it stops. */
        private const val MAX_PLAYBACK_MILLIS = 300_000

        /** The intent that starts the adhan for [prayer]. */
        fun playIntent(
            context: Context,
            prayer: Prayer,
            formattedTime: String,
            placeName: String?,
        ): Intent = Intent(context, AdhanPlayerService::class.java).apply {
            action = ACTION_PLAY
            putExtra(EXTRA_PRAYER, prayer.name)
            putExtra(EXTRA_SOUND, AlertSound.ADHAN.name)
            putExtra(EXTRA_FORMATTED_TIME, formattedTime)
            putExtra(EXTRA_PLACE_NAME, placeName)
        }

        /** The intent that auditions [sound] from the settings screen. */
        fun previewIntent(context: Context, sound: AlertSound): Intent =
            Intent(context, AdhanPlayerService::class.java).apply {
                action = ACTION_PLAY
                putExtra(EXTRA_PRAYER, Prayer.FAJR.name)
                putExtra(EXTRA_SOUND, sound.name)
                putExtra(EXTRA_PREVIEW, true)
            }

        fun stopIntent(context: Context): Intent =
            Intent(context, AdhanPlayerService::class.java).setAction(ACTION_STOP)
    }
}
