package org.cabetus.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.cabetus.data.local.TimetableDao
import org.cabetus.data.settings.AppSettings
import org.cabetus.data.settings.SettingsRepository
import org.cabetus.notification.AlarmScheduler
import org.cabetus.ui.browser.ClassAttendanceBackgroundController
import org.cabetus.work.FetchScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val alarmScheduler: AlarmScheduler,
    private val fetchScheduler: FetchScheduler,
    private val timetableDao: TimetableDao,
    private val classBrowser: ClassAttendanceBackgroundController,
) : ViewModel() {

    private var attemptedBrowserSession: Long? = null

    /** Called only while MainActivity is resumed, including just after onboarding. */
    suspend fun bootstrapTimetableWhileResumed() {
        combine(settings, timetableDao.observeAll(), classBrowser.browserSession) { current, cells, session ->
            session.takeIf { current?.setupCompleted == true && cells.isEmpty() }
        }.distinctUntilChanged().collectLatest { session ->
            if (session == null || session == attemptedBrowserSession) return@collectLatest
            // Returning from the CLASS browser gives an expired session another chance after login.
            if (attemptedBrowserSession != null && !classBrowser.timetableStatus.value.authRequired) return@collectLatest
            while (!classBrowser.tryStartInitialTimetable()) delay(500)
            attemptedBrowserSession = session
        }
    }

    val settings: StateFlow<AppSettings?> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        // アプリ起動時に定期取得・アラームを再登録（端末再起動やデータ更新に追従）
        viewModelScope.launch {
            val s = settingsRepository.current()
            if (s.setupCompleted) {
                fetchScheduler.schedulePeriodic(s.fetch)
                alarmScheduler.rescheduleClassAlarms()
                alarmScheduler.scheduleNextClassWidgetUpdate()
                if (s.notifications.dailySummary) {
                    alarmScheduler.scheduleDailySummary(
                        s.notifications.dailySummaryHour,
                        s.notifications.dailySummaryMinute,
                    )
                }
            }
        }
    }
}
