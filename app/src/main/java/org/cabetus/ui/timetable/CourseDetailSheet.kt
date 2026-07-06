package org.cabetus.ui.timetable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun CourseDetailSheet(
    detail: CourseDetail?,
    onOpenSyllabus: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (detail == null) {
            CircularProgressIndicator()
            return@Column
        }

        Text(
            detail.name,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        if (detail.instructor.isNotBlank()) {
            InfoRow("教員", detail.instructor)
        }
        InfoRow("教室", if (detail.isOnline) "オンライン（遠隔）" else (detail.room ?: "—"))
        detail.credits?.let { InfoRow("単位", "${it}単位") }
        InfoRow("科目コード", detail.code)

        Divider()

        // 出席状況
        Text("出席状況", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        detail.attendanceRate?.let { rate ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LinearProgressIndicator(
                    progress = { (rate / 100.0).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.weight(1f),
                )
                Text("${rate.toInt()}%", fontWeight = FontWeight.Bold)
            }
        }
        val totalPresent = detail.presentCount + detail.localCount
        InfoRow("出席数", "$totalPresent 回（実施 ${detail.heldCount} 回）")
        if (detail.localCount > 0) {
            Text(
                "うちアプリ記録: ${detail.localCount} 回",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                "シラバスを開くには先に出欠PDFを取り込んで年度を設定してください。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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
