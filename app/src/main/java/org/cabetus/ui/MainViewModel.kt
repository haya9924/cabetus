package org.cabetus.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.cabetus.data.settings.AppSettings
import org.cabetus.data.settings.SettingsRepository
import org.cabetus.notification.AlarmScheduler
import org.cabetus.work.FetchScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val alarmScheduler: AlarmScheduler,
    private val fetchScheduler: FetchScheduler,
) : ViewModel() {

    val settings: StateFlow<AppSettings?> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        // アプリ起動時に定期取得・アラームを再登録（端末再起動やデータ更新に追従）
        viewModelScope.launch {
            val s = settingsRepository.current()
            if (s.setupCompleted) {
                fetchScheduler.schedulePeriodic(s.fetch)
                alarmScheduler.rescheduleClassAlarms()
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
