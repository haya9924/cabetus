package org.cabetus.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ClassAttendanceSyncScheduler @Inject constructor(@ApplicationContext context: Context) {
    private val manager = WorkManager.getInstance(context)
    val work = manager.getWorkInfosForUniqueWorkFlow(ClassAttendanceSyncContract.UNIQUE_WORK)
    fun start(year: Int) {
        check(ClassAttendanceSyncContract.ENABLED) { "Background CLASS protocol not verified" }
        require(year in 2000..2100)
        val request = OneTimeWorkRequestBuilder<ClassAttendanceSyncWorker>()
            .setInputData(workDataOf(ClassAttendanceSyncContract.YEAR to year))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        manager.enqueueUniqueWork(ClassAttendanceSyncContract.UNIQUE_WORK, ExistingWorkPolicy.KEEP, request)
    }
    fun cancel() { manager.cancelUniqueWork(ClassAttendanceSyncContract.UNIQUE_WORK) }
}
