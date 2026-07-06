package org.cabetus.ui.hidden

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

    val courses: StateFlow<List<MoodleCourseEntity>> =
        moodleCourseDao.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 課題を再表示する。 */
    fun restoreAssignment(id: String) {
        viewModelScope.launch { assignmentDao.setIgnored(id, false) }
    }

    /** コースの表示/非表示を切り替え、ウィジェットも即時更新する。 */
    fun setCourseHidden(id: String, hidden: Boolean) {
        viewModelScope.launch {
            moodleCourseDao.setEnabled(id, !hidden)
            widgetUpdater.updateAll()
        }
    }
}
