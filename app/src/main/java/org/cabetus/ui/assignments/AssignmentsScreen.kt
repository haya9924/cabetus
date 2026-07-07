package org.cabetus.ui.assignments

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
            // 並び替え・絞り込みの 2 ボタン
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SortButton(
                    current = state.sortMode,
                    onSelect = viewModel::setSort,
                )
                FilterButton(
                    state = state,
                    onStatus = viewModel::setStatusFilter,
                    onDue = viewModel::setDueFilter,
                    onToggleCourse = viewModel::toggleCourse,
                    onClearCourses = viewModel::clearCourses,
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
                    state.sections.forEach { section ->
                        if (section.label != null) {
                            item(key = "header-${section.label}") {
                                SectionHeader(section.label)
                            }
                        }
                        items(section.items, key = { it.id }) { a ->
                            AssignmentCard(
                                a = a,
                                onOpen = {
                                    if (a.url.isNotBlank()) {
                                        runCatching {
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW, a.url.toUri()),
                                            )
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
}

@Composable
private fun SectionHeader(label: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HorizontalDivider()
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun SortButton(
    current: SortMode,
    onSelect: (SortMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { expanded = true },
            label = { Text("並び替え: ${current.label}") },
            leadingIcon = {
                Icon(
                    Icons.AutoMirrored.Filled.Sort,
                    contentDescription = null,
                    Modifier.size(AssistChipDefaults.IconSize),
                )
            },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SortMode.entries.forEach { mode ->
                DropdownMenuItem(
                    leadingIcon = {
                        RadioButton(selected = current == mode, onClick = null)
                    },
                    text = { Text(mode.label) },
                    onClick = {
                        onSelect(mode)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun FilterButton(
    state: AssignmentsUiState,
    onStatus: (StatusFilter) -> Unit,
    onDue: (DueFilter) -> Unit,
    onToggleCourse: (String) -> Unit,
    onClearCourses: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val activeCount = (if (state.statusFilter != StatusFilter.PENDING) 1 else 0) +
        (if (state.dueFilter != DueFilter.ALL) 1 else 0) +
        state.selectedCourses.size
    val label = if (activeCount == 0) "絞り込み" else "絞り込み ($activeCount)"

    Box {
        AssistChip(
            onClick = { expanded = true },
            label = { Text(label) },
            leadingIcon = {
                Icon(
                    Icons.Filled.FilterList,
                    contentDescription = null,
                    Modifier.size(AssistChipDefaults.IconSize),
                )
            },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MenuSectionLabel("ステータス")
            StatusFilter.entries.forEach { f ->
                DropdownMenuItem(
                    leadingIcon = { RadioButton(selected = state.statusFilter == f, onClick = null) },
                    text = { Text(f.label) },
                    onClick = { onStatus(f) },
                )
            }
            HorizontalDivider()
            MenuSectionLabel("期日")
            DueFilter.entries.forEach { f ->
                DropdownMenuItem(
                    leadingIcon = { RadioButton(selected = state.dueFilter == f, onClick = null) },
                    text = { Text(f.label) },
                    onClick = { onDue(f) },
                )
            }
            if (state.courses.isNotEmpty()) {
                HorizontalDivider()
                MenuSectionLabel("科目")
                DropdownMenuItem(
                    leadingIcon = {
                        Checkbox(checked = state.selectedCourses.isEmpty(), onCheckedChange = null)
                    },
                    text = { Text("すべて") },
                    onClick = { onClearCourses() },
                )
                state.courses.forEach { course ->
                    DropdownMenuItem(
                        leadingIcon = {
                            Checkbox(checked = course in state.selectedCourses, onCheckedChange = null)
                        },
                        text = { Text(course, maxLines = 1) },
                        onClick = { onToggleCourse(course) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MenuSectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
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
