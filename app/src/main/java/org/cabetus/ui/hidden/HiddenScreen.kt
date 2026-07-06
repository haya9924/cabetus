package org.cabetus.ui.hidden

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.cabetus.data.local.AssignmentEntity
import org.cabetus.data.local.MoodleCourseEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HiddenScreen(
    onBack: () -> Unit,
    viewModel: HiddenViewModel = hiltViewModel(),
) {
    val hiddenAssignments by viewModel.hiddenAssignments.collectAsStateWithLifecycle()
    val courses by viewModel.courses.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("非表示の管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0}, text = { Text("課題") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("コース") })
            }
            when (tab) {
                0 -> HiddenAssignmentsTab(hiddenAssignments, viewModel::restoreAssignment)
                else -> HiddenCoursesTab(courses, viewModel::setCourseHidden)
            }
        }
    }
}

@Composable
private fun HiddenAssignmentsTab(
    assignments: List<AssignmentEntity>,
    onRestore: (String) -> Unit,
) {
    if (assignments.isEmpty()) {
        Text(
            "非表示にした課題はありません。",
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(assignments, key = { it.id }) { a ->
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(a.title, fontWeight = FontWeight.Bold, maxLines = 2)
                        Text(
                            a.courseName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { onRestore(a.id) }) {
                        Icon(Icons.Filled.Visibility, contentDescription = "再表示")
                    }
                }
            }
        }
    }
}

@Composable
private fun HiddenCoursesTab(
    courses: List<MoodleCourseEntity>,
    onSetHidden: (String, Boolean) -> Unit,
) {
    if (courses.isEmpty()) {
        Text(
            "コースがありません。課題を取得するとここに表示されます。",
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                "非表示にしたコースは課題一覧・ウィジェット・通知から除外され、スキャンもされません。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        items(courses, key = { it.id }) { c ->
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        c.name,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                    )
                    Switch(
                        checked = c.enabled,
                        onCheckedChange = { checked -> onSetHidden(c.id, !checked) },
                    )
                }
            }
        }
    }
}
