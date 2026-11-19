package org.cabetus.ui.assignments

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.cabetus.data.local.AssignmentEntity
import org.cabetus.data.local.LifecycleStatus
import org.cabetus.data.local.SubmissionStatus
import org.cabetus.notification.NotificationHelper
import org.cabetus.ui.browser.BrowserIntents

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssignmentsScreen(
    onOpenHidden: () -> Unit = {},
    viewModel: AssignmentsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val fetchInfo by viewModel.fetchInfo.collectAsStateWithLifecycle()
    var showCalendar by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // 長押しで「何を非表示にするか」を選ぶ対象の課題。
    var hideTarget by remember { mutableStateOf<AssignmentEntity?>(null) }
    // 上部の非表示管理ボタンの長押しヘルプ。
    var showHiddenHelp by remember { mutableStateOf(false) }

    hideTarget?.let { target ->
        HideTargetDialog(
            assignment = target,
            onDismiss = { hideTarget = null },
            onHideOnlyThis = {
                viewModel.setIgnored(target.id, true)
                hideTarget = null
            },
            onHideWholeCourse = {
                scope.launch {
                    val result = runCatching { viewModel.hideCourse(target.courseId) }
                    if (result.isSuccess) {
                        hideTarget = null
                    } else {
                        val message = result.exceptionOrNull()?.message
                            ?: "コースを非表示にできませんでした"
                        snackbarHostState.showSnackbar(message)
                    }
                }
            },
        )
    }

    if (showHiddenHelp) {
        AlertDialog(
            onDismissRequest = { showHiddenHelp = false },
            icon = { Icon(Icons.Filled.VisibilityOff, contentDescription = null) },
            title = { Text("非表示の管理") },
            text = {
                Text(
                    "このボタンを押すと、非表示にした課題とコースの一覧を開きます。" +
                        "課題カードの非表示ボタンを長押しすると、その課題だけ、または" +
                        "同じコースの課題をまとめて非表示にできます。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showHiddenHelp = false
                        onOpenHidden()
                    },
                ) { Text("非表示の管理") }
            },
            dismissButton = {
                TextButton(onClick = { showHiddenHelp = false }) { Text("キャンセル") }
            },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("課題") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
                actions = {
                    IconButton(onClick = { showCalendar = !showCalendar }) {
                        Icon(Icons.Filled.CalendarMonth,
                            contentDescription = if (showCalendar) "カレンダーを閉じる" else "カレンダーを表示",
                            tint = MaterialTheme.colorScheme.primary)
                    }
                    LongPressActionButton(
                        label = "非表示にしたもの",
                        longClickLabel = "非表示の管理について",
                        onClick = onOpenHidden,
                        onLongClick = { showHiddenHelp = true },
                    ) {
                        Icon(Icons.Filled.VisibilityOff, contentDescription = null)
                    }
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "取得")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainer),
        ) {
            // 更新中の一般的なプログレスアニメーション
            if (isRefreshing) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "updated") {
                    androidx.compose.material3.Surface(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text(if (fetchInfo.isNeverFetched) "まだ取得していません" else {
                                val time = java.time.Instant.ofEpochMilli(fetchInfo.lastFetchAt)
                                    .atZone(java.time.ZoneId.of("Asia/Tokyo"))
                                    .format(java.time.format.DateTimeFormatter.ofPattern("M/d HH:mm"))
                                "最終更新の試行：$time"
                            }, style = MaterialTheme.typography.labelMedium)
                            if (fetchInfo.lastFetchResult.isNotBlank()) Text(fetchInfo.lastFetchResult,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (showCalendar) item(key = "calendar") {
                    AssignmentCalendarPanel(state)
                }
                item(key = "controls") {
                    // 並び替え・絞り込みの 2 ボタン
                    Row(
                        Modifier.fillMaxWidth(),
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
                }

                if (state.assignments.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            "この条件に合う課題はありません。\n${state.filterSummary}\n非表示の課題・コースは非表示管理から確認できます。",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp, horizontal = 8.dp),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = viewModel::clearFilters) { Text("絞り込みをすべて解除") }
                    }
                } else {
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
                                        BrowserIntents.open(context, a.url)
                                    }
                                },
                                onIgnore = { viewModel.setIgnored(a.id, true) },
                                onCourseHide = { hideTarget = a },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 長押しで対象選択を開く非表示ダイアログ。
 *
 * 「この課題のみ」は課題の ignored フラグだけを立てる。コース単位の非表示は
 * 親（[AssignmentsScreen]）が suspend の [AssignmentsViewModel.hideCourse] を実行する。
 */
@Composable
private fun HideTargetDialog(
    assignment: AssignmentEntity,
    onDismiss: () -> Unit,
    onHideOnlyThis: () -> Unit,
    onHideWholeCourse: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.VisibilityOff, contentDescription = null) },
        title = { Text("非表示にする対象") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    assignment.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 3,
                )
                Text(
                    "この課題だけを非表示にするか、同じコース「${assignment.courseName}」の" +
                        "課題をまとめて非表示にするかを選んでください。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                androidx.compose.material3.OutlinedButton(
                    onClick = onHideOnlyThis,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("この課題だけを非表示") }
                androidx.compose.material3.FilledTonalButton(
                    onClick = onHideWholeCourse,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("コース全体を非表示") }
                Text("非表示の管理から元に戻せます。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        },
    )
}

/**
 * 48dp のタップ領域を持つアイコンボタン。短押しと長押しで別の操作を行う。
 * 長押し中は親カードのクリック（課題を開く）は発火しない。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LongPressActionButton(
    label: String,
    longClickLabel: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .combinedClickable(
                onClickLabel = label,
                onLongClickLabel = longClickLabel,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .semantics {
                role = Role.Button
                contentDescription = label
            },
        contentAlignment = Alignment.Center,
    ) {
        icon()
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
private fun AssignmentCard(
    a: AssignmentEntity,
    onOpen: () -> Unit,
    onIgnore: () -> Unit,
    onCourseHide: () -> Unit,
) {
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .padding(top = 8.dp, end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    a.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                )
                Text(
                    a.courseName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusBadge(a)
                    Text(
                        "期限: ${NotificationHelper.formatDeadline(a.deadline)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            LongPressActionButton(
                label = "「${a.title}」を非表示にする",
                longClickLabel = "非表示にする対象を選ぶ",
                onClick = onIgnore,
                onLongClick = onCourseHide,
            ) {
                Icon(Icons.Filled.VisibilityOff, contentDescription = null)
            }
        }
    }
}

@Composable
private fun StatusBadge(a: AssignmentEntity) {
    val (text, container, content) = when {
        a.submissionStatus == SubmissionStatus.SUBMITTED || a.submissionStatus == SubmissionStatus.COMPLETED ->
            Triple(
                "提出済み",
                MaterialTheme.colorScheme.primaryContainer,
                MaterialTheme.colorScheme.onPrimaryContainer,
            )
        a.lifecycleStatus == LifecycleStatus.PASSED ->
            Triple(
                "期限切れ",
                MaterialTheme.colorScheme.errorContainer,
                MaterialTheme.colorScheme.onErrorContainer,
            )
        a.lifecycleStatus == LifecycleStatus.BEFORE_START ->
            Triple(
                "開始前",
                MaterialTheme.colorScheme.tertiaryContainer,
                MaterialTheme.colorScheme.onTertiaryContainer,
            )
        else -> Triple(
            "未提出",
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = container,
        contentColor = content,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
