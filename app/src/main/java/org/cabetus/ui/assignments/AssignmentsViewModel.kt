package org.cabetus.ui.assignments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.cabetus.data.local.AssignmentDao
import org.cabetus.data.local.AssignmentEntity
import org.cabetus.data.local.LifecycleStatus
import org.cabetus.data.local.SubmissionStatus
import org.cabetus.work.FetchScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SortMode(val label: String) { DEADLINE("期日順"), DETECTED("検出順") }
enum class StatusFilter(val label: String) { PENDING("未提出のみ"), ALL("すべて") }

data class AssignmentsUiState(
    val assignments: List<AssignmentEntity> = emptyList(),
    val courses: List<String> = emptyList(),
    val sortMode: SortMode = SortMode.DEADLINE,
    val statusFilter: StatusFilter = StatusFilter.PENDING,
    val selectedCourse: String? = null,
)

@HiltViewModel
class AssignmentsViewModel @Inject constructor(
    private val assignmentDao: AssignmentDao,
    private val fetchScheduler: FetchScheduler,
) : ViewModel() {

    private val sortMode = MutableStateFlow(SortMode.DEADLINE)
    private val statusFilter = MutableStateFlow(StatusFilter.PENDING)
    private val selectedCourse = MutableStateFlow<String?>(null)

    val uiState: StateFlow<AssignmentsUiState> = combine(
        assignmentDao.observeAll(),
        sortMode,
        statusFilter,
        selectedCourse,
    ) { all, sort, status, course ->
        val courses = all.map { it.courseName }.distinct().sorted()
        var list = all
        if (status == StatusFilter.PENDING) {
            list = list.filter {
                it.submissionStatus != SubmissionStatus.SUBMITTED &&
                    it.submissionStatus != SubmissionStatus.COMPLETED &&
                    it.lifecycleStatus != LifecycleStatus.PASSED &&
                    it.lifecycleStatus != LifecycleStatus.SUBMITTED
            }
        }
        if (course != null) list = list.filter { it.courseName == course }
        list = when (sort) {
            SortMode.DEADLINE -> list.sortedWith(
                compareBy(nullsLast()) { it.deadline },
            )
            SortMode.DETECTED -> list.sortedByDescending { it.firstSeenAt }
        }
        AssignmentsUiState(list, courses, sort, status, course)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AssignmentsUiState())

    fun setSort(mode: SortMode) { sortMode.value = mode }
    fun setStatusFilter(filter: StatusFilter) { statusFilter.value = filter }
    fun toggleCourse(course: String) {
        selectedCourse.value = if (selectedCourse.value == course) null else course
    }

    fun refresh() {
        fetchScheduler.fetchNow()
    }

    fun setIgnored(id: String, ignored: Boolean) {
        viewModelScope.launch { assignmentDao.setIgnored(id, ignored) }
    }
}
