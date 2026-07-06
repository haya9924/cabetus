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

data class AiSettings(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
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
    val fetchFailure: Boolean = true,
)

data class AppSettings(
    val setupCompleted: Boolean = false,
    val campus: Campus = Campus.KATSUSHIKA,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val loggedIn: Boolean = false,
    val fetch: FetchSettings = FetchSettings(),
    val ai: AiSettings = AiSettings(),
    val notifications: NotificationSettings = NotificationSettings(),
    val skipCounter: Int = 0,
    val lastFetchResult: String = "",
    val lastFetchAt: Long = 0L,
    val academicYear: Int = 0,
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
        val LOGGED_IN = booleanPreferencesKey("logged_in")

        val FETCH_FREQ = stringPreferencesKey("fetch_freq")
        val FETCH_DAILY_HOUR = intPreferencesKey("fetch_daily_hour")
        val FETCH_MOBILE = booleanPreferencesKey("fetch_mobile")
        val FETCH_FORCE_SKIPS = intPreferencesKey("fetch_force_skips")
        val SKIP_COUNTER = intPreferencesKey("skip_counter")

        val AI_BASE_URL = stringPreferencesKey("ai_base_url")
        val AI_API_KEY = stringPreferencesKey("ai_api_key")
        val AI_MODEL = stringPreferencesKey("ai_model")

        val N_DAILY = booleanPreferencesKey("n_daily")
        val N_DAILY_HOUR = intPreferencesKey("n_daily_hour")
        val N_DAILY_MIN = intPreferencesKey("n_daily_min")
        val N_CLASS_START = booleanPreferencesKey("n_class_start")
        val N_DEADLINE = booleanPreferencesKey("n_deadline")
        val N_NEW = booleanPreferencesKey("n_new")
        val N_FAIL = booleanPreferencesKey("n_fail")

        val LAST_RESULT = stringPreferencesKey("last_result")
        val LAST_AT = longPreferencesKey("last_at")
        val ACADEMIC_YEAR = intPreferencesKey("academic_year")

        val AI_SUMMARY_CACHE = stringPreferencesKey("ai_summary_cache")
        val AI_SUMMARY_DATE = stringPreferencesKey("ai_summary_date")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p -> p.toAppSettings() }

    private fun Preferences.toAppSettings() = AppSettings(
        setupCompleted = this[Keys.SETUP_COMPLETED] ?: false,
        campus = Campus.fromNameOrDefault(this[Keys.CAMPUS]),
        themeMode = runCatching { ThemeMode.valueOf(this[Keys.THEME_MODE] ?: "SYSTEM") }
            .getOrDefault(ThemeMode.SYSTEM),
        dynamicColor = this[Keys.DYNAMIC_COLOR] ?: true,
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
        ),
        notifications = NotificationSettings(
            dailySummary = this[Keys.N_DAILY] ?: true,
            dailySummaryHour = this[Keys.N_DAILY_HOUR] ?: 7,
            dailySummaryMinute = this[Keys.N_DAILY_MIN] ?: 30,
            classStart = this[Keys.N_CLASS_START] ?: true,
            deadline = this[Keys.N_DEADLINE] ?: true,
            newAssignment = this[Keys.N_NEW] ?: true,
            fetchFailure = this[Keys.N_FAIL] ?: true,
        ),
        skipCounter = this[Keys.SKIP_COUNTER] ?: 0,
        lastFetchResult = this[Keys.LAST_RESULT] ?: "",
        lastFetchAt = this[Keys.LAST_AT] ?: 0L,
        academicYear = this[Keys.ACADEMIC_YEAR] ?: 0,
    )

    suspend fun current(): AppSettings = settings.first()

    suspend fun setSetupCompleted(value: Boolean) = edit { it[Keys.SETUP_COMPLETED] = value }
    suspend fun setCampus(campus: Campus) = edit { it[Keys.CAMPUS] = campus.name }
    suspend fun setThemeMode(mode: ThemeMode) = edit { it[Keys.THEME_MODE] = mode.name }
    suspend fun setDynamicColor(value: Boolean) = edit { it[Keys.DYNAMIC_COLOR] = value }
    suspend fun setLoggedIn(value: Boolean) = edit { it[Keys.LOGGED_IN] = value }
    suspend fun setAcademicYear(year: Int) = edit { it[Keys.ACADEMIC_YEAR] = year }

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
    }

    suspend fun setNotificationSettings(n: NotificationSettings) = edit {
        it[Keys.N_DAILY] = n.dailySummary
        it[Keys.N_DAILY_HOUR] = n.dailySummaryHour
        it[Keys.N_DAILY_MIN] = n.dailySummaryMinute
        it[Keys.N_CLASS_START] = n.classStart
        it[Keys.N_DEADLINE] = n.deadline
        it[Keys.N_NEW] = n.newAssignment
        it[Keys.N_FAIL] = n.fetchFailure
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

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }
}
