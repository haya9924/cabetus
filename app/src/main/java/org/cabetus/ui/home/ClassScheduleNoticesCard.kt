package org.cabetus.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.cabetus.ui.browser.ClassAttendanceBackgroundController.ScheduleStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val noticeTimeFormat = DateTimeFormatter.ofPattern("M/d HH:mm").withZone(ZoneId.of("Asia/Tokyo"))

@Composable
internal fun ClassScheduleNoticesCard(status: ScheduleStatus, openClass: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("CLASSの授業変更", style = MaterialTheme.typography.titleSmall)
            val message = when {
                status.running -> "休講・実施場所変更の掲示を取得中…"
                status.authRequired -> "CLASSでログインしてください。"
                status.failed -> "最新の掲示を確認できませんでした。"
                status.snapshot == null -> "CLASSの掲示を未確認です。"
                else -> null
            }
            if (message != null) Text(message, style = MaterialTheme.typography.bodySmall)
            val snapshot = status.snapshot
            if (snapshot != null) {
                status.refreshedAt?.let {
                    Text("取得時点: ${noticeTimeFormat.format(Instant.ofEpochMilli(it))}", style = MaterialTheme.typography.labelSmall)
                }
                snapshot.titles.take(5).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (snapshot.titles.isEmpty()) {
                    Text("取得した一覧に対象の掲示はありません。", style = MaterialTheme.typography.bodySmall)
                }
                if (snapshot.hasMore || snapshot.titles.size > 5) {
                    Text("このカードには一覧の一部のみ表示しています。", style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(onClick = openClass) { Text("CLASSで確認") }
        }
    }
}
