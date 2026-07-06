package org.cabetus.ui.pdf

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * PDFインポート画面。時間割PDF・出欠PDFを選択→プレビュー→保存。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfImportScreen(
    onDone: () -> Unit,
    viewModel: PdfImportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val timetablePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.onTimetablePicked(it) } }

    val attendancePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.onAttendancePicked(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PDFインポート") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "CLASS で出力した「学生時間割表」と「学生出欠状況表」のPDFを取り込みます。",
                style = MaterialTheme.typography.bodyMedium,
            )

            if (state.busy) {
                CircularProgressIndicator()
            }
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            // 時間割
            SectionCard(title = "時間割PDF") {
                OutlinedButton(
                    onClick = { timetablePicker.launch(arrayOf("application/pdf")) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("時間割PDFを選択") }

                state.timetablePreview?.let { p ->
                    Text("科目数: ${p.courseCount} / コマ数: ${p.cellCount}")
                    Button(
                        onClick = { viewModel.confirmTimetable() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("この内容で保存") }
                }
                if (state.timetableSaved) {
                    Text("時間割を保存しました ✓", color = MaterialTheme.colorScheme.primary)
                }
            }

            // 出欠
            SectionCard(title = "出欠PDF") {
                OutlinedButton(
                    onClick = { attendancePicker.launch(arrayOf("application/pdf")) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("出欠PDFを選択") }

                state.attendancePreview?.let { p ->
                    Text("科目数: ${p.courseCount} / 授業回: ${p.sessionCount}")
                    Button(
                        onClick = { viewModel.confirmAttendance() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("この内容で保存") }
                }
                if (state.attendanceSaved) {
                    Text("出欠を保存しました ✓", color = MaterialTheme.colorScheme.primary)
                }
            }

            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                Text("完了")
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
