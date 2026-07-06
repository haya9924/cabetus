package org.cabetus.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.cabetus.data.settings.AiSettings
import org.cabetus.data.settings.SettingsRepository
import org.cabetus.domain.Campus
import org.cabetus.notification.AlarmScheduler
import org.cabetus.work.FetchScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val fetchScheduler: FetchScheduler,
    private val alarmScheduler: AlarmScheduler,
) : ViewModel() {

    private val _campus = MutableStateFlow(Campus.KATSUSHIKA)
    val campus: StateFlow<Campus> = _campus.asStateFlow()

    fun setCampus(c: Campus) {
        _campus.value = c
        viewModelScope.launch { settingsRepository.setCampus(c) }
    }

    fun saveAi(baseUrl: String, apiKey: String, model: String) {
        viewModelScope.launch {
            settingsRepository.setAiSettings(AiSettings(baseUrl.trim(), apiKey.trim(), model.trim()))
        }
    }

    fun complete() {
        viewModelScope.launch {
            settingsRepository.setSetupCompleted(true)
            val settings = settingsRepository.current()
            fetchScheduler.schedulePeriodic(settings.fetch)
            fetchScheduler.fetchNow()
            alarmScheduler.rescheduleClassAlarms()
            if (settings.notifications.dailySummary) {
                alarmScheduler.scheduleDailySummary(
                    settings.notifications.dailySummaryHour,
                    settings.notifications.dailySummaryMinute,
                )
            }
        }
    }
}
