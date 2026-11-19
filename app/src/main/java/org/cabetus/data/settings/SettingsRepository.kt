package org.cabetus.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import org.cabetus.domain.Campus
import org.cabetus.domain.LetusClassLinkResolver
import org.cabetus.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** 取得頻度。 */
enum class FetchFrequency { HOURLY, DAILY }

data class FetchSettings(
    val frequency: FetchFrequency = FetchFrequency.DAILY,
    /** DAILY のときの取得時刻（時, 0-23）。 */
    val dailyHour: Int = 7,
    val allowMobileData: Boolean = false,
    /** モバイルデータ不許可で連続スキップした回数がこれ以上になったら強制取得。 */
    val forceFetchAfterSkips: Int = 3,
)

/** AIコメントの口調プリセット。 */
enum class AiTone(val label: String, val instruction: String) {
    DEFAULT("標準", ""),
    POLITE("丁寧", "常に丁寧語（です・ます調）で、落ち着いた敬意ある口調で話してください。"),
    FRIENDLY("フレンドリー", "友達のようにくだけたタメ口で、絵文字も交えつつ明るく話してください。"),
    KANSAI("関西弁", "親しみやすい関西弁で話してください。"),
    TSUNDERE("ツンデレ", "ツンデレ口調で話してください。素直じゃないけど本当は応援している、という態度で。"),
}

data class AiSettings(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val tone: AiTone = AiTone.DEFAULT,
    val customInstruction: String = "",
) {
    val isConfigured: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank()
}

data class NotificationSettings(
    val dailySummary: Boolean = true,
    val dailySummaryHour: Int = 7,
    val dailySummaryMinute: Int = 30,
    val classStart: Boolean = true,
    val deadline: Boolean = true,
    val newAssignment: Boolean = true,
    /** 既存課題の締切・内容が変わったときのお知らせ。 */
    val letusChange: Boolean = true,
    val fetchFailure: Boolean = true,
    /** 出席チェック忘れのリマインダー。 */
    val attendanceReminder: Boolean = true,
    /** 授業終了の何分前にリマインドするか。 */
    val attendanceReminderMinutes: Int = 10,
    /** 授業終了時点で未登録なら自動的に欠席として記録する。 */
    val autoMarkAbsent: Boolean = true,
)

data class AppSettings(
    val setupCompleted: Boolean = false,
    val campus: Campus = Campus.KATSUSHIKA,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    /** 時間割の科目色の決め方。 */
    val timetableColorMode: TimetableColorMode = TimetableColorMode.ATTENDANCE_RATE,
    /** カスタムアクセント色（ARGB Int）。null=デフォルト。 */
    val customAccent: Int? = null,
    /** カスタム背景色（ARGB Int）。null=デフォルト。 */
    val customBackground: Int? = null,
    val loggedIn: Boolean = false,
    val fetch: FetchSettings = FetchSettings(),
    val ai: AiSettings = AiSettings(),
    val notifications: NotificationSettings = NotificationSettings(),
    val skipCounter: Int = 0,
    val lastFetchResult: String = "",
    val lastFetchAt: Long = 0L,
    val academicYear: Int = 0,
    /** 詳細な診断ログを出力するか（既定は無効）。 */
    val diagnosticLogging: Boolean = false,
)

