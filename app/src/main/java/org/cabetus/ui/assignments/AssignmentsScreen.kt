package org.cabetus.ui.assignments

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.cabetus.data.local.AssignmentEntity
import org.cabetus.data.local.LifecycleStatus
import org.cabetus.data.local.SubmissionStatus
import org.cabetus.notification.NotificationHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssignmentsScreen(
    onOpenHidden: () -> Unit = {},
    viewModel: AssignmentsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("課題") },
                actions = {
                    IconButton(onClick = onOpenHidden) {
                        Icon(Icons.Filled.VisibilityOff, contentDescription = "非表示にしたもの")
                    }
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "取得")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            // 更新中の一般的なプログレスアニメーション
            if (isRefreshing) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            // ソート・ステータス
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SortMode.entries.forEach { mode ->
                    FilterChip(
                        selected = state.sortMode == mode,
                        onClick = { viewModel.setSort(mode) },
                        label = { Text(mode.label) },
                    )
                }
                StatusFilter.entries.forEach { f ->
                    FilterChip(
                        selected = state.statusFilter == f,
                        onClick = { viewModel.setStatusFilter(f) },
                        label = { Text(f.label) },
                    )
                }
            }
            // 期日フィルタ
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DueFilter.entries.forEach { f ->
                    FilterChip(
                        selected = state.dueFilter == f,
                        onClick = { viewModel.setDueFilter(f) },
                        label = { Text(f.label) },
                    )
                }
            }
            // 科目絞り込み（複数選択プルダウン）
            if (state.courses.isNotEmpty()) {
                CourseFilterDropdown(
                    courses = state.courses,
                    selected = state.selectedCourses,
                    onToggle = viewModel::toggleCourse,
                    onClear = viewModel::clearCourses,
                )
            }

            if (state.assignments.isEmpty()) {
                Text(
                    "課題がありません。右上の更新ボタンで取得できます。",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.assignments, key = { it.id }) { a ->
                        AssignmentCard(
                            a = a,
                            onOpen = {
                                if (a.url.isNotBlank()) {
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, a.url.toUri()))
                                    }
                                }
                            },
                            onIgnore = { viewModel.setIgnored(a.id, true) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CourseFilterDropdown(
    courses: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onClear: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = if (selected.isEmpty()) "すべての科目" else "${selected.size}科目を選択中"
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text("科目で絞り込み") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                leadingIcon = { Checkbox(checked = selected.isEmpty(), onCheckedChange = null) },
                text = { Text("すべて") },
                onClick = { onClear() },
            )
            courses.forEach { course ->
                DropdownMenuItem(
                    leadingIcon = {
                        Checkbox(checked = course in selected, onCheckedChange = null)
                    },
                    text = { Text(course, maxLines = 1) },
                    onClick = { onToggle(course) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssignmentCard(a: AssignmentEntity, onOpen: () -> Unit, onIgnore: () -> Unit) {
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    a.title,
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                )
                IconButton(onClick = onIgnore) {
                    Icon(Icons.Filled.VisibilityOff, contentDescription = "非表示")
                }
            }
            Text(a.courseName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(a)
                Text(
                    "期限: ${NotificationHelper.formatDeadline(a.deadline)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(a: AssignmentEntity) {
    val (text, _) = when {
        a.submissionStatus == SubmissionStatus.SUBMITTED || a.submissionStatus == SubmissionStatus.COMPLETED ->
            "提出済み" to MaterialTheme.colorScheme.primary
        a.lifecycleStatus == LifecycleStatus.PASSED -> "期限切れ" to MaterialTheme.colorScheme.error
        a.lifecycleStatus == LifecycleStatus.BEFORE_START -> "開始前" to MaterialTheme.colorScheme.tertiary
        else -> "未提出" to MaterialTheme.colorScheme.secondary
    }
    SuggestionChip(onClick = {}, label = { Text(text, style = MaterialTheme.typography.labelSmall) })
}
