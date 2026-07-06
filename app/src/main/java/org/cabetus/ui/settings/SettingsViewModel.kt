package org.cabetus.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.cabetus.data.ai.AssistantContext
import org.cabetus.data.ai.AssistantRepository
import org.cabetus.data.ai.AssistantResult
import org.cabetus.data.settings.AiSettings
import org.cabetus.data.settings.AppSettings
import org.cabetus.data.settings.FetchSettings
import org.cabetus.data.settings.NotificationSettings
import org.cabetus.data.settings.SettingsRepository
import org.cabetus.domain.Campus
import org.cabetus.notification.AlarmScheduler
import org.cabetus.ui.theme.ThemeMode
import org.cabetus.work.FetchScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val fetchScheduler: FetchScheduler,
    private val assistantRepository: AssistantRepository,
    private val alarmScheduler: AlarmScheduler,
) : ViewModel() {

    val settings: StateFlow<AppSettings?> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _aiTestResult = MutableStateFlow<String?>(null)
    val aiTestResult: StateFlow<String?> = _aiTestResult.asStateFlow()

    fun setFetch(f: FetchSettings) {
        viewModelScope.launch {
            settingsRepository.setFetchSettings(f)
            fetchScheduler.schedulePeriodic(f)
        }
    }

    fun fetchNow() = fetchScheduler.fetchNow()

    fun setCampus(campus: Campus) {
        viewModelScope.launch { settingsRepository.setCampus(campus) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setDynamicColor(enabled) }
    }

    fun setAi(ai: AiSettings) {
        viewModelScope.launch { settingsRepository.setAiSettings(ai) }
    }

    fun setNotifications(n: NotificationSettings) {
        viewModelScope.launch {
            settingsRepository.setNotificationSettings(n)
            if (n.dailySummary) {
                alarmScheduler.scheduleDailySummary(n.dailySummaryHour, n.dailySummaryMinute)
            } else {
                alarmScheduler.cancelDailySummary()
            }
            if (n.classStart) {
                alarmScheduler.rescheduleClassAlarms()
            }
        }
    }

    fun testAi(ai: AiSettings) {
        viewModelScope.launch {
            _aiTestResult.value = "送信中…"
            val result = assistantRepository.generate(
                ai,
                AssistantContext(
                    dateLabel = "テスト",
                    todayClasses = emptyList(),
                    pendingAssignments = emptyList(),
                    weather = null,
                ),
            )
            _aiTestResult.value = when (result) {
                is AssistantResult.Success -> "成功: ${result.text.take(60)}"
                is AssistantResult.Error -> "失敗: ${result.message}"
                AssistantResult.NotConfigured -> "未設定です"
            }
        }
    }

    fun clearAiTest() { _aiTestResult.value = null }
}