@Singleton
class SettingsRepository @Inject constructor(
    private val context: Context,
) {
    private object Keys {
        val SETUP_COMPLETED = booleanPreferencesKey("setup_completed")
        val CAMPUS = stringPreferencesKey("campus")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val TIMETABLE_COLOR_MODE = stringPreferencesKey("timetable_color_mode")
        val CUSTOM_ACCENT = intPreferencesKey("custom_accent")
        val CUSTOM_BACKGROUND = intPreferencesKey("custom_background")
        val LOGGED_IN = booleanPreferencesKey("logged_in")

        val FETCH_FREQ = stringPreferencesKey("fetch_freq")
        val FETCH_DAILY_HOUR = intPreferencesKey("fetch_daily_hour")
        val FETCH_MOBILE = booleanPreferencesKey("fetch_mobile")
        val FETCH_FORCE_SKIPS = intPreferencesKey("fetch_force_skips")
        val SKIP_COUNTER = intPreferencesKey("skip_counter")

        val AI_BASE_URL = stringPreferencesKey("ai_base_url")
        val AI_API_KEY = stringPreferencesKey("ai_api_key")
        val AI_MODEL = stringPreferencesKey("ai_model")
        val AI_TONE = stringPreferencesKey("ai_tone")
        val AI_CUSTOM_INSTRUCTION = stringPreferencesKey("ai_custom_instruction")

        val N_DAILY = booleanPreferencesKey("n_daily")
        val N_DAILY_HOUR = intPreferencesKey("n_daily_hour")
        val N_DAILY_MIN = intPreferencesKey("n_daily_min")
        val N_CLASS_START = booleanPreferencesKey("n_class_start")
        val N_DEADLINE = booleanPreferencesKey("n_deadline")
        val N_NEW = booleanPreferencesKey("n_new")
        val N_LETUS_CHANGE = booleanPreferencesKey("n_letus_change")
        val N_FAIL = booleanPreferencesKey("n_fail")
        val N_ATTEND = booleanPreferencesKey("n_attend")
        val N_ATTEND_MIN = intPreferencesKey("n_attend_min")
        val N_AUTO_ABSENT = booleanPreferencesKey("n_auto_absent")

        val LAST_RESULT = stringPreferencesKey("last_result")
        val LAST_AT = longPreferencesKey("last_at")
        val ACADEMIC_YEAR = intPreferencesKey("academic_year")
        val DIAGNOSTIC_LOGGING = booleanPreferencesKey("diagnostic_logging")

        val AI_SUMMARY_CACHE = stringPreferencesKey("ai_summary_cache")
        val AI_SUMMARY_DATE = stringPreferencesKey("ai_summary_date")

        val ATTENDANCE_GOALS = stringPreferencesKey("attendance_goals")
    }

    private val classMappingsKey = stringPreferencesKey("class_attendance_mappings")
    val classAttendanceMappings: Flow<Map<String, String>> = context.dataStore.data.map { p ->
        runCatching { org.json.JSONObject(p[classMappingsKey] ?: "{}").let { obj ->
            obj.keys().asSequence().associateWith { obj.getString(it) }
        } }.getOrDefault(emptyMap())
    }

    private val classLetusMappingsKey = stringPreferencesKey("class_letus_mappings")

    /**
     * CLASS の科目（年度・ローカル科目コード）から LETUS コース URL への明示マッピング。
     * キーは `"<年度>:<ローカル科目コード>"`、値は正規化済みの LETUS コース URL。
     * 保存値が壊れている場合は空マップを返す（画面を壊さない）。
     */
    val classLetusMappings: Flow<Map<String, String>> = context.dataStore.data.map { p ->
        runCatching { org.json.JSONObject(p[classLetusMappingsKey] ?: "{}").let { obj ->
            obj.keys().asSequence().associateWith { obj.getString(it) }
        } }.getOrDefault(emptyMap())
    }

    /**
     * LETUS コースの明示マッピングを保存する。[year] は 2000..2100、
     * [localCourseCode] は空でないこと。
     *
     * [url] が null ならそのキーだけを削除する。それ以外は LETUS のコースページとして
     * 検証できた場合だけ、正規化したコース URL を保存する。
     * 既存の他のマッピングは変更しない。
     */
    suspend fun saveClassLetusMapping(year: Int, localCourseCode: String, url: String?) {
        require(year in 2000..2100) { "year out of range" }
        require(localCourseCode.isNotBlank()) { "localCourseCode must not be blank" }
        val canonicalUrl = url?.let {
            val id = LetusClassLinkResolver.courseViewId(it)
            require(id != null) { "invalid LETUS course URL" }
            LetusClassLinkResolver.courseUrl(id)
        }
        edit { p ->
            val obj = org.json.JSONObject(p[classLetusMappingsKey] ?: "{}")
            val key = "$year:$localCourseCode"
            if (canonicalUrl == null) {
                obj.remove(key)
            } else {
                obj.put(key, canonicalUrl)
            }
            p[classLetusMappingsKey] = obj.toString()
        }
    }

    /** ユーザーが確認した科目対応だけを年度別に保存する。既存対応の上書きは禁止。 */
    suspend fun saveClassAttendanceMapping(year: Int, official: String, local: String) = edit { p ->
        require(p[Keys.ACADEMIC_YEAR] == year && year in 2000..2100)
        require(Regex("[A-Z0-9]{7}").matches(official) && local.startsWith("CLASSWEB-"))
        val obj = org.json.JSONObject(p[classMappingsKey] ?: "{}")
        val key = "$year:$official"
        require(!obj.has(key) || obj.getString(key) == local)
        require(obj.keys().asSequence().none { it != key && it.startsWith("$year:") && obj.getString(it) == local })
        obj.put(key, local)
        p[classMappingsKey] = obj.toString()
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p -> p.toAppSettings() }

    private fun Preferences.toAppSettings() = AppSettings(
        setupCompleted = this[Keys.SETUP_COMPLETED] ?: false,
        campus = Campus.fromNameOrDefault(this[Keys.CAMPUS]),
        themeMode = runCatching { ThemeMode.valueOf(this[Keys.THEME_MODE] ?: "SYSTEM") }
            .getOrDefault(ThemeMode.SYSTEM),
        dynamicColor = this[Keys.DYNAMIC_COLOR] ?: true,
        timetableColorMode = runCatching {
            TimetableColorMode.valueOf(this[Keys.TIMETABLE_COLOR_MODE] ?: "ATTENDANCE_RATE")
        }.getOrDefault(TimetableColorMode.ATTENDANCE_RATE),
        customAccent = this[Keys.CUSTOM_ACCENT],
        customBackground = this[Keys.CUSTOM_BACKGROUND],
        loggedIn = this[Keys.LOGGED_IN] ?: false,
        fetch = FetchSettings(
            frequency = runCatching {
                FetchFrequency.valueOf(this[Keys.FETCH_FREQ] ?: "DAILY")
            }.getOrDefault(FetchFrequency.DAILY),
            dailyHour = this[Keys.FETCH_DAILY_HOUR] ?: 7,
            allowMobileData = this[Keys.FETCH_MOBILE] ?: false,
            forceFetchAfterSkips = this[Keys.FETCH_FORCE_SKIPS] ?: 3,
        ),
        ai = AiSettings(
            baseUrl = this[Keys.AI_BASE_URL] ?: "",
            apiKey = this[Keys.AI_API_KEY] ?: "",
            model = this[Keys.AI_MODEL] ?: "",
            tone = runCatching { AiTone.valueOf(this[Keys.AI_TONE] ?: "DEFAULT") }
                .getOrDefault(AiTone.DEFAULT),
            customInstruction = this[Keys.AI_CUSTOM_INSTRUCTION] ?: "",
        ),
        notifications = NotificationSettings(
            dailySummary = this[Keys.N_DAILY] ?: true,
            dailySummaryHour = this[Keys.N_DAILY_HOUR] ?: 7,
            dailySummaryMinute = this[Keys.N_DAILY_MIN] ?: 30,
            classStart = this[Keys.N_CLASS_START] ?: true,
            deadline = this[Keys.N_DEADLINE] ?: true,
            newAssignment = this[Keys.N_NEW] ?: true,
            letusChange = this[Keys.N_LETUS_CHANGE] ?: true,
            fetchFailure = this[Keys.N_FAIL] ?: true,
            attendanceReminder = this[Keys.N_ATTEND] ?: true,
            attendanceReminderMinutes = this[Keys.N_ATTEND_MIN] ?: 10,
            autoMarkAbsent = this[Keys.N_AUTO_ABSENT] ?: true,
        ),
        skipCounter = this[Keys.SKIP_COUNTER] ?: 0,
        lastFetchResult = this[Keys.LAST_RESULT] ?: "",
        lastFetchAt = this[Keys.LAST_AT] ?: 0L,
        academicYear = this[Keys.ACADEMIC_YEAR] ?: 0,
        diagnosticLogging = this[Keys.DIAGNOSTIC_LOGGING] ?: false,
    )

    suspend fun current(): AppSettings = settings.first()

    suspend fun setSetupCompleted(value: Boolean) = edit { it[Keys.SETUP_COMPLETED] = value }
    suspend fun setCampus(campus: Campus) = edit { it[Keys.CAMPUS] = campus.name }
    suspend fun setThemeMode(mode: ThemeMode) = edit { it[Keys.THEME_MODE] = mode.name }
    suspend fun setDynamicColor(value: Boolean) = edit { it[Keys.DYNAMIC_COLOR] = value }

    /**
     * 科目色の決め方を切り替える。手動で選んだ色（[timetableCourseColors]）は消さない。
     */
    suspend fun setTimetableColorMode(mode: TimetableColorMode) =
        edit { it[Keys.TIMETABLE_COLOR_MODE] = mode.name }
    suspend fun setCustomAccent(argb: Int?) = edit {
        if (argb == null) it.remove(Keys.CUSTOM_ACCENT) else it[Keys.CUSTOM_ACCENT] = argb
    }
    suspend fun setCustomBackground(argb: Int?) = edit {
        if (argb == null) it.remove(Keys.CUSTOM_BACKGROUND) else it[Keys.CUSTOM_BACKGROUND] = argb
    }
    suspend fun setLoggedIn(value: Boolean) = edit { it[Keys.LOGGED_IN] = value }
    suspend fun setAcademicYear(year: Int) = edit { it[Keys.ACADEMIC_YEAR] = year }
    suspend fun setDiagnosticLogging(value: Boolean) = edit { it[Keys.DIAGNOSTIC_LOGGING] = value }

    suspend fun setFetchSettings(f: FetchSettings) = edit {
        it[Keys.FETCH_FREQ] = f.frequency.name
        it[Keys.FETCH_DAILY_HOUR] = f.dailyHour
        it[Keys.FETCH_MOBILE] = f.allowMobileData
        it[Keys.FETCH_FORCE_SKIPS] = f.forceFetchAfterSkips
    }

    suspend fun setAiSettings(a: AiSettings) = edit {
        it[Keys.AI_BASE_URL] = a.baseUrl
        it[Keys.AI_API_KEY] = a.apiKey
        it[Keys.AI_MODEL] = a.model
        it[Keys.AI_TONE] = a.tone.name
        it[Keys.AI_CUSTOM_INSTRUCTION] = a.customInstruction
    }

    suspend fun setNotificationSettings(n: NotificationSettings) = edit {
        it[Keys.N_DAILY] = n.dailySummary
        it[Keys.N_DAILY_HOUR] = n.dailySummaryHour
        it[Keys.N_DAILY_MIN] = n.dailySummaryMinute
        it[Keys.N_CLASS_START] = n.classStart
        it[Keys.N_DEADLINE] = n.deadline
        it[Keys.N_NEW] = n.newAssignment
        it[Keys.N_LETUS_CHANGE] = n.letusChange
        it[Keys.N_FAIL] = n.fetchFailure
        it[Keys.N_ATTEND] = n.attendanceReminder
        it[Keys.N_ATTEND_MIN] = n.attendanceReminderMinutes
        it[Keys.N_AUTO_ABSENT] = n.autoMarkAbsent
    }

    suspend fun setSkipCounter(value: Int) = edit { it[Keys.SKIP_COUNTER] = value }

    suspend fun setLastFetch(result: String, at: Long) = edit {
        it[Keys.LAST_RESULT] = result
        it[Keys.LAST_AT] = at
    }

    suspend fun setDailySummaryCache(date: String, text: String) = edit {
        it[Keys.AI_SUMMARY_DATE] = date
        it[Keys.AI_SUMMARY_CACHE] = text
    }

    val dailySummaryCache: Flow<Pair<String, String>> = context.dataStore.data.map {
        (it[Keys.AI_SUMMARY_DATE] ?: "") to (it[Keys.AI_SUMMARY_CACHE] ?: "")
    }

    /**
     * 科目ごとの出席目標。キーは [attendanceGoalKey]（`"<年度>:<科目コード>"`）。
     *
     * 保存値が壊れている場合は空マップを返す（画面を壊さない）。
     * 更新時は [saveAttendanceGoal] / [removeAttendanceGoal] が壊れた値を検出して
     * 書き込みを中止するので、ここで空に見えても他の設定を消して上書きすることはない。
     */
    val attendanceGoals: Flow<Map<String, org.cabetus.domain.AttendanceGoal>> =
        context.dataStore.data.map { p -> AttendanceGoalCodec.decode(p[Keys.ATTENDANCE_GOALS]) }

    /**
     * 出席目標を保存する。[year] は 1 以上、[code] は空でないこと、
     * [goal] は [org.cabetus.domain.AttendanceGoalCalculator.validate] を満たすこと。
     *
     * 既存の保存値を読み直してから 1 回の edit で書き戻す（他の科目の目標を消さない）。
     * 保存値が壊れている場合は [IllegalStateException] を投げて書き込みを中止する。
     */
    suspend fun saveAttendanceGoal(
        year: Int,
        code: String,
        goal: org.cabetus.domain.AttendanceGoal,
    ) {
        val key = attendanceGoalKey(year, code)
        require(org.cabetus.domain.AttendanceGoalCalculator.validate(goal)) {
            "invalid attendance goal"
        }
        edit { prefs ->
            val current = AttendanceGoalCodec.decodeOrThrow(prefs[Keys.ATTENDANCE_GOALS])
            prefs[Keys.ATTENDANCE_GOALS] = AttendanceGoalCodec.encode(current + (key to goal))
        }
    }

    /**
     * 出席目標を削除する。存在しないキーは何もしない（他の設定は保たれる）。
     * 保存値が壊れている場合は書き込みを中止する。
     */
    suspend fun removeAttendanceGoal(year: Int, code: String) {
        val key = attendanceGoalKey(year, code)
        edit { prefs ->
            val current = AttendanceGoalCodec.decodeOrThrow(prefs[Keys.ATTENDANCE_GOALS])
            val updated = current - key
            if (updated == current) return@edit
            prefs[Keys.ATTENDANCE_GOALS] = AttendanceGoalCodec.encode(updated)
        }
    }

    /**
     * 手動で選んだ科目色。キーは [timetableColorKey]（`"<年度>:<科目コード>"`）、
     * 値は不透明に正規化した ARGB。
     *
     * 保存値のうち、キー形式が不正・年度が範囲外・値が Int でないものは無視する。
     */
    val timetableCourseColors: Flow<Map<String, Int>> = context.dataStore.data.map { p ->
        val result = LinkedHashMap<String, Int>()
        p.asMap().forEach { (key, value) ->
            val name = key.name
            if (!name.startsWith(TT_COLOR_PREFIX)) return@forEach
            val color = value as? Int ?: return@forEach
            val courseKey = normalizeTimetableColorKey(name.removePrefix(TT_COLOR_PREFIX))
                ?: return@forEach
            result[courseKey] = color
        }
        result
    }

    /**
     * 科目色を保存する。[year] は 1..9999、[code] は空でないこと。
     * [argb] が null なら解除（その科目だけ既定の色に戻る）。
     * それ以外はアルファを 0xFF にして不透明化して保存する。
     * 他の科目の色や他の設定は変更しない。
     */
    suspend fun setTimetableCourseColor(year: Int, code: String, argb: Int?) {
        val prefKey = intPreferencesKey(TT_COLOR_PREFIX + timetableColorKey(year, code))
        edit { prefs ->
            if (argb == null) {
                prefs.remove(prefKey)
            } else {
                prefs[prefKey] = argb or 0xFF000000.toInt()
            }
        }
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }
}
