package org.cabetus.ui.assignments

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.cabetus.data.local.AssignmentDao
import org.cabetus.data.local.AssignmentEntity
import org.cabetus.data.local.LifecycleStatus
import org.cabetus.data.local.SubmissionStatus
import org.cabetus.work.FetchScheduler
import javax.inject.Inject

enum class SortMode(val label: String) { DEADLINE("期日順"), DETECTED("検出順") }
enum class StatusFilter(val label: String) { PENDING("未提出のみ"), ALL("すべて") }
enum class DueFilter(val label: String, val windowMillis: Long?) {
    ALL("すべて", null),
    H24("24時間以内", 24L * 60 * 60 * 1000),
    D3("3日以内", 3L * 24 * 60 * 60 * 1000),
    D7("7日以内", 7L * 24 * 60 * 60 * 1000),
}

data class AssignmentsUiState(
    val assignments: List<AssignmentEntity> = emptyList(),
    val courses: List<String> = emptyList(),
    val sortMode: SortMode = SortMode.DEADLINE,
    val statusFilter: StatusFilter = StatusFilter.PENDING,
    val selectedCourses: Set<String> = emptySet(),
    val dueFilter: DueFilter = DueFilter.ALL,
)

@HiltViewModel
class AssignmentsViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val assignmentDao: AssignmentDao,
    private val fetchScheduler: FetchScheduler,
) : ViewModel() {

    private val wm = WorkManager.getInstance(context)

    private val sortMode = MutableStateFlow(SortMode.DEADLINE)
    private val statusFilter = MutableStateFlow(StatusFilter.PENDING)
    private val selectedCourses = MutableStateFlow<Set<String>>(emptySet())
    private val dueFilter = MutableStateFlow(DueFilter.ALL)

    /** 課題フィルタ状態をまとめて combine する（combine のフロー数上限を避けるため）。 */
    private data class Filters(
        val sort: SortMode,
        val status: StatusFilter,
        val courses: Set<String>,
        val due: DueFilter,
    )

    private val filters = combine(
        sortMode,
        statusFilter,
        selectedCourses,
        dueFilter,
    ) { sort, status, courses, due -> Filters(sort, status, courses, due) }

    val uiState: StateFlow<AssignmentsUiState> = combine(
        assignmentDao.observeAll(),
        filters,
    ) { all, f ->
        val courses = all.map { it.courseName }.distinct().sorted()
        var list = all
        if (f.status == StatusFilter.PENDING) {
            list = list.filter {
                it.submissionStatus != SubmissionStatus.SUBMITTED &&
                    it.submissionStatus != SubmissionStatus.COMPLETED &&
                    it.lifecycleStatus != LifecycleStatus.PASSED &&
                    it.lifecycleStatus != LifecycleStatus.SUBMITTED
            }
        }
        if (f.courses.isNotEmpty()) list = list.filter { it.courseName in f.courses }
        f.due.windowMillis?.let { window ->
            val now = System.currentTimeMillis()
            list = list.filter { it.deadline != null && it.deadline <= now + window }
        }
        list = when (f.sort) {
            SortMode.DEADLINE -> list.sortedWith(compareBy(nullsLast()) { it.deadline })
            SortMode.DETECTED -> list.sortedByDescending { it.firstSeenAt }
        }
        AssignmentsUiState(list, courses, f.sort, f.status, f.courses, f.due)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AssignmentsUiState())

    /** 取得（手動/定期）が実行中かどうか。上部プログレスバー用。 */
    val isRefreshing: StateFlow<Boolean> = combine(
        wm.getWorkInfosForUniqueWorkFlow(FetchScheduler.ONESHOT_NAME),
        wm.getWorkInfosForUniqueWorkFlow(FetchScheduler.PERIODIC_NAME),
    ) { oneshot, periodic ->
        (oneshot + periodic).any { it.state == WorkInfo.State.RUNNING }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setSort(mode: SortMode) { sortMode.value = mode }
    fun setStatusFilter(filter: StatusFilter) { statusFilter.value = filter }
    fun setDueFilter(filter: DueFilter) { dueFilter.value = filter }

    fun toggleCourse(course: String) {
        selectedCourses.value = selectedCourses.value.toMutableSet().apply {
            if (!add(course)) remove(course)
        }
    }

    fun clearCourses() { selectedCourses.value = emptySet() }

    fun refresh() {
        fetchScheduler.fetchNow()
    }

    fun setIgnored(id: String, ignored: Boolean) {
        viewModelScope.launch { assignmentDao.setIgnored(id, ignored) }
    }
}
