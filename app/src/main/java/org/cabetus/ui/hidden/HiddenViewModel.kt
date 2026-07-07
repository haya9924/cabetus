package org.cabetus.ui.hidden

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.cabetus.data.local.AssignmentDao
import org.cabetus.data.local.AssignmentEntity
import org.cabetus.data.local.MoodleCourseDao
import org.cabetus.data.local.MoodleCourseEntity
import org.cabetus.widget.WidgetUpdater
import javax.inject.Inject

@HiltViewModel
class HiddenViewModel @Inject constructor(
    private val assignmentDao: AssignmentDao,
    private val moodleCourseDao: MoodleCourseDao,
    private val widgetUpdater: WidgetUpdater,
) : ViewModel() {

    val hiddenAssignments: StateFlow<List<AssignmentEntity>> =
        assignmentDao.observeIgnored()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 非表示にしているコース（enabled = false）。管理画面のリスト表示用。 */
    val hiddenCourses: StateFlow<List<MoodleCourseEntity>> =
        moodleCourseDao.observeAll()
            .map { list -> list.filter { !it.enabled } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 表示中のコース（enabled = true）。「コースを追加」ダイアログの候補用。 */
    val visibleCourses: StateFlow<List<MoodleCourseEntity>> =
        moodleCourseDao.observeAll()
            .map { list -> list.filter { it.enabled } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 課題を再表示する。 */
    fun restoreAssignment(id: String) {
        viewModelScope.launch { assignmentDao.setIgnored(id, false) }
    }

    /** コースを非表示にし、ウィジェットも即時更新する。 */
    fun hideCourse(id: String) {
        viewModelScope.launch {
            moodleCourseDao.setEnabled(id, false)
            widgetUpdater.updateAll()
        }
    }

    /** コースを再表示し、ウィジェットも即時更新する。 */
    fun restoreCourse(id: String) {
        viewModelScope.launch {
            moodleCourseDao.setEnabled(id, true)
            widgetUpdater.updateAll()
        }
    }
}
