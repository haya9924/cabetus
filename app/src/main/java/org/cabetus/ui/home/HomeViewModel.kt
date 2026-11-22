package org.cabetus.ui.home

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.cabetus.data.ai.AssistantContext
import org.cabetus.data.ai.AssistantRepository
import org.cabetus.data.ai.AssistantResult
import org.cabetus.data.classweb.MobileAttendanceRepository
import org.cabetus.data.local.AssignmentDao
import org.cabetus.data.local.AssignmentEntity
import org.cabetus.data.local.ClassCourseDao
import org.cabetus.data.local.MoodleCourseDao
import org.cabetus.data.local.NotificationHistoryDao
import org.cabetus.data.local.TimetableDao
import org.cabetus.data.network.NetworkMonitor
import org.cabetus.data.network.NetworkStatus
import org.cabetus.data.settings.SettingsRepository
import org.cabetus.data.weather.WeatherRepository
import org.cabetus.data.weather.WeatherSummary
import org.cabetus.domain.AttendanceGoalMonitor
import org.cabetus.domain.AttendanceRiskRow
import org.cabetus.domain.ClassTimeline
import org.cabetus.domain.ClassTimelineResolver
import org.cabetus.domain.attendanceRiskRows
import org.cabetus.domain.LetusClassLinkResolver
import org.cabetus.domain.MobileAttendanceEvidence
import org.cabetus.notification.NotificationHelper
import org.cabetus.ui.browser.ClassAttendanceBackgroundController
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

data class HomeUiState(
    val dateLabel: String = "",
    val pending: List<AssignmentEntity> = emptyList(),
    val aiConfigured: Boolean = false,
    val pendingCount: Int = 0,
    val academicYear: Int = 0,
)

/**
 * ホームの授業カードが 1 つの状態として描画する値。
 *
 * 時間割タイムラインと、その実施中授業に対応する LETUS リンクは同じ入力から
 * 同じタイミングで決まるため、別々に購読せず 1 つの状態にまとめる。
 * 初期値は空のタイムライン（[HomeViewModel.timeline] の初期値）とリンクなし。
 */
