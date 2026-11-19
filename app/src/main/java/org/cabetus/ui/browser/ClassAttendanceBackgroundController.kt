package org.cabetus.ui.browser

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import org.cabetus.data.classweb.ClassAttendanceTour
import org.cabetus.data.classweb.ClassScheduleNoticesDom
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ClassAttendanceBackgroundController @Inject constructor(@ApplicationContext private val context: Context) {
    data class Status(val running: Boolean = false, val message: String = "", val saved: Int = 0,
                      val total: Int = 0, val authRequired: Boolean = false, val result: ClassAttendanceTour.State? = null)
    data class MobileStatus(val running: Boolean = false, val message: String = "",
                            val authRequired: Boolean = false, val completedAt: Long? = null)
    /** 変更通知一覧モード。前回の検証済み一覧は失敗時も保持する。 */
    data class ScheduleStatus(
        val running: Boolean = false,
        val snapshot: ClassScheduleNoticesDom.Snapshot? = null,
        val refreshedAt: Long? = null,
        val failed: Boolean = false,
        val authRequired: Boolean = false,
    )
    /**
     * 時間割の初回取り込みモード。
     *
     * 年度・学期は画面から取得できないため推定しない（未設定も許容）。既存の時間割がある
     * 状態では取り込まず、[failed] と [authRequired] で結果を区別する。出欠の保存・通知は行わない。
     */
    data class TimetableStatus(
        val running: Boolean = false,
        val message: String = "",
        val insertedCells: Int = 0,
        val createdCourses: Int = 0,
        val failed: Boolean = false,
        val authRequired: Boolean = false,
        val completedAt: Long? = null,
    )
    private val mutable = MutableStateFlow(Status())
    val status = mutable.asStateFlow()
    private val mobileMutable = MutableStateFlow(MobileStatus())
    val mobileStatus = mobileMutable.asStateFlow()
    private val scheduleMutable = MutableStateFlow(ScheduleStatus())
    val scheduleStatus = scheduleMutable.asStateFlow()
    private val timetableMutable = MutableStateFlow(TimetableStatus())
    val timetableStatus = timetableMutable.asStateFlow()
    private val browserSessionMutable = MutableStateFlow(0L)
    val browserSession = browserSessionMutable
    // Main-thread ownership gate: no second foreground or background WebView during a tour.
    private var browserOwners = 0
    private var foregroundRequested = false
    private val foregroundOpened = MutableStateFlow(false)

    /** 利用者の明示操作を自動読取より優先する。出欠巡回は中断しない。 */
    suspend fun openForeground(launch: () -> Boolean): Boolean {
        if (foregroundRequested || browserOwners > 0 || mutable.value.running) return false
        foregroundRequested = true
        foregroundOpened.value = false
        try {
            if (mobileMutable.value.running || scheduleMutable.value.running ||
                timetableMutable.value.running
            ) {
                try {
                    cancel()
                } catch (_: RuntimeException) {
                    return false
                }
                if (kotlinx.coroutines.withTimeoutOrNull(5_000) {
                        kotlinx.coroutines.flow.combine(
                            mobileStatus, scheduleStatus, timetableStatus,
                        ) { m, s, t -> !m.running && !s.running && !t.running }.first { it }
                    } == null
                ) return false
            }
            if (!launch()) return false
            // Activity の onCreate がブラウザを取得するまで自動読取を再開しない。
            return kotlinx.coroutines.withTimeoutOrNull(5_000) {
                foregroundOpened.first { it }
            } == true
        } finally {
            foregroundRequested = false
        }
    }
    val isBrowserBusy: Boolean
        get() = browserOwners > 0 || mutable.value.running || mobileMutable.value.running ||
            scheduleMutable.value.running || timetableMutable.value.running
    fun browserOpened(): Boolean {
        if (isBrowserBusy) return false
        browserOwners++
        foregroundOpened.value = true
        return true
    }
    fun browserClosed() {
        browserOwners = (browserOwners - 1).coerceAtLeast(0)
        browserSessionMutable.value += 1
    }
    fun start() {
        if (mutable.value.running) return
        check(!foregroundRequested) { "ブラウザを開いています。" }
        check(!mobileMutable.value.running) { "モバイル出席確認中です。完了してから更新してください。" }
        check(!scheduleMutable.value.running) { "変更通知を確認中です。完了してから更新してください。" }
        check(!timetableMutable.value.running) { "時間割を取り込み中です。完了してから更新してください。" }
        check(browserOwners == 0) { "CLASSブラウザを閉じてから更新してください。" }
        mutable.value = Status(running = true, message = "CLASSに接続中")
        try {
            ContextCompat.startForegroundService(context, Intent(context, ClassAttendanceTourService::class.java).setAction(ClassAttendanceTourService.START))
        } catch (_: RuntimeException) {
            mutable.value = Status(message = "更新を開始できません。アプリを表示して再試行してください。")
        }
    }
    fun cancel() { context.startService(Intent(context, ClassAttendanceTourService::class.java).setAction(ClassAttendanceTourService.CANCEL)) }
    /** Synchronously reserves the mobile-attendance slot, then asks the service to start. */
    fun tryStartMobileAttendance(): Boolean {
        if (isBrowserBusy || foregroundRequested) return false
        mobileMutable.value = MobileStatus(running = true, message = "確認中…")
        return try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ClassAttendanceTourService::class.java).setAction("org.cabetus.classTour.MOBILE")
            )
            true
        } catch (_: RuntimeException) {
            mobileMutable.value = MobileStatus(message = "取得できません", completedAt = System.currentTimeMillis())
            false
        }
    }
    internal fun publish(value: Status) { mutable.value = value }
    internal fun publishMobile(value: MobileStatus) { mobileMutable.value = value }
    /**
     * 変更通知一覧の自動読取を開始する。既存の出欠巡回・モバイル出席確認とは排他。
     * 前回の検証済み一覧は開始時・失敗時とも保持し、成功時だけ差し替える。
     */
    fun tryStartScheduleNotices(): Boolean {
        if (isBrowserBusy || foregroundRequested) return false
        scheduleMutable.value = scheduleMutable.value.copy(running = true, failed = false, authRequired = false)
        return try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ClassAttendanceTourService::class.java).setAction(ClassAttendanceTourService.SCHEDULE)
            )
            true
        } catch (_: RuntimeException) {
            scheduleMutable.value = scheduleMutable.value.copy(running = false, failed = true)
            false
        }
    }
    internal fun publishSchedule(value: ScheduleStatus) { scheduleMutable.value = value }
    /**
     * 既存の時間割が空のときだけ開始する初回取り込み。
     *
     * 出欠巡回・モバイル出席確認・変更通知と同じブラウザ枠を同期的に予約し、
     * 既存モードと同時に実行しない。年度・学期は推定も変更もしない。
     * false はブラウザ枠が使用中の場合だけ。開始エラーは状態で通知する。
     */
    fun tryStartInitialTimetable(): Boolean {
        if (isBrowserBusy || foregroundRequested) return false
        timetableMutable.value = timetableMutable.value.copy(
            running = true, failed = false, authRequired = false, message = "時間割を確認中",
        )
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ClassAttendanceTourService::class.java)
                    .setAction(ClassAttendanceTourService.TIMETABLE),
            )
        } catch (_: RuntimeException) {
            timetableMutable.value = timetableMutable.value.copy(
                running = false,
                failed = true,
                completedAt = System.currentTimeMillis(),
                message = "時間割を取り込めません。アプリを表示して再試行してください。",
            )
        }
        return true
    }
    internal fun publishTimetable(value: TimetableStatus) { timetableMutable.value = value }
}
