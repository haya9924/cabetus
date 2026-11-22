package org.cabetus.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import org.cabetus.ui.MainActivity
import org.cabetus.ui.browser.BrowserActivity
import org.cabetus.ui.browser.BrowserUrlPolicy
import org.cabetus.work.ClassAttendanceSyncContract as C
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ClassAttendanceSyncNotifications @Inject constructor(@ApplicationContext private val context: Context) {
    private fun channels() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(C.CHANNEL_PROGRESS, "CLASS出欠更新中", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(C.CHANNEL_RESULT, "CLASS出欠更新結果", NotificationManager.IMPORTANCE_DEFAULT))
    }
    private fun intent(auth: Boolean): PendingIntent {
        val target = if (auth) BrowserActivity.intent(context, BrowserUrlPolicy.CLASS_URL)
        else Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_NAVIGATE, MainActivity.NAV_TIMETABLE)
        return PendingIntent.getActivity(context, if (auth) 74003 else 74002, target,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    fun progress(processed: Int, total: Int, workId: UUID? = null): Notification {
        channels()
        val builder = NotificationCompat.Builder(context, C.CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("CLASS出欠の更新")
            .setContentText(if (total > 0) "更新中・$processed/$total 科目" else "出欠一覧を確認中")
            .setProgress(total.coerceAtLeast(0), processed.coerceIn(0, total.coerceAtLeast(0)), total <= 0)
            .setContentIntent(intent(false)).setOngoing(true).setOnlyAlertOnce(true)
        workId?.let { builder.addAction(0, "中止", WorkManager.getInstance(context).createCancelPendingIntent(it)) }
        return builder.build()
    }
    fun finish(saved: Int, total: Int, reason: String?) {
        channels()
        val auth = reason == C.Reason.AUTH.key
        val text = when {
            auth -> "CLASSへのログインが必要です。保存済み記録は保持しています。"
            reason == null && total > 0 && saved == total -> "$total 科目の出欠を更新しました。"
            saved > 0 -> "一部更新：$saved/$total 科目。未取得科目の記録は保持しています。"
            else -> C.Reason.fromKey(reason)?.label ?: "出欠を更新できませんでした。"
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        try {
            NotificationManagerCompat.from(context).notify(C.RESULT_NOTIFICATION_ID,
                NotificationCompat.Builder(context, C.CHANNEL_RESULT).setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("CLASS出欠の更新結果").setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true)
                    .setContentIntent(intent(auth)).build())
        } catch (_: SecurityException) { /* 許可の変更時にも更新結果はWorkInfoに残す。 */ }
    }
}
