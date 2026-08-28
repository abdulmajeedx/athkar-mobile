package com.athkar.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.athkar.app.notifications.PrayerAlarmScheduler
import com.athkar.app.notifications.PrayerNotifier
import com.athkar.data.seed.AdhkarSeeder
import com.athkar.domain.PrayerPreferencesRepository
import com.athkar.sync.worker.AthkarSyncWorker
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Application entry point (Hilt). Schedules the periodic, connectivity-aware sync worker AND the
 * pull-on-open refresh. Session auto-lock on background is enforced at the Activity level (see
 * MainActivity) because only it can hide the task snapshot.
 */
@HiltAndroidApp
class AthkarApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var adhkarSeeder: AdhkarSeeder

    @Inject lateinit var prayerPreferencesRepository: PrayerPreferencesRepository

    @Inject lateinit var prayerAlarmScheduler: PrayerAlarmScheduler

    @Inject lateinit var prayerNotifier: PrayerNotifier

    /** Application-lifetime scope for startup work that must not be tied to a screen. */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        seedBundledAdhkar()
        watchPrayerAlarms()
        schedulePeriodicSync()
    }

    /**
     * Keeps the prayer alarms in step with the settings for the whole life of the process.
     *
     * Rescheduling is driven by the preference flow rather than called from the settings screen:
     * the place, the calculation method and the enabled prayers all move the alarm times, and one
     * observer cannot forget a case the way six call sites can.
     */
    private fun watchPrayerAlarms() {
        prayerNotifier.ensureChannel()
        applicationScope.launch {
            prayerPreferencesRepository.observe()
                .distinctUntilChanged()
                .collect { preferences ->
                    runCatching { prayerAlarmScheduler.reschedule(preferences) }
                        .onFailure {
                            android.util.Log.e("AthkarAlarms", "rescheduling prayer alarms failed", it)
                        }
                }
        }
    }

    /**
     * Fills an empty database with the bundled adhkar. Off the main thread because it opens the
     * encrypted database, and failure-tolerant because an empty list is a far better outcome than a
     * crash on first launch.
     */
    private fun seedBundledAdhkar() {
        applicationScope.launch {
            runCatching { adhkarSeeder.seedIfNeeded() }
                .onFailure { android.util.Log.e("AthkarSeed", "seeding bundled adhkar failed", it) }
        }
    }

    /**
     * Logs any uncaught exception to [filesDir]/crash.log (and preserves the default handler so the
     * system still reports the crash). Lets us read the real failure cause off-device instead of
     * guessing at it — run the app once and then pull this file.
     */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                java.io.File(filesDir, "crash.log").appendText(
                    "${System.currentTimeMillis()} [${thread.name}]\n" +
                        android.util.Log.getStackTraceString(throwable) + "\n\n"
                )
            }
            prevHandler(previous, thread, throwable)
        }
    }

    private fun prevHandler(
        previous: Thread.UncaughtExceptionHandler?,
        thread: Thread,
        throwable: Throwable,
    ) {
        previous?.uncaughtException(thread, throwable)
            ?: Runtime.getRuntime().halt(1)
    }

    private fun schedulePeriodicSync() {
        val request = PeriodicWorkRequestBuilder<AthkarSyncWorker>(4, TimeUnit.HOURS)
            .setConstraints(
                androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            AthkarSyncWorker.NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}
