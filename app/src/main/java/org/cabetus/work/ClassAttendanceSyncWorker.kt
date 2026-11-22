package org.cabetus.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.cabetus.data.classweb.ClassAttendanceHttpsTransport
import org.cabetus.data.classweb.ClassAttendanceNetworkPage
import org.cabetus.data.classweb.ClassAttendanceSyncPlanner
import org.cabetus.data.local.ClassWebAttendanceDao
import org.cabetus.data.settings.SettingsRepository
import org.cabetus.notification.ClassAttendanceSyncNotifications
import org.cabetus.work.ClassAttendanceSyncContract as C

/** User-started, bounded, one-shot sync. Activity lifetime never owns the operation. */
@HiltWorker
class ClassAttendanceSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val transport: ClassAttendanceHttpsTransport,
    private val settings: SettingsRepository,
    private val dao: ClassWebAttendanceDao,
    private val notifications: ClassAttendanceSyncNotifications,
) : CoroutineWorker(context, parameters) {
    private var processed = 0
    private var total = 0
    private var saved = 0
    private var storagePhase = false
    private fun data(reason: C.Reason? = null) = workDataOf(C.PROCESSED to processed, C.TOTAL to total,
        C.SAVED to saved, C.FAILED to (processed - saved), C.REASON to reason?.key)
    private suspend fun progress() {
        setProgress(data())
        val notification = notifications.progress(processed, total, id)
        val info = if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(C.PROGRESS_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(C.PROGRESS_NOTIFICATION_ID, notification)
        setForeground(info)
    }
    private fun finish(reason: C.Reason?): Result {
        notifications.finish(saved, total, reason?.key)
        return if (reason == null) Result.success(data()) else Result.failure(data(reason))
    }
    override suspend fun doWork(): Result = try {
        kotlinx.coroutines.withTimeout(600_000) { runSync() }
    } catch (_: kotlinx.coroutines.TimeoutCancellationException) { finish(C.Reason.NETWORK) }

    private suspend fun runSync(): Result {
        try {
            if (!C.ENABLED) return finish(C.Reason.UNSUPPORTED)
            progress()
            val year = inputData.getInt(C.YEAR, 0)
            require(year in 2000..2100 && settings.current().academicYear == year)
            var response = transport.get(ClassAttendanceHttpsTransport.LIST_PATH)
            var page = ClassAttendanceNetworkPage(response.html, response.path)
            if (page.isLogin()) throw ClassAttendanceHttpsTransport.AuthRequired()
            val context = page.context()
            require(context.first == year)
            val roster = page.roster()
            total = roster.size
            progress()
            for (row in roster) {
                currentCoroutineContext().ensureActive()
                require(settings.current().academicYear == year)
                // Re-read the exact row identifier from the current, verified response.
                require(page.context() == context && page.roster().map { it.code } == roster.map { it.code })
                val fresh = page.roster().single { it.code == row.code }
                response = transport.post(response.path, page.postFields(fresh.componentId))
                val detail = ClassAttendanceNetworkPage(response.html, response.path)
                if (detail.isLogin()) throw ClassAttendanceHttpsTransport.AuthRequired()
                require(detail.context() == context && detail.detailCode() == row.code)
                val back = if (processed + 1 < total) detail.backComponent() else null
                val records = ClassAttendanceSyncPlanner.plan(detail.detail(), year, context.second, row.code, System.currentTimeMillis())
                currentCoroutineContext().ensureActive()
                require(settings.current().academicYear == year)
                storagePhase = true
                dao.upsertAll(records)
                storagePhase = false
                saved++; processed++
                progress()
                if (processed < total) {
                    response = transport.post(response.path, detail.postFields(requireNotNull(back)))
                    page = ClassAttendanceNetworkPage(response.html, response.path)
                    if (page.isLogin()) throw ClassAttendanceHttpsTransport.AuthRequired()
                }
            }
            return finish(null)
        } catch (e: CancellationException) {
            notifications.finish(saved, total, C.Reason.CANCELLED.key)
            throw e
        } catch (_: ClassAttendanceHttpsTransport.AuthRequired) {
            return finish(C.Reason.AUTH)
        } catch (_: ClassAttendanceHttpsTransport.Unsupported) {
            return finish(C.Reason.UNSUPPORTED)
        } catch (_: UnsupportedOperationException) {
            return finish(C.Reason.UNSUPPORTED)
        } catch (_: java.io.IOException) {
            return finish(C.Reason.NETWORK)
        } catch (_: Exception) {
            return finish(if (storagePhase) C.Reason.STORAGE else C.Reason.VALIDATION)
        }
    }
}
