package org.cabetus.ui.timetable

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.cabetus.domain.AttendanceSource
import org.cabetus.domain.AttendanceStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DATE_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("M/d（E）", Locale.JAPANESE).withZone(ZoneId.of("Asia/Tokyo"))

@Composable
fun CourseDetailSheet(
    detail: CourseDetail?,
    onOpenSyllabus: (String) -> Unit,
    onOpenLetus: (String) -> Unit,
    onOpenClass: () -> Unit,
    loadRows: suspend (String) -> List<AttendanceRowUi>,
    setStatus: suspend (String, Long, Int, AttendanceStatus?) -> Unit,
    onChanged: () -> Unit,
    confirmationRevision: List<org.cabetus.data.local.MobileAttendanceConfirmationEntity>,
    goalSettings: @Composable () -> Unit,
) {
    var showSettings by remember { mutableStateOf(false) }
    var showAttendanceDialog by remember { mutableStateOf(false) }
    val overviewScroll = rememberScrollState()
    val settingsScroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(if (showSettings) settingsScroll else overviewScroll)
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (detail == null) {
            CircularProgressIndicator()
            return@Column
        }

        if (!showSettings) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    detail.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(onClick = { showSettings = true }) {
                    Icon(Icons.Filled.Settings, contentDescription = "授業の設定")
                }
            }
            val attendance = buildString {
                append("${detail.presentCount} / ${detail.heldCount} 回")
                detail.effectiveRatePercent?.let { append("（$it%）") }
            }
            InfoRow("出席（アプリ集計）", attendance)
            if (detail.hasAdjustments) {
                Text(
                    "手動修正を反映済み",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { showAttendanceDialog = true }) {
                Icon(Icons.Filled.EditCalendar, contentDescription = null)
                Text("  出席の詳細・修正")
            }
            HorizontalDivider()
            InfoRow("休講", "科目別には未確認")
            InfoRow("教室移動", "科目別には未確認")
            TextButton(onClick = onOpenClass) { Text("CLASSで掲示を確認") }

            if (showAttendanceDialog) {
                AttendanceDetailDialog(
                    detail = detail,
                    loadRows = loadRows,
                    setStatus = setStatus,
                    onChanged = onChanged,
                    onDismiss = { showAttendanceDialog = false },
                    confirmationRevision = confirmationRevision,
                )
            }
            return@Column
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { showSettings = false }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "授業詳細に戻る")
            }
            Text("授業の設定", style = MaterialTheme.typography.titleLarge)
        }
        Text(detail.name, style = MaterialTheme.typography.titleMedium)
        if (detail.instructor.isNotBlank()) {
            InfoRow("教員", detail.instructor)
        }
        InfoRow("通常の教室", if (detail.isOnline) "オンライン（遠隔）" else (detail.room ?: "—"))
        detail.credits?.let { InfoRow("単位", "${it}単位") }
        InfoRow("科目コード", detail.code)

        HorizontalDivider()
        Text("出席目標", style = MaterialTheme.typography.titleMedium)
        goalSettings()

        detail.letusCourseUrl?.let { url ->
            OutlinedButton(onClick = { onOpenLetus(url) }, modifier = Modifier.fillMaxWidth()) {
                Text("この授業のLETUSコースを開く")
            }
        }

        Button(
            onClick = { onOpenSyllabus(detail.code) },
            modifier = Modifier.fillMaxWidth(),
            enabled = detail.academicYear > 0,
        ) {
            Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null)
            Text("  シラバスを開く")
        }
        if (detail.academicYear <= 0) {
            Text(
                "シラバスを開くには設定画面で年度を設定してください。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AttendanceDetailDialog(
    detail: CourseDetail,
    loadRows: suspend (String) -> List<AttendanceRowUi>,
    setStatus: suspend (String, Long, Int, AttendanceStatus?) -> Unit,
    onChanged: () -> Unit,
    onDismiss: () -> Unit,
    confirmationRevision: List<org.cabetus.data.local.MobileAttendanceConfirmationEntity>,
) {
    val code = detail.code
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<AttendanceRowUi>?>(null) }
    var reloadTrigger by remember { mutableIntStateOf(0) }
    var loadedAt by remember { mutableStateOf<Long?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    androidx.compose.runtime.LaunchedEffect(code, reloadTrigger, confirmationRevision) {
        try {
            rows = loadRows(code)
            loadedAt = System.currentTimeMillis()
            loadError = null
        } catch (_: Exception) { loadError = "記録の再読込に失敗しました。" }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("出席状況", modifier = Modifier.weight(1f))
                    TextButton(onClick = { reloadTrigger++; onChanged() }) { Text("更新") }
                }
                loadedAt?.let { time ->
                    val label = java.time.format.DateTimeFormatter.ofPattern("M/d HH:mm:ss")
                        .withZone(ZoneId.of("Asia/Tokyo")).format(Instant.ofEpochMilli(time))
                    Text("アプリ内記録の読込：$label", style = MaterialTheme.typography.labelSmall)
                }
                if (detail.hasAdjustments) {
                    Text("手動修正が反映されています", style = MaterialTheme.typography.labelSmall)
                }
                detail.officialRatePercent?.let {
                    Text("CLASS の記録上の出席率: $it%", style = MaterialTheme.typography.labelSmall)
                }
                Text("CLASSからの取得ではありません（プレビューのみ）", style = MaterialTheme.typography.labelSmall)
                loadError?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        text = {
            val current = rows
            when {
                current == null -> {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                current.isEmpty() -> {
                    Text(
                        "記録がありません。授業開始通知の「出席にカウント」で記録できます。CLASSブラウザの出欠詳細は現在プレビューのみです。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(current, key = { "${it.date}-${it.period}" }) { row ->
                            AttendanceRow(
                                row = row,
                                onSelect = { newStatus ->
                                    scope.launch {
                                        setStatus(code, row.date, row.period, newStatus)
                                        reloadTrigger++
                                        onChanged()
                                    }
                                },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("閉じる") }
        },
    )
}

@Composable
private fun AttendanceRow(
    row: AttendanceRowUi,
    onSelect: (AttendanceStatus?) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = row.source != AttendanceSource.MOBILE_REGISTRATION) { menuOpen = true }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                val head = buildString {
                    row.sessionNo?.let { append("第${it}回  ") }
                    append(DATE_FMT.format(Instant.ofEpochMilli(row.date)))
                    append("  ${row.period}限")
                }
                Text(head, style = MaterialTheme.typography.bodyMedium)
                sourceNote(row.source)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                row.status.label,
                color = statusColor(row.status),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            listOf(
                AttendanceStatus.PRESENT,
                AttendanceStatus.LATE,
                AttendanceStatus.ABSENT,
                AttendanceStatus.HOLIDAY,
            ).forEach { status ->
                DropdownMenuItem(
                    text = { Text(status.label, color = statusColor(status)) },
                    onClick = {
                        menuOpen = false
                        onSelect(status)
                    },
                )
            }
            // 手動修正を解除して元（PDF/アプリ記録）に戻す
            if (row.source == AttendanceSource.MANUAL) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("修正を取り消す") },
                    onClick = {
                        menuOpen = false
                        onSelect(null)
                    },
                )
            }
        }
    }
}

private fun sourceNote(source: AttendanceSource): String? = when (source) {
    AttendanceSource.MOBILE_REGISTRATION -> "出席登録画面で確認（最優先・手動変更不可）"
    AttendanceSource.MANUAL -> "手動修正"
    AttendanceSource.LOCAL -> "アプリ記録"
    AttendanceSource.PDF -> "過去の取込記録"
    AttendanceSource.CLASS -> "CLASS取得記録"
    AttendanceSource.NONE -> null
}

@Composable
private fun statusColor(status: AttendanceStatus): Color = when (status) {
    AttendanceStatus.PRESENT -> MaterialTheme.colorScheme.primary
    AttendanceStatus.LATE -> MaterialTheme.colorScheme.tertiary
    AttendanceStatus.ABSENT -> MaterialTheme.colorScheme.error
    AttendanceStatus.HOLIDAY -> MaterialTheme.colorScheme.secondary
    AttendanceStatus.UNRECORDED -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            label,
            modifier = Modifier.padding(end = 4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
