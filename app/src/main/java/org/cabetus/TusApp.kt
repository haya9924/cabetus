package org.cabetus

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import org.cabetus.notification.NotificationChannels
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class TusApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    @Inject
    lateinit var attendanceGoalMonitor: org.cabetus.domain.AttendanceGoalMonitor

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.createAll(this)
        androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "attendance-goal-check",
            androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            androidx.work.PeriodicWorkRequestBuilder<org.cabetus.work.AttendanceGoalWorker>(
                15, java.util.concurrent.TimeUnit.MINUTES,
            ).build(),
        )
    }
}
