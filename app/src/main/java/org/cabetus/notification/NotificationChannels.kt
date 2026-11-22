package org.cabetus.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService

/** 通知チャンネル。仕様どおり用途ごとに全て別チャンネルにする。 */
object NotificationChannels {
    const val DAILY_SUMMARY = "daily_summary"
    const val CLASS_START = "class_start"
    const val ATTENDANCE_REMINDER = "attendance_reminder"
    const val ATTENDANCE_RISK = "attendance_risk"
    const val DEADLINE = "deadline"
    const val NEW_ASSIGNMENT = "new_assignment"
    const val LETUS_CHANGE = "letus_change"
    const val FETCH_FAILURE = "fetch_failure"
    const val FETCH_PROGRESS = "fetch_progress"

    fun createAll(context: Context) {
        val nm = context.getSystemService<NotificationManager>() ?: return
        val channels = listOf(
            NotificationChannel(
                DAILY_SUMMARY, "今日のまとめ", NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "AIによる今日のひとことアシスタント" },
            NotificationChannel(
                CLASS_START, "授業開始", NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "授業開始5分前のお知らせ" },
            NotificationChannel(
                ATTENDANCE_REMINDER, "出席チェックのリマインド", NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "授業終了前に出席が未記録のときのお知らせ" },
            NotificationChannel(
                ATTENDANCE_RISK, "欠席リスク", NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "欠席可能回数が少なくなったときのお知らせ" },
            NotificationChannel(
                DEADLINE, "課題の期日", NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "課題の期日が近いお知らせ" },
            NotificationChannel(
                NEW_ASSIGNMENT, "課題の新規検出", NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "初めて取得した課題を科目・締切とともにお知らせします" },
            NotificationChannel(
                LETUS_CHANGE, "LETUSの変更", NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "既存の課題の締切・内容が変わったお知らせ" },
            NotificationChannel(
                FETCH_FAILURE, "取得の失敗", NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "データ取得に失敗したお知らせ" },
            NotificationChannel(
                FETCH_PROGRESS, "更新中", NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "課題の取得中に表示するお知らせ" },
        )
        nm.createNotificationChannels(channels)
    }
}
