package org.cabetus.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import org.cabetus.data.local.AttendanceOverrideDao
import org.cabetus.data.local.AttendanceOverrideEntity
import org.cabetus.data.local.ClassSessionDao
import org.cabetus.data.local.LocalAttendanceDao
import org.cabetus.data.settings.SettingsRepository
import org.cabetus.domain.AttendanceStatus
import org.cabetus.widget.WidgetUpdater
import org.cabetus.work.DailySummaryWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * AlarmManager からの発火を受ける。授業開始通知・AIデイリー通知の起点。
 */
@AndroidEntryPoint
class AlarmReceiver : BroadcastReceiver() {

    @Inject
    lateinit var notificationHelper: NotificationHelper

    @Inject
    lateinit var alarmScheduler: AlarmScheduler

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var widgetUpdater: WidgetUpdater

    @Inject
    lateinit var localAttendanceDao: LocalAttendanceDao

    @Inject
    lateinit var attendanceOverrideDao: AttendanceOverrideDao

    @Inject
    lateinit var classSessionDao: ClassSessionDao

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getStringExtra(EXTRA_KIND)) {
            KIND_CLASS_START -> {
                val courseName = intent.getStringExtra(AlarmScheduler.EXTRA_COURSE_NAME) ?: return
                val room = intent.getStringExtra(AlarmScheduler.EXTRA_ROOM)
                val courseCode = intent.getStringExtra(AlarmScheduler.EXTRA_COURSE_CODE) ?: ""
                val date = intent.getLongExtra(AlarmScheduler.EXTRA_DATE, 0L)
                val period = intent.getIntExtra(AlarmScheduler.EXTRA_PERIOD, 0)
                notificationHelper.notifyClassStart(courseName, room, courseCode, date, period)
            }

            KIND_DAILY_SUMMARY -> {
                // AI生成は時間がかかるため Worker に委譲
                WorkManager.getInstance(context).enqueue(
                    OneTimeWorkRequestBuilder<DailySummaryWorker>().build(),
                )
                // 翌日分を再スケジュール
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val s = settingsRepository.current()
                        alarmScheduler.scheduleDailySummary(
                            s.notifications.dailySummaryHour,
                            s.notifications.dailySummaryMinute,
                        )
                        alarmScheduler.rescheduleClassAlarms()
                    } finally {
                        pending.finish()
                    }
                }
            }

            KIND_ATTENDANCE_REMINDER -> {
                val courseName = intent.getStringExtra(AlarmScheduler.EXTRA_COURSE_NAME) ?: return
                val room = intent.getStringExtra(AlarmScheduler.EXTRA_ROOM)
                val courseCode = intent.getStringExtra(AlarmScheduler.EXTRA_COURSE_CODE) ?: return
                val date = intent.getLongExtra(AlarmScheduler.EXTRA_DATE, 0L)
                val period = intent.getIntExtra(AlarmScheduler.EXTRA_PERIOD, 0)
                val minutes = intent.getIntExtra(AlarmScheduler.EXTRA_MINUTES, 10)
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        // 設定が後から無効化された場合や、既に出席記録済みの場合は通知しない
                        val s = settingsRepository.current()
                        if (!s.notifications.attendanceReminder) return@launch
                        val recorded = localAttendanceDao.exists(courseCode, date, period) ||
                            attendanceOverrideDao.get(courseCode, date, period) != null
                        if (!recorded) {
                            notificationHelper.notifyAttendanceReminder(
                                courseName, room, courseCode, date, period, minutes,
                            )
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }

            KIND_NEXT_CLASS_WIDGET -> {
                // コマ境界に到達。ウィジェットを再描画し、次の境界を再登録する。
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        widgetUpdater.updateAll()
                        alarmScheduler.scheduleNextClassWidgetUpdate()
                    } finally {
                        pending.finish()
                    }
                }
            }

            KIND_AUTO_ABSENT -> {
                // 授業終了時点で出席未登録なら自動で欠席として記録する。
                val courseName = intent.getStringExtra(AlarmScheduler.EXTRA_COURSE_NAME) ?: return
                val room = intent.getStringExtra(AlarmScheduler.EXTRA_ROOM)
                val courseCode = intent.getStringExtra(AlarmScheduler.EXTRA_COURSE_CODE) ?: return
                val date = intent.getLongExtra(AlarmScheduler.EXTRA_DATE, 0L)
                val period = intent.getIntExtra(AlarmScheduler.EXTRA_PERIOD, 0)
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val s = settingsRepository.current()
                        if (!s.notifications.autoMarkAbsent) return@launch
                        val recorded = localAttendanceDao.exists(courseCode, date, period) ||
                            attendanceOverrideDao.get(courseCode, date, period) != null
                        // 出欠PDFで既にマーク済みなら自動記録しない
                        val mark = classSessionDao.getBetween(date, date + DAY_MILLIS)
                            .firstOrNull { it.courseCode == courseCode && it.period == period }?.mark
                        if (!recorded && mark.isNullOrBlank()) {
                            attendanceOverrideDao.upsert(
                                AttendanceOverrideEntity(
                                    courseCode = courseCode,
                                    date = date,
                                    period = period,
                                    status = AttendanceStatus.ABSENT.name,
                                    updatedAt = System.currentTimeMillis(),
                                ),
                            )
                            notificationHelper.notifyAutoAbsent(courseName, room, courseCode, date, period)
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_KIND = "kind"
        const val KIND_CLASS_START = "class_start"
        const val KIND_DAILY_SUMMARY = "daily_summary"
        const val KIND_NEXT_CLASS_WIDGET = "next_class_widget"
        const val KIND_ATTENDANCE_REMINDER = "attendance_reminder"
        const val KIND_AUTO_ABSENT = "auto_absent"
        private const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}
