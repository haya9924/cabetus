package org.cabetus.ui.timetable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.cabetus.domain.ClassScheduleNoticeOrganizer.Kind
import org.cabetus.domain.ClassScheduleNoticeOrganizer.Notice
import org.cabetus.ui.browser.ClassAttendanceBackgroundController.ScheduleStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val fetchedTimeFormat = DateTimeFormatter.ofPattern("M/d HH:mm").withZone(ZoneId.of("Asia/Tokyo"))

@Composable
internal fun CourseScheduleNotices(
    status: ScheduleStatus,
    notices: List<Notice>,
    unmatched: List<Notice>,
    onRefresh: () -> Unit,
    onOpenClass: () -> Unit,
) {
    var showUnmatched by remember { mutableStateOf(false) }
    val snapshot = status.snapshot
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("CLASSの授業変更", style = MaterialTheme.typography.titleMedium)
            val message = when {
                status.running -> "掲示を取得中…"
                status.authRequired -> "掲示の取得にはCLASSへのログインが必要です。"
                status.failed -> "最新の掲示を取得できませんでした。"
                snapshot == null -> "CLASSの掲示はまだ取得していません。"
                else -> null
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            status.refreshedAt?.let { time ->
                val label = if (message == null) "取得時点" else "表示中の掲示の取得時点"
                Text("$label: ${fetchedTimeFormat.format(Instant.ofEpochMilli(time))}", style = MaterialTheme.typography.labelSmall)
            }
            if (snapshot != null) {
                if (notices.isEmpty()) {
                    Text("取得した掲示に、この科目と特定できるものはありません。", style = MaterialTheme.typography.bodySmall)
                } else {
                    for (kind in Kind.entries) {
                        val group = notices.filter { it.kind == kind }
                        if (group.isEmpty()) continue
                        Text(
                            when (kind) {
                                Kind.CANCELLATION -> "休講"
                                Kind.ROOM_CHANGE -> "教室・実施場所の変更"
                                Kind.OTHER -> "その他の時間割変更"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        group.forEach { Text(it.title, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
                if (unmatched.isNotEmpty()) {
                    TextButton(onClick = { showUnmatched = !showUnmatched }) {
                        Text(if (showUnmatched) "科目未特定の掲示を閉じる" else "科目未特定の掲示（${unmatched.size}件）")
                    }
                    if (showUnmatched) {
                        unmatched.forEach { Text("• ${it.title}", style = MaterialTheme.typography.bodyMedium) }
                    }
                }
                if (snapshot.hasMore) {
                    Text("CLASSに続きの掲示があります。", style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = onRefresh,
                    enabled = !status.running && !status.authRequired,
                    modifier = Modifier.widthIn(min = 48.dp),
                ) { Text("掲示を更新") }
                TextButton(onClick = onOpenClass, modifier = Modifier.weight(1f, fill = false)) {
                    Text(if (status.authRequired) "CLASSにログイン" else "CLASSを開く")
                }
            }
        }
    }
}
