package org.cabetus.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.cabetus.data.ai.AssistantContext
import org.cabetus.data.ai.AssistantRepository
import org.cabetus.data.ai.AssistantResult
import org.cabetus.data.local.TimetableDao
import org.cabetus.data.settings.AiSettings
import org.cabetus.data.settings.AppSettings
import org.cabetus.data.settings.FetchSettings
import org.cabetus.data.settings.NotificationSettings
import org.cabetus.data.settings.SettingsRepository
import org.cabetus.data.settings.TimetableColorMode
import org.cabetus.domain.Campus
import org.cabetus.notification.AlarmScheduler
import org.cabetus.ui.theme.ThemeMode
import org.cabetus.work.FetchScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 時間割の配色設定に出す科目（重複を除いた表示用）。 */
data class TimetableCourseOption(
    val code: String,
    val name: String,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val fetchScheduler: FetchScheduler,
    private val assistantRepository: AssistantRepository,
    private val alarmScheduler: AlarmScheduler,
    private val timetableDao: TimetableDao,
) : ViewModel() {

    val settings: StateFlow<AppSettings?> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _aiTestResult = MutableStateFlow<String?>(null)
    val aiTestResult: StateFlow<String?> = _aiTestResult.asStateFlow()

    /** 年度入力の下書き。null の間は保存済みの値を表示する。 */
    private val _academicYearInput = MutableStateFlow<String?>(null)
    val academicYearInput: StateFlow<String?> = _academicYearInput.asStateFlow()

    private val _academicYearSaving = MutableStateFlow(false)
    val academicYearSaving: StateFlow<Boolean> = _academicYearSaving.asStateFlow()

    private val _academicYearError = MutableStateFlow<String?>(null)
    val academicYearError: StateFlow<String?> = _academicYearError.asStateFlow()

    /** 年度入力の許容範囲（西暦）。 */
    val academicYearRange: IntRange = ACADEMIC_YEAR_MIN..ACADEMIC_YEAR_MAX

    /**
     * 時間割に登録されている科目。同じ科目コードは 1 件にまとめる。
     * 表示順は時限順（月曜1限…）で、最初に現れた名前を使う。
     */
    val timetableCourses: StateFlow<List<TimetableCourseOption>> = timetableDao.observeAll()
        .map { cells ->
            val seen = LinkedHashSet<String>()
            cells.mapNotNull { cell ->
                if (cell.courseCode.isBlank() || !seen.add(cell.courseCode)) {
                    null
                } else {
                    TimetableCourseOption(
                        code = cell.courseCode,
                        name = cell.courseName.ifBlank { cell.courseCode },
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 手動で選んだ科目色。キーは `"<年度>:<科目コード>"`。 */
    val timetableCourseColors: StateFlow<Map<String, Int>> =
        settingsRepository.timetableCourseColors
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private val _timetableColorModeError = MutableStateFlow<String?>(null)
    val timetableColorModeError: StateFlow<String?> = _timetableColorModeError.asStateFlow()

    private val _timetableColorError = MutableStateFlow<String?>(null)
    val timetableColorError: StateFlow<String?> = _timetableColorError.asStateFlow()

    /** 配色モードを保存する。失敗しても既存の設定は変更しない。 */
    fun setTimetableColorMode(mode: TimetableColorMode) {
        viewModelScope.launch {
            _timetableColorModeError.value = null
            runCatching { settingsRepository.setTimetableColorMode(mode) }
                .onFailure {
                    _timetableColorModeError.value =
                        "配色モードを保存できませんでした。既存の設定は変更されていません。"
                }
        }
    }

    /**
     * 科目色を保存する（[argb] が null なら解除）。
     *
     * [year] は呼び出し時点で画面に表示していた年度をそのまま渡す。
     * 保存中に年度が変わっても別年度の色を書き換えないため。
     */
    fun setTimetableCourseColor(year: Int, code: String, argb: Int?) {
        if (year <= 0) {
            _timetableColorError.value = "年度を設定すると科目の色を変更できます。"
            return
        }
        viewModelScope.launch {
            _timetableColorError.value = null
            runCatching { settingsRepository.setTimetableCourseColor(year, code, argb) }
                .onFailure {
                    _timetableColorError.value =
                        "科目の色を保存できませんでした。既存の設定は変更されていません。"
                }
        }
    }

    fun clearTimetableColorErrors() {
        _timetableColorModeError.value = null
        _timetableColorError.value = null
    }

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

    fun setDiagnosticLogging(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setDiagnosticLogging(enabled) }
    }

    fun setCustomAccent(argb: Int?) {
        viewModelScope.launch { settingsRepository.setCustomAccent(argb) }
    }

    fun setCustomBackground(argb: Int?) {
        viewModelScope.launch { settingsRepository.setCustomBackground(argb) }
    }

    /** 年度入力欄の変更。数字のみ最大4桁を受け付け、エラー表示を消す。 */
    fun setAcademicYearInput(raw: String) {
        _academicYearInput.value = raw.filter { it.isDigit() }.take(4)
        _academicYearError.value = null
    }

    /**
     * 入力中の年度を保存する。2000..2100 の整数以外は保存しない。
     * 既存の設定値は setAcademicYear が該当キーだけを書き換えるため維持される。
     */
    fun saveAcademicYear() {
        val text = _academicYearInput.value?.trim().orEmpty()
        val year = text.toIntOrNull()
        if (year == null || year !in academicYearRange) {
            _academicYearError.value =
                "年度は${ACADEMIC_YEAR_MIN}〜${ACADEMIC_YEAR_MAX}の整数で入力してください。"
            return
        }
        viewModelScope.launch {
            _academicYearSaving.value = true
            _academicYearError.value = null
            try {
                settingsRepository.setAcademicYear(year)
                _academicYearInput.value = null
            } catch (_: Exception) {
                // 保存に失敗しても既存の設定値は変更しない。
                _academicYearError.value = "保存できませんでした。既存の設定値は変更されていません。"
            } finally {
                _academicYearSaving.value = false
            }
        }
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
            // 授業開始通知・出席リマインダーは同じ授業アラーム経路で登録するため、
            // いずれかが有効なら再スケジュールする。
            if (n.classStart || n.attendanceReminder || n.autoMarkAbsent) {
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

    private companion object {
        const val ACADEMIC_YEAR_MIN = 2000
        const val ACADEMIC_YEAR_MAX = 2100
    }
}
