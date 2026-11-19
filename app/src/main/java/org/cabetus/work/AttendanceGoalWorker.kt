package org.cabetus.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.cabetus.domain.AttendanceGoalMonitor

/** アプリ画面が閉じていても保存済み記録の警告を確認（通信取得はしない）。 */
@HiltWorker
class AttendanceGoalWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val monitor: AttendanceGoalMonitor,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        monitor.checkNow()
        Result.success()
    } catch (_: Exception) { Result.retry() }
}
