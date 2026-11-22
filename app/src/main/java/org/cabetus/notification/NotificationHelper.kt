package org.cabetus.notification

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.cabetus.data.letus.AssignmentChange
import org.cabetus.data.local.AssignmentEntity
import org.cabetus.data.local.NotificationHistoryDao
import org.cabetus.data.local.NotificationHistoryEntity
import org.cabetus.data.local.NotificationType
import org.cabetus.data.local.NotifiedKeyDao
import org.cabetus.data.local.NotifiedKeyEntity
import org.cabetus.ui.MainActivity
import org.cabetus.ui.browser.BrowserIntents
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notifiedKeyDao: NotifiedKeyDao,
    private val notificationHistoryDao: NotificationHistoryDao,
) {
    private val nm = NotificationManagerCompat.from(context)

    private fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    private fun smallIcon(): Int = android.R.drawable.ic_dialog_info

    private fun openUrlIntent(url: String, requestCode: Int): PendingIntent {
        // LETUS / CLASS はアプリ内ブラウザ、それ以外は外部ブラウザへ。
        // URL が不正な場合は課題一覧を開く。
        val intent = BrowserIntents.createOpenIntent(context, url)
            ?: return openAppIntent(
                requestCode,
                MainActivity.EXTRA_NAVIGATE to MainActivity.NAV_ASSIGNMENTS,
            )
        return PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openAppIntent(requestCode: Int, extra: Pair<String, String>? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        extra?.let { intent.putExtra(it.first, it.second) }
        return PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun notifyNewAssignments(newOnes: List<AssignmentEntity>, isInitialImport: Boolean = false) {
        if (!canPost() || !nm.areNotificationsEnabled()) return
        val presentation = NewAssignmentPresentation.build(newOnes, isInitialImport) ?: return
        val single = presentation.assignments.singleOrNull()
        val id = single?.let { ("new" + it.id).hashCode() } ?: "new_multi".hashCode()
        val intent = single?.let { openUrlIntent(it.url, id) } ?: openAppIntent(
            id, MainActivity.EXTRA_NAVIGATE to MainActivity.NAV_ASSIGNMENTS,
        )
        val notification = NotificationCompat.Builder(context, NotificationChannels.NEW_ASSIGNMENT)
            .setSmallIcon(smallIcon())
            .setContentTitle(presentation.title)
            .setContentText(presentation.summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(presentation.expandedText))
            .setNumber(presentation.assignments.size)
            .setAutoCancel(true)
            .setContentIntent(intent)
            .setSilent(isInitialImport)
            .build()
        try {
            nm.notify(id, notification)
            recordHistory(NotificationType.NEW_ASSIGNMENT, presentation.title,
                presentation.expandedText, url = single?.url)
        } catch (_: SecurityException) {
            // 通知権限の変更と競合した場合も取得結果は保持する。
        }
    }

    /** LETUS 上で既存課題に変更（締切・タイトル）があったときのお知らせ。 */
    fun notifyAssignmentChanges(changes: List<AssignmentChange>) {
        if (!canPost() || changes.isEmpty()) return
        if (changes.size == 1) {
            val c = changes.first()
            recordHistory(
                NotificationType.LETUS_CHANGE,
                "課題の変更: ${c.assignment.courseName}",
                "${c.assignment.title}\n${c.changes.joinToString("\n")}",
                url = c.assignment.url,
            )
            post(
                NotificationChannels.LETUS_CHANGE,
                id = ("chg" + c.assignment.id).hashCode(),
                title = "課題の変更: ${c.assignment.courseName}",
                text = "${c.assignment.title}\n${c.changes.joinToString("\n")}",
                contentIntent = openUrlIntent(c.assignment.url, ("chg" + c.assignment.id).hashCode()),
            )
        } else {
            val first = changes.first()
            recordHistory(
                NotificationType.LETUS_CHANGE,
                "課題に${changes.size}件の変更",
                "${first.assignment.title} ほか",
                url = null,
            )
            post(
                NotificationChannels.LETUS_CHANGE,
                id = "chg_multi".hashCode(),
                title = "課題に${changes.size}件の変更",
                text = "${first.assignment.title} ほか",
                contentIntent = openAppIntent(
                    "chg_multi".hashCode(),
                    MainActivity.EXTRA_NAVIGATE to MainActivity.NAV_ASSIGNMENTS,
                ),
            )
        }
    }

    /**
     * 締切24h/3h/1h前の警告を出す。NotifiedKey で1回ずつに抑制する。
     */
    suspend fun notifyDeadlineWarnings(assignments: List<AssignmentEntity>) {
        if (!canPost()) return
        val now = System.currentTimeMillis()
        val oneHour = 60L * 60 * 1000
        for (a in assignments) {
            val d = a.deadline ?: continue
            val diff = d - now
            if (diff <= 0) continue
            val (bucket, label) = when {
                diff <= oneHour -> "1h" to "締切まで1時間以内"
                diff <= 3 * oneHour -> "3h" to "締切まで3時間以内"
                diff <= 24 * oneHour -> "24h" to "締切まで24時間以内"
                else -> continue
            }
            val key = "${a.id}:$bucket"
            if (notifiedKeyDao.exists(key)) continue
            recordHistory(
                NotificationType.DEADLINE,
                label,
                "${a.title}\n${a.courseName}",
                url = a.url,
            )
            post(
                NotificationChannels.DEADLINE,
                id = key.hashCode(),
                title = label,
                text = "${a.title}\n${a.courseName}",
                contentIntent = openUrlIntent(a.url, key.hashCode()),
            )
            notifiedKeyDao.insert(NotifiedKeyEntity(key, now))
        }
    }

    fun notifyFetchFailure(loginRequired: Boolean) {
        if (!canPost()) return
        val (title, text, intent) = if (loginRequired) {
            Triple(
                "再ログインが必要です",
                "LETUS のログインが必要です。タップしてログインしてください。",
                openAppIntent("login".hashCode(), MainActivity.EXTRA_NAVIGATE to MainActivity.NAV_LOGIN),
            )
        } else {
            Triple(
                "取得に失敗しました",
                "ネットワーク接続を確認してください。",
                openAppIntent("fail".hashCode()),
            )
        }
        recordHistory(
            NotificationType.FETCH_FAILURE,
            title,
            text,
            url = null,
        )
        post(NotificationChannels.FETCH_FAILURE, "fail".hashCode(), title, text, intent)
    }

    fun notifyDailySummary(text: String) {
        if (!canPost() || text.isBlank()) return
        recordHistory(
            NotificationType.DAILY_SUMMARY,
            "今日のまとめ",
            text,
            url = null,
        )
        post(
            NotificationChannels.DAILY_SUMMARY,
            id = "daily".hashCode(),
            title = "今日のまとめ",
            text = text,
            contentIntent = openAppIntent("daily".hashCode()),
        )
    }

    /** 授業開始通知（出席チェックのアクション付き）。 */
    fun notifyClassStart(
        courseName: String,
        room: String?,
        courseCode: String,
        date: Long,
        period: Int,
    ) {
        if (!canPost()) return
        val notifId = "class$courseCode$date$period".hashCode()
        val checkIntent = Intent(context, AttendanceCheckActivity::class.java).apply {
            putExtra(AttendanceCheckActivity.EXTRA_COURSE_CODE, courseCode)
            putExtra(AttendanceCheckActivity.EXTRA_DATE, date)
            putExtra(AttendanceCheckActivity.EXTRA_PERIOD, period)
            putExtra(AttendanceCheckActivity.EXTRA_NOTIFICATION_ID, notifId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val checkPending = PendingIntent.getActivity(
            context, notifId, checkIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val roomText = room?.let { "（$it）" } ?: ""
        recordHistory(
            NotificationType.CLASS_START,
            "まもなく授業: $courseName",
            "${period}限$roomText が始まります",
            url = null,
        )
        val builder = NotificationCompat.Builder(context, NotificationChannels.CLASS_START)
            .setSmallIcon(smallIcon())
            .setContentTitle("まもなく授業: $courseName")
            .setContentText("${period}限$roomText が始まります")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "出席にカウント", checkPending)
            .setContentIntent(openAppIntent(notifId))
        safeNotify(notifId, builder)
    }

    /** 出席チェック忘れのリマインド通知（「出席にカウント」アクション付き）。 */
    fun notifyAttendanceReminder(
        courseName: String,
        room: String?,
        courseCode: String,
        date: Long,
        period: Int,
        minutesBefore: Int,
    ) {
        if (!canPost()) return
        val notifId = "attend$courseCode$date$period".hashCode()
        val checkIntent = Intent(context, AttendanceCheckActivity::class.java).apply {
            putExtra(AttendanceCheckActivity.EXTRA_COURSE_CODE, courseCode)
            putExtra(AttendanceCheckActivity.EXTRA_DATE, date)
            putExtra(AttendanceCheckActivity.EXTRA_PERIOD, period)
            putExtra(AttendanceCheckActivity.EXTRA_NOTIFICATION_ID, notifId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val checkPending = PendingIntent.getActivity(
            context, notifId, checkIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val roomText = room?.let { "（$it）" } ?: ""
        recordHistory(
            NotificationType.ATTENDANCE_REMINDER,
            "出席チェックがまだです: $courseName",
            "${period}限$roomText 終了${minutesBefore}分前",
            url = null,
        )
        val builder = NotificationCompat.Builder(context, NotificationChannels.ATTENDANCE_REMINDER)
            .setSmallIcon(smallIcon())
            .setContentTitle("出席チェックがまだです: $courseName")
            .setContentText("${period}限$roomText 終了${minutesBefore}分前。出席済みならタップして記録してください")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "出席にカウント", checkPending)
            .setContentIntent(openAppIntent(notifId))
        safeNotify(notifId, builder)
    }

    /** 授業終了時点で未登録だったため、自動で欠席として記録したときの通知。 */
    fun notifyAutoAbsent(
        courseName: String,
        room: String?,
        courseCode: String,
        date: Long,
        period: Int,
    ) {
        if (!canPost()) return
        val notifId = "autoAbsent$courseCode$date$period".hashCode()
        val roomText = room?.let { "（$it）" } ?: ""
        recordHistory(
            NotificationType.AUTO_ABSENT,
            "出席未登録: $courseName",
            "${period}限$roomText を欠席として自動記録しました",
            url = null,
        )
        val builder = NotificationCompat.Builder(context, NotificationChannels.ATTENDANCE_REMINDER)
            .setSmallIcon(smallIcon())
            .setContentTitle("出席未登録のため欠席としました")
            .setContentText("$courseName ${period}限$roomText を欠席として自動記録しました。誤りの場合は時間割から修正できます")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openAppIntent(notifId, MainActivity.EXTRA_NAVIGATE to MainActivity.NAV_TIMETABLE))
        safeNotify(notifId, builder)
    }

    /**
     * 欠席可能回数が少なくなった科目の警告。NotifiedKey を DB で claim して重複を抑止する。
     * remaining は欠席可能な残り回数（負数は目標達成不可）。
     */
    suspend fun notifyAttendanceRisk(
        year: Int,
        code: String,
        name: String,
        remaining: Int,
    ) {
        if (year <= 0 || remaining > 2) return
        if (!canPost() || !nm.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            nm.getNotificationChannel(NotificationChannels.ATTENDANCE_RISK)?.importance == android.app.NotificationManager.IMPORTANCE_NONE) return
        val bucket = when {
            remaining >= 2 -> "2"
            remaining == 1 -> "1"
            remaining == 0 -> "0"
            else -> "exceeded"
        }
        val key = "attendance-risk:v1:$year:$code:$bucket"
        val claimed = notifiedKeyDao.claim(NotifiedKeyEntity(key, System.currentTimeMillis()))
        if (claimed == -1L) return
        val title = "欠席リスク: $name"
        val text = if (remaining < 0) {
            "記録済みの出欠では欠席許容回数を超え、目標達成不可です。未取得の記録は含みません。時間割や目標設定を確認してください。"
        } else {
            "記録済みの出欠では欠席可能はあと${remaining}回です。未取得の記録がある場合はさらに少なくなります。時間割や目標設定を確認してください。"
        }
        val notifId = "attendance-risk:$code".hashCode()
        recordHistory(
            NotificationType.ATTENDANCE_RISK,
            title,
            text,
            url = null,
        )
        val builder = NotificationCompat.Builder(context, NotificationChannels.ATTENDANCE_RISK)
            .setSmallIcon(smallIcon())
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openAppIntent(notifId))
        try {
            nm.notify(notifId, builder.build())
        } catch (e: Exception) {
            notifiedKeyDao.deleteByKey(key)
        }
    }

    /** 課題取得中に表示する「更新中」通知（別チャンネル・無音・常駐）。 */
    fun notifyFetchProgress() {
        if (!canPost()) return
        val builder = NotificationCompat.Builder(context, NotificationChannels.FETCH_PROGRESS)
            .setSmallIcon(smallIcon())
            .setContentTitle("更新中")
            .setContentText("LETUS から課題を取得しています…")
            .setOngoing(true)
            .setSilent(true)
            .setProgress(0, 0, true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent(PROGRESS_ID))
        runCatching { nm.notify(PROGRESS_ID, builder.build()) }
    }

    /** 「更新中」通知を消去する。 */
    fun cancelFetchProgress() {
        runCatching { nm.cancel(PROGRESS_ID) }
    }

    private fun recordHistory(
        type: NotificationType,
        title: String,
        text: String,
        url: String?,
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                notificationHistoryDao.insert(
                    NotificationHistoryEntity(
                        type = type,
                        title = title,
                        text = text,
                        url = url,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }
    private fun post(
        channel: String,
        id: Int,
        title: String,
        text: String,
        contentIntent: PendingIntent,
    ) {
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(smallIcon())
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
        safeNotify(id, builder)
    }

    private fun safeNotify(id: Int, builder: NotificationCompat.Builder) {
        if (!canPost()) return
        runCatching { nm.notify(id, builder.build()) }
    }

    companion object {
        private val PROGRESS_ID = "fetch_progress".hashCode()

        private val TIME_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("M/d HH:mm").withZone(ZoneId.of("Asia/Tokyo"))

        fun formatDeadline(epoch: Long?): String =
            epoch?.let { TIME_FMT.format(Instant.ofEpochMilli(it)) } ?: "期限なし"
    }
}