data class HomeClassState(
    val timeline: ClassTimeline,
    val letusLink: LetusClassLinkResolver.Result? = null,
    val academicYear: Int = 0,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val assignmentDao: AssignmentDao,
    private val timetableDao: TimetableDao,
    private val classCourseDao: ClassCourseDao,
    private val moodleCourseDao: MoodleCourseDao,
    private val weatherRepository: WeatherRepository,
    private val assistantRepository: AssistantRepository,
    private val notificationHelper: NotificationHelper,
    private val notificationHistoryDao: NotificationHistoryDao,
    private val networkMonitor: NetworkMonitor,
    private val mobileAttendanceRepository: MobileAttendanceRepository,
    private val backgroundController: ClassAttendanceBackgroundController,
    attendanceGoalMonitor: AttendanceGoalMonitor,
) : ViewModel() {

    private val dateFmt = DateTimeFormatter.ofPattern("M月d日（E）", Locale.JAPANESE)

    /** アプリの表示・判定に使う基準タイムゾーン（日本時間）。 */
    private val zone: ZoneId = ZoneId.of("Asia/Tokyo")

    val uiState: StateFlow<HomeUiState> = combine(
        settingsRepository.settings,
        assignmentDao.observePending(),
        flow { while (true) { emit(LocalDate.now(zone)); delay(60_000) } },
    ) { settings, pending, today ->
        HomeUiState(
            dateLabel = today.format(dateFmt),
            pending = pending.take(10),
            aiConfigured = settings.ai.isConfigured && settings.notifications.dailySummary,
            pendingCount = pending.size,
            academicYear = settings.academicYear,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState())

    /**
     * 今日の授業と実施中授業の残り時間。
     *
     * 画面が購読している間だけ1秒ごとに現在時刻を流し、時限境界・日付境界をまたいでも
     * 「実施中／次／終了」と残り時間が追従する。時刻は日本時間（Asia/Tokyo）を使う。
     */
    val timeline: StateFlow<ClassTimeline> = combine(
        timetableDao.observeAll(),
        classCourseDao.observeAll(),
        secondTicker(),
    ) { cells, courses, now ->
        ClassTimelineResolver.resolve(
            cells = cells,
            nameByCode = courses.associate { it.code to it.name },
            now = now,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        ClassTimeline(
            date = LocalDate.now(zone),
            now = null,
            slots = emptyList(),
            current = null,
            next = null,
        ),
    )

    /**
     * 端末のネットワーク状態。画面表示中だけ NetworkCallback を登録する。
     * CLASS への到達性やログイン状態を判定するものではない。
     */
    private val networkRestart = MutableStateFlow(0)

    val networkStatus: StateFlow<NetworkStatus> = networkRestart
        .flatMapLatest { networkMonitor.status }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NetworkStatus.Unknown)

    private val _weather = MutableStateFlow<WeatherSummary?>(null)
    val weather: StateFlow<WeatherSummary?> = _weather.asStateFlow()

    /**
     * 背景読み取り（モバイル出席確認）の進行・要再ログイン状態。
     * ホーム表示中だけ自動更新を回す判定にも使う。
     */
    val mobileStatus = backgroundController.mobileStatus
    val scheduleStatus = backgroundController.scheduleStatus
    private fun tryRefreshScheduleNotices() {
        backgroundController.tryStartScheduleNotices()
    }

    /** ホーム画面が表示中かどうか。自動更新・タイマーの購読を表示中だけに限定する。 */
    private val homeVisible = MutableStateFlow(false)

    /** ホーム画面の表示状態を伝える。 */
    fun setHomeVisible(visible: Boolean) {
        homeVisible.value = visible
    }

    /**
     * 実施中授業の出席状況（PENDING / CONFIRMED / 状況未確認）。
     *
     * CLASS モバイル出席登録の読み取り結果（[MobileAttendanceRepository.snapshot]）を、
     * 表示中のコマ・現在時刻・端末ネットワークと突き合わせて評価する。
     * 背景読み取りが「要再ログイン」を返している間（[mobileStatus] の
     * [ClassAttendanceBackgroundController.MobileStatus.authRequired]）だけ
     * [MobileAttendanceEvidence.LoginState.LOGGED_OUT] として評価し、古い読み取り結果を使わない。
     * それ以外はログイン状態を確認できていないため
     * [MobileAttendanceEvidence.LoginState.UNKNOWN] とする（設定の lastFetchResult / loggedIn から
     * ログイン切れを推測しない）。ホームで状態として出すのは
     * [MobileAttendanceEvidence.Status.CONFIRMED]（出席完了）と
     * [MobileAttendanceEvidence.Status.PENDING]（出席確認中。受付終了までの残り秒を出す）だけで、
     * 失効・非該当・受付終了（[MobileAttendanceEvidence.Status.EXPIRED]）は
     * 「状況未確認」にまとめる。未提出や残り期限を推測して表示しない。
     *
     * 評価は [timeline]（1秒ごとの現在時刻）と組み合わせているので、PENDING の残り秒は
     * 毎秒更新され、受付終了を過ぎれば EXPIRED（状況未確認）に変わる。
     * これは授業自体の残り時間（[ClassSlotState.remainingSeconds]）とは別の値である。
     */
    val campusNetworkLabel: StateFlow<String?> = combine(
        mobileAttendanceRepository.snapshot, networkStatus, timeline, mobileStatus,
    ) { snapshot, network, _, mobile ->
        org.cabetus.domain.CampusNetworkEvidence.label(snapshot, network, System.currentTimeMillis(), mobile.authRequired)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val attendanceEvidence: StateFlow<MobileAttendanceEvidence.Display> = combine(
        mobileAttendanceRepository.snapshot,
        timeline,
        networkStatus,
        mobileStatus,
    ) { snapshot, classTimeline, network, mobile ->
        val current = classTimeline.current
        val now = classTimeline.now
        when {
            snapshot == null -> MobileAttendanceEvidence.Display(
                status = MobileAttendanceEvidence.Status.NONE,
            )

            current == null || now == null -> MobileAttendanceEvidence.Display(
                status = MobileAttendanceEvidence.Status.NOT_APPLICABLE,
                reason = "実施中の授業がないため、出席完了は表示しません。",
            )

            else -> MobileAttendanceEvidence.evaluate(
                snapshot = snapshot,
                now = now,
                dayOfWeek = classTimeline.date.dayOfWeek.value,
                period = current.period,
                courseCode = current.courseCode,
                currentTransport = network.transport.takeIf { network.isKnown && network.isConnected },
                currentNetworkKnown = network.isKnown && network.isConnected,
                loginState = if (mobile.authRequired) {
                    MobileAttendanceEvidence.LoginState.LOGGED_OUT
                } else {
                    MobileAttendanceEvidence.LoginState.UNKNOWN
                },
            )
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        MobileAttendanceEvidence.Display(status = MobileAttendanceEvidence.Status.NONE),
    )

    /** 画面表示中だけ購読する LETUS コース一覧（[classState] と共有）。 */
    val letusCourses = moodleCourseDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * ホームの授業カードの状態（タイムライン＋実施中授業の LETUS リンク＋設定年度）。
     *
     * CLASS の科目と LETUS のコースは、ユーザーが確認した対応付け
     * （[SettingsRepository.classAttendanceMappings] / [SettingsRepository.classLetusMappings]）
     * があるときだけ明示的に結ぶ。仮コード（CLASSWEB-）の科目は、出席対応から
     * 正式コードが一意に決まるときだけその正式コードで照合する（複数なら推測しない）。
     * 明示マッピングが無い場合、[LetusClassLinkResolver] は LETUS 名末尾の科目コード、
     * 次に科目名の完全一致でコースページを特定し、曖昧・不一致なら LETUS トップへ逃がす
     * （別授業を開かない）。実施中の授業が無いときは null（LETUS の導線を出さない）。
     *
     * タイムラインとリンクは同じ入力から同時に決まるので、画面では 1 状態として描画する。
     */
    val classState: StateFlow<HomeClassState> = combine(
        timeline,
        letusCourses,
        settingsRepository.settings,
        settingsRepository.classAttendanceMappings,
        settingsRepository.classLetusMappings,
    ) { classTimeline, moodleCourses, settings, attendanceMappings, letusMappings ->
        val year = settings.academicYear
        val link = classTimeline.current?.let { current ->
            val localCode = current.courseCode
            // 仮コード（CLASSWEB-）の科目は、年度内で値が一致する正式コードが
            // ちょうど 1 件のときだけその正式コードを照合に使う。複数ならローカルコードのまま。
            val officialAlias = attendanceMappings
                .filter { (key, value) -> key.startsWith("$year:") && value == localCode }
                .keys
                .map { it.removePrefix("$year:") }
                .distinct()
                .singleOrNull()
            val resolvedCode = officialAlias ?: localCode
            // 明示マッピングはローカルコードで引き、リゾルバには解決後のコードの下で渡す。
            val explicitUrl = letusMappings["$year:$localCode"]
            LetusClassLinkResolver.resolve(
                courseCode = resolvedCode,
                courseName = current.name,
                explicitUrlByCourseCode = explicitUrl?.let { mapOf(resolvedCode to it) } ?: emptyMap(),
                moodleCourses = moodleCourses.map {
                    LetusClassLinkResolver.MoodleCourseRef(id = it.id, name = it.name, url = it.url)
                },
            )
        }
        HomeClassState(
            timeline = classTimeline,
            letusLink = link,
            academicYear = year,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        HomeClassState(timeline = timeline.value, letusLink = null),
    )

    /**
     * 実施中授業の LETUS コース対応付けを、現在の設定年度に対して保存する。
     * [url] が null ならその科目の対応付けだけを削除する。
     * 呼び出し側は対象の [courseCode] を引数で渡す（実施中のコマが変わっても保存先が
     * ぶれないよう、画面側で対象コードを捕捉してから呼ぶ）。
     */
    suspend fun setCurrentClassLetusCourse(year: Int, courseCode: String, url: String?) {
        settingsRepository.saveClassLetusMapping(year, courseCode, url)
    }

    suspend fun openForegroundBrowser(launch: () -> Boolean): Boolean =
        backgroundController.openForeground(launch)

    val unreadCount: StateFlow<Int> = notificationHistoryDao.observeUnreadCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /**
     * 出席目標の危険科目（通知と同じ判定をホームにも出す）。
     *
     * [AttendanceGoalMonitor.evaluations] と時間割・設定年度を合成し、年度一致・時間割に
     * 存在する科目・残り欠席可能回数 2 以下だけを残り回数の昇順（同数はコード順）で返す。
     * 科目名は時間割の名称、空なら科目コード。ここでは通知を出さず、既存の通知判定も変えない。
     */
    val attendanceRisks: StateFlow<List<AttendanceRiskRow>> = combine(
        attendanceGoalMonitor.evaluations,
        timetableDao.observeAll(),
        settingsRepository.settings,
    ) { evaluations, cells, settings ->
        attendanceRiskRows(evaluations, cells, settings.academicYear)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
        // ホーム表示中だけ、実施中コマの出席確認を自動で読み取る。
        // 非表示時は timeline / networkStatus を購読しない（1秒タイマーを動かさない）。
        // 時間割が未登録の間は、変更通知の自動読取でブラウザ枠を先取りしない
        // （初回の時間割取り込みを優先する）。時間割が登録されれば従来どおり動作する。
        viewModelScope.launch {
            // 直近に起動を要求したときのブラウザセッション。
            // 要再ログイン後、CLASS ブラウザを開いて閉じるとセッションが進み、抑制が解ける。
            var requestSession: Long? = null
            // 直前の自動更新キーと試行時刻（キーが変わればスロットルしない）。
            val refreshGate = org.cabetus.domain.MobileAttendanceRefreshGate(AUTO_REFRESH_THROTTLE_MS)

            homeVisible
                .flatMapLatest { visible ->
                    if (visible) {
                        combine(
                            timeline,
                            networkStatus,
                            timetableDao.observeAll()
                                .map { it.isNotEmpty() }
                                .distinctUntilChanged(),
                        ) { classTimeline, network, hasTimetable ->
                            Triple(classTimeline, network, hasTimetable)
                        }
                    } else {
                        emptyFlow<Triple<ClassTimeline, NetworkStatus, Boolean>>()
                    }
                }
                .collect { (classTimeline, network, hasTimetable) ->
                    if (!homeVisible.value) return@collect
                    // 時間割が未登録の間は通知・モバイル出席の自動読取を開始しない。
                    if (!hasTimetable) return@collect
                    val current = classTimeline.current
                    if (!network.isKnown || !network.isConnected) return@collect
                    if (backgroundController.isBrowserBusy) return@collect

                    if (current == null) {
                        tryRefreshScheduleNotices()
                        return@collect
                    }

                    val session = backgroundController.browserSession.value
                    // 要再ログインの間は、CLASS ブラウザを開いて閉じる（セッションが進む）まで自動更新しない。
                    val mobile = mobileStatus.value
                    if (mobile.authRequired) {
                        if (requestSession == null) requestSession = session
                        if (requestSession == session) {
                            tryRefreshScheduleNotices()
                            return@collect
                        }
                    }

                    val key = "${classTimeline.date}:${current.period}:${current.courseCode}:" +
                        "${network.transport.name}:$session"
                    val nowElapsed = SystemClock.elapsedRealtime()
                    if (refreshGate.isDue(key, nowElapsed)) {
                        // 失敗した起動も毎秒繰り返さない。
                        refreshGate.markStarted(key, nowElapsed)
                        if (backgroundController.tryStartMobileAttendance()) {
                            requestSession = session
                            return@collect
                        }
                    }
                    tryRefreshScheduleNotices()
                }
        }
    }

    fun refreshWeather() {
        viewModelScope.launch {
            val campus = settingsRepository.current().campus
            _weather.value = weatherRepository.getWeather(campus)
        }
    }

    /** ネットワーク状態を取り直す（NetworkCallback を登録し直して現在値を再取得）。 */
    fun refreshNetworkStatus() {
        networkRestart.update { it + 1 }
    }

    fun generateSummary() {
        viewModelScope.launch {
            _aiState.value = AiCardState.Loading
            val settings = settingsRepository.current()
            if (!settings.ai.isConfigured || !settings.notifications.dailySummary) {
                _aiState.value = AiCardState.NotConfigured
                return@launch
            }
            val state = uiState.value
            val timeline = timeline.value
            val context = AssistantContext(
                dateLabel = timeline.date.format(dateFmt),
                todayClasses = timeline.slots.map {
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

    /**
     * 秒境界に合わせて現在時刻を流す。残り時間表示が1秒ごとに正しく減るようにし、
     * 端末の時刻変更・日付跨ぎにも追従する。時刻は日本時間（Asia/Tokyo）で流す。
     */
    private fun secondTicker(): Flow<LocalDateTime> = flow {
        while (true) {
            val now = LocalDateTime.now(zone)
            emit(now)
            val sleepMs = 1_000L - (now.nano / 1_000_000L)
            delay(sleepMs.coerceIn(1L, 1_000L))
        }
    }

    private companion object {
        /** 同じコマ・同じネットワーク種別での自動更新の最短間隔（ミリ秒）。 */
        const val AUTO_REFRESH_THROTTLE_MS = 60_000L
    }
}

sealed interface AiCardState {
    data object Idle : AiCardState
    data object Loading : AiCardState
    data class Loaded(val text: String) : AiCardState
    data class Error(val message: String) : AiCardState
    data object NotConfigured : AiCardState
}
