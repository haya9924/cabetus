package org.cabetus.ui.assignments

import org.cabetus.domain.isPendingAssignment

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.cabetus.data.local.AssignmentDao
import org.cabetus.data.local.AssignmentEntity
import org.cabetus.data.local.LifecycleStatus
import org.cabetus.data.local.SubmissionStatus
import org.cabetus.data.settings.SettingsRepository
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

/**
 * 設定に記録された「最後の取得試行」。
 *
 * [lastFetchAt] が 0 のときは一度も取得していない（未取得）。
 * [lastFetchResult] は記録された文言をそのまま出す（成功/失敗を推測して付け足さない）。
 */
data class FetchInfo(
    val lastFetchAt: Long = 0L,
    val lastFetchResult: String = "",
) {
    /** まだ一度も取得を試みていない。 */
    val isNeverFetched: Boolean get() = lastFetchAt <= 0L
}

/** 期日順表示の区切り。label = null は見出しなし（検出順など）。 */
data class AssignmentSection(
    val label: String?,
    val items: List<AssignmentEntity>,
)

data class AssignmentsUiState(
    val sections: List<AssignmentSection> = emptyList(),
    val courses: List<String> = emptyList(),
    val sortMode: SortMode = SortMode.DEADLINE,
    val statusFilter: StatusFilter = StatusFilter.PENDING,
    val selectedCourses: Set<String> = emptySet(),
    val dueFilter: DueFilter = DueFilter.ALL,
    /**
     * カレンダー表示の対象（現在のステータス/期日/科目フィルタ適用後、並び替え前）の課題。
     * 一覧のセクションは期日区分で並べ替えたものなので、カレンダーはこの値を使う。
     */
    val filteredAssignments: List<AssignmentEntity> = emptyList(),
) {
    /** 全課題のフラット表示（空判定などに使用）。 */
    val assignments: List<AssignmentEntity> get() = sections.flatMap { it.items }

    /** 結果を狭めている絞り込みがあるか（全解除ボタンの表示判定）。 */
    val hasNarrowingFilter: Boolean
        get() = statusFilter != StatusFilter.ALL ||
            dueFilter != DueFilter.ALL ||
            selectedCourses.isNotEmpty()

    /** 適用中の絞り込み条件の説明（空状態で何が隠れているか示す）。 */
    val filterSummary: String
        get() = buildString {
            append("ステータス: ").append(statusFilter.label)
            append("／期日: ").append(dueFilter.label)
            append("／科目: ")
            if (selectedCourses.isEmpty()) {
                append("すべて")
            } else {
                append(selectedCourses.sorted().joinToString("、"))
            }
        }

    /** カレンダーの対象を説明する文言（何を対象にしているか明示する）。 */
    val calendarScopeLabel: String
        get() = buildString {
            append("一覧と同じ絞り込み")
            append("・ステータス: ").append(statusFilter.label)
            if (dueFilter != DueFilter.ALL) append("・期日: ").append(dueFilter.label)
            if (selectedCourses.isNotEmpty()) {
                append("・科目: ").append(selectedCourses.sorted().joinToString("、"))
            }
        }
}

@HiltViewModel
class AssignmentsViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val assignmentDao: AssignmentDao,
    private val moodleCourseDao: org.cabetus.data.local.MoodleCourseDao,
    private val fetchScheduler: FetchScheduler,
    private val settingsRepository: SettingsRepository,
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

    /**
     * 1分ごとに現在時刻を流す。期日区分（24時間以内→期限切れ等）と期日絞り込みが
     * 画面を開いたままでも追従するようにする。
     */
    private fun minuteTicker(): Flow<Long> = flow {
        while (true) {
            val now = System.currentTimeMillis()
            emit(now)
            delay((60_000L - now % 60_000L).coerceIn(1L, 60_000L))
        }
    }

    val uiState: StateFlow<AssignmentsUiState> = combine(
        assignmentDao.observeAll(),
        filters,
        minuteTicker(),
    ) { all, f, now ->
        val courses = all.map { it.courseName }.distinct().sorted()
        var list = all
        if (f.status == StatusFilter.PENDING) {
            // 未提出＝提出済みでないもの。期限切れ（PASSED）も未提出として残す
            // （時間経過で突然消えないようにする）。
            list = list.filter { it.isPendingAssignment() }
        }
        if (f.courses.isNotEmpty()) list = list.filter { it.courseName in f.courses }
        f.due.windowMillis?.let { window ->
            list = list.filter { it.deadline != null && it.deadline <= now + window }
        }
        val filtered = list.sortedWith(compareBy(nullsLast()) { it.deadline })
        list = when (f.sort) {
            SortMode.DEADLINE -> filtered
            SortMode.DETECTED -> list.sortedByDescending { it.firstSeenAt }
        }
        val sections = when (f.sort) {
            // 期日順は「期限切れ/24時間以内/…」でグルーピングして見出しを付ける。
            SortMode.DEADLINE -> {
                list.groupBy { sectionLabel(it.deadline, now) }
                    .map { (label, items) -> AssignmentSection(label, items) }
            }
            // 検出順は見出しなしの単一セクション。
            SortMode.DETECTED ->
                if (list.isEmpty()) emptyList() else listOf(AssignmentSection(null, list))
        }
        AssignmentsUiState(
            sections = sections,
            courses = courses,
            sortMode = f.sort,
            statusFilter = f.status,
            selectedCourses = f.courses,
            dueFilter = f.due,
            filteredAssignments = filtered,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AssignmentsUiState())

    /**
     * 最後の取得試行（設定の lastFetchAt / lastFetchResult）。
     * 小型表示用に購読するだけで、成功/失敗の判定はしない。
     */
    val fetchInfo: StateFlow<FetchInfo> = settingsRepository.settings
        .map { FetchInfo(lastFetchAt = it.lastFetchAt, lastFetchResult = it.lastFetchResult) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FetchInfo())

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

    /** 絞り込みをすべて解除する（ステータス=すべて／期日=すべて／科目=すべて）。 */
    fun clearFilters() {
        statusFilter.value = StatusFilter.ALL
        dueFilter.value = DueFilter.ALL
        selectedCourses.value = emptySet()
    }

    fun refresh() {
        fetchScheduler.fetchNow()
    }

    suspend fun hideCourse(courseId: String) {
        require(moodleCourseDao.getAll().any { it.id == courseId }) { "コースが見つかりません" }
        moodleCourseDao.setEnabled(courseId, false)
    }

    fun setIgnored(id: String, ignored: Boolean) {
        viewModelScope.launch { assignmentDao.setIgnored(id, ignored) }
    }

    companion object {
        private const val DAY = 24L * 60 * 60 * 1000

        /** 期日を「期限切れ/24時間以内/3日以内/7日以内/それ以降/期限なし」のいずれかに分類する。 */
        fun sectionLabel(deadline: Long?, now: Long): String = when {
            deadline == null -> "期限なし"
            deadline < now -> "期限切れ"
            deadline <= now + DAY -> "24時間以内"
            deadline <= now + 3 * DAY -> "3日以内"
            deadline <= now + 7 * DAY -> "7日以内"
            else -> "それ以降"
        }
    }
}
