package org.cabetus.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService

/** 通知チャンネル。仕様どおり用途ごとに全て別チャンネルにする。 */
object NotificationChannels {
    const val DAILY_SUMMARY = "daily_summary"
    const val CLASS_START = "class_start"
    const val DEADLINE = "deadline"
    const val NEW_ASSIGNMENT = "new_assignment"
    const val FETCH_FAILURE = "fetch_failure"

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
                DEADLINE, "課題の期日", NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "課題の期日が近いお知らせ" },
            NotificationChannel(
                NEW_ASSIGNMENT, "新規課題", NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "新しい課題が見つかったお知らせ" },
            NotificationChannel(
                FETCH_FAILURE, "取得の失敗", NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "データ取得に失敗したお知らせ" },
        )
        nm.createNotificationChannels(channels)
    }
}
