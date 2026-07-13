package org.cabetus.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.getSystemService
import org.cabetus.data.local.ClassCourseDao
import org.cabetus.data.local.ClassSessionDao
import org.cabetus.data.local.TimetableDao
import org.cabetus.data.settings.SettingsRepository
import org.cabetus.domain.NextClassResolver
import org.cabetus.domain.PeriodTimes
import org.cabetus.widget.NextClassWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 授業開始通知（開始5分前）とAIデイリー通知の AlarmManager スケジューラ。
 */
@Singleton
class AlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val classSessionDao: ClassSessionDao,
    private val timetableDao: TimetableDao,
    private val classCourseDao: ClassCourseDao,
    private val settingsRepository: SettingsRepository,
) {
    private val zone: ZoneId = ZoneId.of("Asia/Tokyo")
    private val am get() = context.getSystemService<AlarmManager>()

    /** 授業開始アラーム・出席リマインダーを今日・明日分について再登録する。 */
    suspend fun rescheduleClassAlarms() {
        val settings = settingsRepository.current()
        val classStart = settings.notifications.classStart
        val reminder = settings.notifications.attendanceReminder
        val reminderMin = settings.notifications.attendanceReminderMinutes.toLong()
        if (!classStart && !reminder) return
        val nameByCode = classCourseDao.getAll().associate { it.code to it.name }
        val roomByCodePeriod = timetableDao.getAll()
            .associateBy { it.courseCode to it.period }

        val now = System.currentTimeMillis()
        val today = LocalDate.now(zone)
        val from = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = today.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()

        // 出欠PDF由来の実日程を優先（不規則な複数回にも対応）
        val sessions = classSessionDao.getBetween(from, to)
        val meetings = if (sessions.isNotEmpty()) {
            sessions.map { Meeting(it.courseCode, it.period, it.date) }
        } else {
            // フォールバック: 週間グリッドから今日・明日の該当コマを算出
            val cells = timetableDao.getAll()
            listOf(today, today.plusDays(1)).flatMap { date ->
                val dow = date.dayOfWeek.value
                cells.filter { it.dayOfWeek == dow }.map {
                    Meeting(it.courseCode, it.period, date.atStartOfDay(zone).toInstant().toEpochMilli())
                }
            }
        }

        for (m in meetings) {
            val pt = PeriodTimes.of(m.period) ?: continue
            val dateLocal = Instant.ofEpochMilli(m.date).atZone(zone).toLocalDate()
            val room = roomByCodePeriod[m.courseCode to m.period]
            val name = nameByCode[m.courseCode] ?: m.courseCode

            // 授業開始5分前
            if (classStart) {
                val startAt = LocalDateTime.of(dateLocal, pt.start).minusMinutes(5)
                    .atZone(zone).toInstant().toEpochMilli()
                if (startAt > now) {
                    scheduleClassAlarm(startAt, m, name, room?.room, room?.isOnline ?: false)
                }
            }
            // 授業終了 reminderMin 分前（出席チェック忘れリマインド）
            if (reminder) {
                val remindAt = LocalDateTime.of(dateLocal, pt.end).minusMinutes(reminderMin)
                    .atZone(zone).toInstant().toEpochMilli()
                if (remindAt > now) {
                    scheduleAttendanceReminder(
                        remindAt, m, name, room?.room, room?.isOnline ?: false, reminderMin.toInt(),
                    )
                }
            }
        }
    }

    private fun scheduleAttendanceReminder(
        triggerAt: Long,
        m: Meeting,
        courseName: String,
        room: String?,
        isOnline: Boolean,
        minutesBefore: Int,
    ) {
        val requestCode = ("attend${m.courseCode}${m.date}${m.period}").hashCode()
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(AlarmReceiver.EXTRA_KIND, AlarmReceiver.KIND_ATTENDANCE_REMINDER)
            putExtra(EXTRA_COURSE_NAME, courseName)
            putExtra(EXTRA_ROOM, if (isOnline) "オンライン" else room)
            putExtra(EXTRA_COURSE_CODE, m.courseCode)
            putExtra(EXTRA_DATE, m.date)
            putExtra(EXTRA_PERIOD, m.period)
            putExtra(EXTRA_MINUTES, minutesBefore)
        }
        val pending = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        setExact(triggerAt, pending)
    }

    private fun scheduleClassAlarm(
        triggerAt: Long,
        m: Meeting,
        courseName: String,
        room: String?,
        isOnline: Boolean,
    ) {
        val requestCode = ("class${m.courseCode}${m.date}${m.period}").hashCode()
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(AlarmReceiver.EXTRA_KIND, AlarmReceiver.KIND_CLASS_START)
            putExtra(EXTRA_COURSE_NAME, courseName)
            putExtra(EXTRA_ROOM, if (isOnline) "オンライン" else room)
            putExtra(EXTRA_COURSE_CODE, m.courseCode)
            putExtra(EXTRA_DATE, m.date)
            putExtra(EXTRA_PERIOD, m.period)
        }
        val pending = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        setExact(triggerAt, pending)
    }

    /** デイリー通知アラームを次回発火時刻で登録する。 */
    fun scheduleDailySummary(hour: Int, minute: Int) {
        val now = LocalDateTime.now(zone)
        var target = now.withHour(hour.coerceIn(0, 23)).withMinute(minute.coerceIn(0, 59))
            .withSecond(0).withNano(0)
        if (!target.isAfter(now)) target = target.plusDays(1)
        val triggerAt = target.atZone(zone).toInstant().toEpochMilli()

        val intent = Intent(context, AlarmReceiver::class.java)
            .putExtra(AlarmReceiver.EXTRA_KIND, AlarmReceiver.KIND_DAILY_SUMMARY)
        val pending = PendingIntent.getBroadcast(
            context, DAILY_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        setExact(triggerAt, pending)
    }

    fun cancelDailySummary() {
        val intent = Intent(context, AlarmReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            context, DAILY_REQUEST_CODE, intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        pending?.let { am?.cancel(it) }
    }

    /**
     * 「次の授業」ウィジェットを次のコマ境界（授業開始/終了時刻）で自動更新するアラームを登録する。
     * 授業中ならその終了時刻、休み時間なら次のコマ開始時刻に発火する。発火のたびに
     * AlarmReceiver から再登録され、コマ境界をまたぐたびに表示（授業中↔次の授業）が追従する。
     * ウィジェットが未配置なら既存アラームを解除して何もしない。
     */
    suspend fun scheduleNextClassWidgetUpdate() {
        val placed = runCatching {
            GlanceAppWidgetManager(context).getGlanceIds(NextClassWidget::class.java).isNotEmpty()
        }.getOrDefault(false)
        if (!placed) {
            cancelNextClassWidgetUpdate()
            return
        }
        val cells = timetableDao.getAll()
        val nowDt = LocalDateTime.now(zone)
        val info = NextClassResolver.resolve(cells, nowDt) ?: return
        // 授業中→終了時刻で「次の授業」に、休み時間→開始時刻で「授業中」に切り替わる
        val boundary = if (info.isOngoing) info.end else info.start
        val today = LocalDate.now(zone)
        var triggerAt: Long? = null
        for (offset in 0..8) {
            val date = today.plusDays(offset.toLong())
            if (date.dayOfWeek.value != info.dayOfWeek) continue
            val dt = LocalDateTime.of(date, boundary)
            if (dt.isAfter(nowDt)) {
                triggerAt = dt.atZone(zone).toInstant().toEpochMilli()
                break
            }
        }
        val at = triggerAt ?: return
        val intent = Intent(context, AlarmReceiver::class.java)
            .putExtra(AlarmReceiver.EXTRA_KIND, AlarmReceiver.KIND_NEXT_CLASS_WIDGET)
        val pending = PendingIntent.getBroadcast(
            context, NEXT_CLASS_WIDGET_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        setExact(at, pending)
    }

    fun cancelNextClassWidgetUpdate() {
        val intent = Intent(context, AlarmReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            context, NEXT_CLASS_WIDGET_REQUEST_CODE, intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        pending?.let { am?.cancel(it) }
    }

    private fun setExact(triggerAt: Long, pending: PendingIntent) {
        val manager = am ?: return
        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            manager.canScheduleExactAlarms()
        } else {
            true
        }
        try {
            if (canExact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }
        } catch (_: SecurityException) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }

    private data class Meeting(val courseCode: String, val period: Int, val date: Long)

    companion object {
        const val EXTRA_COURSE_NAME = "course_name"
        const val EXTRA_ROOM = "room"
        const val EXTRA_COURSE_CODE = "course_code"
        const val EXTRA_DATE = "date"
        const val EXTRA_PERIOD = "period"
        const val EXTRA_MINUTES = "minutes"
        private const val DAILY_REQUEST_CODE = 999001
        private const val NEXT_CLASS_WIDGET_REQUEST_CODE = 999002
    }
}
