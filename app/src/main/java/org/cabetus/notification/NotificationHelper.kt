package org.cabetus.notification

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.cabetus.data.local.AssignmentEntity
import org.cabetus.data.local.NotifiedKeyDao
import org.cabetus.data.local.NotifiedKeyEntity
import org.cabetus.ui.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notifiedKeyDao: NotifiedKeyDao,
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
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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

    fun notifyNewAssignments(newOnes: List<AssignmentEntity>) {
        if (!canPost() || newOnes.isEmpty()) return
        if (newOnes.size == 1) {
            val a = newOnes.first()
            post(
                NotificationChannels.NEW_ASSIGNMENT,
                id = ("new" + a.id).hashCode(),
                title = "新しい課題: ${a.courseName}",
                text = a.title,
                contentIntent = openUrlIntent(a.url, ("new" + a.id).hashCode()),
            )
        } else {
            val first = newOnes.first()
            post(
                NotificationChannels.NEW_ASSIGNMENT,
                id = "new_multi".hashCode(),
                title = "新しい課題が${newOnes.size}件",
                text = "${first.title} ほか",
                contentIntent = openAppIntent("new_multi".hashCode()),
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
        post(NotificationChannels.FETCH_FAILURE, "fail".hashCode(), title, text, intent)
    }

    fun notifyDailySummary(text: String) {
        if (!canPost() || text.isBlank()) return
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
        private val TIME_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("M/d HH:mm").withZone(ZoneId.of("Asia/Tokyo"))

        fun formatDeadline(epoch: Long?): String =
            epoch?.let { TIME_FMT.format(Instant.ofEpochMilli(it)) } ?: "期限なし"
    }
}
