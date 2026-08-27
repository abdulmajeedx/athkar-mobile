package com.athkar.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.athkar.sync.worker.AthkarSyncWorker
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Application entry point (Hilt). Schedules the periodic, connectivity-aware sync worker AND the
 * pull-on-open refresh. Session auto-lock on background is enforced at the Activity level (see
 * MainActivity) because only it can hide the task snapshot.
 */
@HiltAndroidApp
class AthkarApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        schedulePeriodicSync()
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
