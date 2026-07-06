package org.cabetus.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.cabetus.data.ai.AssistantContext
import org.cabetus.data.ai.AssistantRepository
import org.cabetus.data.ai.AssistantResult
import org.cabetus.data.local.AssignmentDao
import org.cabetus.data.local.AssignmentEntity
import org.cabetus.data.local.ClassCourseDao
import org.cabetus.data.local.TimetableDao
import org.cabetus.data.settings.SettingsRepository
import org.cabetus.data.weather.WeatherRepository
import org.cabetus.data.weather.WeatherSummary
import org.cabetus.domain.PeriodTimes
import org.cabetus.notification.NotificationHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

data class TodayClass(
    val period: Int,
    val name: String,
    val room: String?,
    val isOnline: Boolean,
    val start: LocalTime?,
    val end: LocalTime?,
    val isCurrent: Boolean,
)

data class HomeUiState(
    val dateLabel: String = "",
    val todayClasses: List<TodayClass> = emptyList(),
    val currentClass: TodayClass? = null,
    val nextClass: TodayClass? = null,
    val pending: List<AssignmentEntity> = emptyList(),
    val aiConfigured: Boolean = false,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val assignmentDao: AssignmentDao,
    private val timetableDao: TimetableDao,
    private val classCourseDao: ClassCourseDao,
    private val weatherRepository: WeatherRepository,
    private val assistantRepository: AssistantRepository,
    private val notificationHelper: NotificationHelper,
) : ViewModel() {

    private val dateFmt = DateTimeFormatter.ofPattern("M月d日（E）", Locale.JAPANESE)

    val uiState: StateFlow<HomeUiState> = combine(
        settingsRepository.settings,
        assignmentDao.observePending(),
        timetableDao.observeAll(),
        classCourseDao.observeAll(),
    ) { settings, pending, cells, courses ->
        val today = LocalDate.now()
        val dow = today.dayOfWeek.value // 1=Mon..7=Sun
        val nameByCode = courses.associate { it.code to it.name }
        val now = LocalTime.now()

        val todayClasses = cells.filter { it.dayOfWeek == dow }
            .sortedBy { it.period }
            .map { c ->
                val pt = PeriodTimes.of(c.period)
                val isCurrent = pt != null && now >= pt.start && now <= pt.end
                TodayClass(
                    period = c.period,
                    name = nameByCode[c.courseCode]?.takeIf { it.isNotBlank() }
                        ?: c.courseName.ifBlank { c.courseCode },
                    room = c.room,
                    isOnline = c.isOnline,
                    start = pt?.start,
                    end = pt?.end,
                    isCurrent = isCurrent,
                )
            }
        val current = todayClasses.firstOrNull { it.isCurrent }
        val next = todayClasses.firstOrNull { it.start != null && it.start > now }

        HomeUiState(
            dateLabel = today.format(dateFmt),
            todayClasses = todayClasses,
            currentClass = current,
            nextClass = next,
            pending = pending.take(10),
            aiConfigured = settings.ai.isConfigured,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState())

    private val _weather = MutableStateFlow<WeatherSummary?>(null)
    val weather: StateFlow<WeatherSummary?> = _weather.asStateFlow()

    private val _aiState = MutableStateFlow<AiCardState>(AiCardState.Idle)
    val aiState: StateFlow<AiCardState> = _aiState.asStateFlow()

    init {
        // 天気を取得
        viewModelScope.launch {
            val campus = settingsRepository.current().campus
            _weather.value = weatherRepository.getWeather(campus)
        }
        // AIサマリのキャッシュを反映
        viewModelScope.launch {
            val (date, text) = settingsRepository.dailySummaryCache.first()
            val todayKey = LocalDate.now().toString()
            if (date == todayKey && text.isNotBlank()) {
                _aiState.value = AiCardState.Loaded(text)
            }
        }
    }

    fun refreshWeather() {
        viewModelScope.launch {
            val campus = settingsRepository.current().campus
            _weather.value = weatherRepository.getWeather(campus)
        }
    }

    fun generateSummary() {
        viewModelScope.launch {
            _aiState.value = AiCardState.Loading
            val settings = settingsRepository.current()
            if (!settings.ai.isConfigured) {
                _aiState.value = AiCardState.NotConfigured
                return@launch
            }
            val state = uiState.value
            val context = AssistantContext(
                dateLabel = state.dateLabel,
                todayClasses = state.todayClasses.map {
                    "${it.period}限 ${it.name}${it.room?.let { r -> "（$r）" } ?: ""}"
                },
                pendingAssignments = state.pending.take(8).map {
                    "${it.courseName} ${it.title}（${NotificationHelper.formatDeadline(it.deadline)}）"
                },
                weather = _weather.value?.let { "${it.label} ${it.currentTemp?.toInt() ?: ""}℃" },
            )
            when (val result = assistantRepository.generate(settings.ai, context)) {
                is AssistantResult.Success -> {
                    _aiState.value = AiCardState.Loaded(result.text)
                    settingsRepository.setDailySummaryCache(LocalDate.now().toString(), result.text)
                }

                is AssistantResult.Error -> _aiState.value = AiCardState.Error(result.message)
                AssistantResult.NotConfigured -> _aiState.value = AiCardState.NotConfigured
            }
        }
    }
}

sealed interface AiCardState {
    data object Idle : AiCardState
    data object Loading : AiCardState
    data class Loaded(val text: String) : AiCardState
    data class Error(val message: String) : AiCardState
    data object NotConfigured : AiCardState
}
