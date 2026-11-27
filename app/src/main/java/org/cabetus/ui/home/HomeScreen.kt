package org.cabetus.ui.home

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.cabetus.data.local.AssignmentEntity
import org.cabetus.data.local.MoodleCourseEntity
import org.cabetus.data.network.NetworkStatus
import org.cabetus.data.network.NetworkStatusFormatter
import org.cabetus.domain.ClassPhase
import org.cabetus.domain.ClassSlotState
import org.cabetus.domain.ClassTimelineResolver
import org.cabetus.domain.LetusClassLinkResolver
import org.cabetus.domain.AttendanceRiskRow
import org.cabetus.domain.MobileAttendanceEvidence
import org.cabetus.domain.attendanceRiskSummary
import org.cabetus.notification.NotificationHelper
import org.cabetus.ui.browser.BrowserIntents
import org.cabetus.ui.browser.ClassAttendanceBackgroundController
import org.cabetus.ui.browser.BrowserUrlPolicy
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/**
 * LETUS コース対応付けピッカーが対象とする科目。
 *
 * ピッカーを開いた時点の科目コード・名称・年度・現在のリンクをまとめて保持し、
 * 実施中のコマや解決結果が後から変わっても保存先がぶれないようにする。
 */
private data class LetusPickerTarget(
    val courseCode: String,
    val courseName: String,
    val academicYear: Int,
    val currentUrl: String?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenAssignments: () -> Unit,
    onOpenNotifications: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val classState by viewModel.classState.collectAsStateWithLifecycle()
    val attendancePanel by viewModel.attendancePanel.collectAsStateWithLifecycle()
    val campusLabel by viewModel.campusNetworkLabel.collectAsStateWithLifecycle()
    val letusCourses by viewModel.letusCourses.collectAsStateWithLifecycle()
    val network by viewModel.networkStatus.collectAsStateWithLifecycle()
    val weather by viewModel.weather.collectAsStateWithLifecycle()
    val aiState by viewModel.aiState.collectAsStateWithLifecycle()
    val unreadCount by viewModel.unreadCount.collectAsStateWithLifecycle()
    val attendanceRisks by viewModel.attendanceRisks.collectAsStateWithLifecycle()
    val mobileStatus by viewModel.mobileStatus.collectAsStateWithLifecycle()
    val scheduleStatus by viewModel.scheduleStatus.collectAsStateWithLifecycle()
    val canRefreshMobile by viewModel.canRefreshMobile.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    fun openBrowser(url: String) {
        scope.launch {
            if (!viewModel.openForegroundBrowser { BrowserIntents.open(context, url) }) {
                android.widget.Toast.makeText(context, "ブラウザを開けませんでした。少し待って再試行してください。", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    // 対応付けの保存先が実施中のコマの変化でぶれないよう、科目コード・名称・年度・
    // 現在のリンクをピッカーを開いた時点でまとめて捕捉する。
    var letusPickerTarget by remember { mutableStateOf<LetusPickerTarget?>(null) }
    var letusPickerSaving by remember { mutableStateOf(false) }
    var letusPickerError by remember { mutableStateOf<String?>(null) }

    // ホームが見えている間だけ自動の出席確認を許可する。
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.setHomeVisible(true)
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> viewModel.setHomeVisible(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        // 既に RESUMED の状態で戻ってきた場合、ON_RESUME が来ないので即時に同期する。
        viewModel.setHomeVisible(
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
        )
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.setHomeVisible(false)
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("ホーム") },
            actions = {
                IconButton(onClick = onOpenNotifications) {
                    BadgedBox(badge = {
                        if (unreadCount > 0) {
                            Badge { Text(if (unreadCount > 99) "99+" else "$unreadCount") }
                        }
                    }) {
                        Icon(Icons.Filled.Notifications, contentDescription = "通知履歴")
                    }
                }
            },
        )
    }) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            item { WeatherCard(weather, state.dateLabel) { viewModel.refreshWeather() } }
            item {
                ClassCard(
                    state = classState,
                    attendancePanel = attendancePanel,
                    mobile = mobileStatus,
                    scheduleRunning = scheduleStatus.running,
                    canRefreshMobile = canRefreshMobile,
                    onRefreshMobile = { viewModel.refreshMobileAttendance() },
                    onOpenClass = { openBrowser(BrowserUrlPolicy.CLASS_URL) },
                    onOpenLetus = ::openBrowser,
                    onConfigureLetus = {
                        classState.timeline.current?.let { current ->
                            letusPickerError = null
                            letusPickerSaving = false
                            letusPickerTarget = LetusPickerTarget(
                                courseCode = current.courseCode,
                                courseName = current.name,
                                academicYear = classState.academicYear,
                                currentUrl = (classState.letusLink as? LetusClassLinkResolver.Result.Course)?.url,
                            )
                        }
                    },
                )
            }
            item {
                ClassScheduleNoticesCard(scheduleStatus) { openBrowser(BrowserUrlPolicy.CLASS_URL) }
            }
            item {
                // 対象があるときだけ 1 枚のコンパクトなカードを出す（無いときは高さ 0）。
                AttendanceRiskCard(attendanceRisks)
            }
            if (classState.timeline.current != null) {
                item {
                    NetworkCard(
                        campusLabel = campusLabel,
                        checking = mobileStatus.running,
                        network = network,
                        onRefresh = { viewModel.refreshNetworkStatus() },
                    )
                }
            }
            item { AiCard(aiState, state.aiConfigured, onGenerate = { viewModel.generateSummary() }, onOpenSettings = onOpenSettings) }
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "残っている課題 " + state.pendingCount + "件",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        // 一覧は先頭 10 件だけを出すので、全件数を別に見出しで示す。
                        if (state.pendingCount > state.pending.size) {
                            Text(
                                "先頭${state.pending.size}件を表示",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    TextButton(onClick = onOpenAssignments) { Text("すべて見る") }
                }
            }
            if (state.pending.isEmpty()) {
                item { Text("未提出の課題はありません 🎉", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(state.pending) { a ->
                    AssignmentRow(a) {
                        if (a.url.isNotBlank()) {
                            openBrowser(a.url)
                        }
                    }
                }
            }
        }
    }

    val pickerTarget = letusPickerTarget
    if (pickerTarget != null) {
        LetusCoursePickerDialog(
            courseCode = pickerTarget.courseCode,
            courseName = pickerTarget.courseName,
            courses = letusCourses,
            currentUrl = pickerTarget.currentUrl,
            saving = letusPickerSaving,
            error = letusPickerError,
            onDismiss = {
                if (!letusPickerSaving) {
                    letusPickerTarget = null
                    letusPickerError = null
                }
            },
            onOpenLetusLogin = { openBrowser(BrowserUrlPolicy.LETUS_URL) },
            onConfirm = { url ->
                // 保存には現在のコマではなく、ピッカーを開いたときの科目コード・年度を使う。
                scope.launch {
                    letusPickerSaving = true
                    letusPickerError = null
                    val result = runCatching {
                        viewModel.setCurrentClassLetusCourse(
                            pickerTarget.academicYear,
                            pickerTarget.courseCode,
                            url,
                        )
                    }
                    letusPickerSaving = false
                    if (result.isSuccess) {
                        letusPickerTarget = null
                    } else {
                        letusPickerError = result.exceptionOrNull()?.message
                            ?: "LETUSの対応付けを保存できませんでした"
                    }
                }
            },
        )
    }
}

/**
 * 天気の常時表示は 1 行の要約だけに留める（縦の占有を小さく保つ）。
 * 時間別の予報は初期状態では畳んでおき、ボタンで開いたときだけ出す。
 * 取得できなかった場合も同じ 1 行の高さに収める。
 */
@Composable
private fun WeatherCard(
    weather: org.cabetus.data.weather.WeatherSummary?,
    dateLabel: String,
    onRefresh: () -> Unit,
) {
    var showHourly by rememberSaveable { mutableStateOf(false) }
    val hasHourly = weather != null && weather.hourly.isNotEmpty()
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(weather?.emoji ?: "🌡️", fontSize = 24.sp)
                Column(Modifier.weight(1f)) {
                    Text(dateLabel, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    if (weather != null) {
                        Text(
                            weather.label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            buildString {
                                weather.currentTemp?.let { append("${it.toInt()}℃ ") }
                                weather.maxTemp?.let { append("最高 ${it.toInt()}℃ ") }
                                weather.minTemp?.let { append("最低 ${it.toInt()}℃ ") }
                                weather.precipProbability?.let { append("降水 ${it}%") }
                            }.trim(),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        Text(
                            "天気を取得できませんでした",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (hasHourly) {
                    TextButton(
                        onClick = { showHourly = !showHourly },
                        modifier = Modifier.widthIn(min = 64.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            if (showHourly) "閉じる" else "時間別",
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
                IconButton(onClick = onRefresh, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Refresh, contentDescription = "更新")
                }
            }
            if (showHourly && hasHourly) {
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    weather?.hourly?.forEach { h -> HourlyCell(h) }
                }
            }
        }
    }
}

@Composable
private fun HourlyCell(h: org.cabetus.data.weather.HourlyForecast) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(h.hourLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(h.emoji, fontSize = 20.sp)
        Text(h.temp?.let { "${it}℃" } ?: "—", style = MaterialTheme.typography.bodySmall)
        Text(
            h.precip?.let { "${it}%" } ?: "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun ClassCard(
    state: HomeClassState,
    attendancePanel: List<MobileAttendanceEvidence.PageCourseDisplay>,
    mobile: ClassAttendanceBackgroundController.MobileStatus,
    scheduleRunning: Boolean,
    canRefreshMobile: Boolean,
    onRefreshMobile: () -> Unit,
    onOpenClass: () -> Unit,
    onOpenLetus: (String) -> Unit,
    onConfigureLetus: () -> Unit,
) {
    var showDetails by rememberSaveable { mutableStateOf(false) }
    // CLASS ボタンは自動確認中も無効化しない。openForegroundBrowser は背景の確認を安全に
    // 中断し、Ticket 1 以降は取得済みの有効なスナップショットを失敗で破棄しないため。
    // 対応付けのピッカーはブラウザを開かないので、自動読み取り中も使える。
    val timeline = state.timeline
    val letusLink = state.letusLink
    val current = timeline.current
    val next = timeline.next
    // コースページを特定できたときだけ「この授業のLETUS」を出す。
    val courseLink = letusLink as? LetusClassLinkResolver.Result.Course
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (current != null) {
                CurrentClassBlock(current)
            } else if (next == null) {
                Text(
                    when {
                        timeline.slots.isEmpty() -> "今日の授業はありません"
                        else -> "本日の授業は終了しました"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (next != null) {
                NextClassBlock(next, timeline.now)
            }
            // 出席状況は実施中のコマの有無によらず、CLASS の画面に写した授業ブロックだけで示す。
            AttendancePanelSection(
                courses = attendancePanel,
                mobile = mobile,
                scheduleRunning = scheduleRunning,
                canRefreshMobile = canRefreshMobile,
                onRefreshMobile = onRefreshMobile,
            )
            if (timeline.slots.isNotEmpty()) {
                TodaySlotsRow(timeline.slots)
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onOpenClass,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text("CLASS", maxLines = 1, softWrap = false)
                }
                if (current != null) {
                    if (courseLink != null) {
                        TextButton(
                            onClick = { onOpenLetus(courseLink.url) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text("この授業のLETUS", maxLines = 1, softWrap = false)
                        }
                    } else {
                        // 実施中の授業ではトップへ逃がさず、対応付けを促す。
                        TextButton(
                            onClick = onConfigureLetus,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text("この授業のLETUSを設定", maxLines = 1, softWrap = false)
                        }
                    }
                }
                TextButton(
                    onClick = { showDetails = !showDetails },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        if (showDetails) "詳細を閉じる" else "詳細",
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
            // 対応付けのやり直しはナビゲーションの行と分ける（行が横に広がらないようにする）。
            if (current != null && courseLink != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = onConfigureLetus,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            "リンク変更",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
            if (letusLink != null && !letusLink.isCourseSpecific && !showDetails) {
                Text(letusLink.detailText, style = MaterialTheme.typography.bodySmall)
            }
            if (showDetails) {
                if (letusLink != null) {
                    Text(
                        letusLink.detailText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "時限時刻はアプリ既定の時程（1限08:50〜10:20 ほか）。CLASSの時程と異なる場合があります。" +
                        "CLASSはトップページを開き、出席登録は行いません。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    MobileAttendanceEvidence.SCOPE_NOTE,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "ホーム表示中に自動確認。出席の入力・送信は行いません。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    MobileAttendanceEvidence.PENDING_NOTE,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 実施中授業に LETUS コースを対応付けるピッカー。
 *
 * [onConfirm] には LETUS のコースページとして検証できる URL だけを渡す。
 * 保存が成功するまでピッカーを閉じない（呼び出し側が [saving] を管理する）。
 */
@Composable
private fun LetusCoursePickerDialog(
    courseCode: String,
    courseName: String,
    courses: List<MoodleCourseEntity>,
    currentUrl: String?,
    saving: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onOpenLetusLogin: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val candidates = courses
        .mapNotNull { course ->
            val id = LetusClassLinkResolver.courseViewId(course.url) ?: return@mapNotNull null
            id to course
        }
        .distinctBy { it.first }
    var selectedId by remember(courseCode) { mutableStateOf<String?>(null) }
    val selected = candidates.firstOrNull { it.first == selectedId }
    val currentId = LetusClassLinkResolver.courseViewId(currentUrl)

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("LETUSコースの対応付け") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "$courseName（$courseCode）に開くLETUSコースを選んでください。",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (candidates.isEmpty()) {
                    Text(
                        "LETUSのコース情報がありません。LETUSにログインし、コース一覧を更新すると選べるようになります。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onOpenLetusLogin, enabled = !saving) {
                        Text("LETUSにログイン")
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(candidates, key = { it.first }) { (id, course) ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable(enabled = !saving) { selectedId = id }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    if (id == selectedId) "●" else "○",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(course.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        if (id == currentId) "ID $id・現在のリンク" else "ID $id",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                selected?.let { (id, course) ->
                    Text(
                        "「${course.name}」（ID $id）に設定します。",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (error != null) {
                    Text(
                        "保存できませんでした: $error",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (saving) {
                    Text("保存中…", style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    selected?.let { (id, _) -> onConfirm(LetusClassLinkResolver.courseUrl(id)) }
                },
                // 保存中は二重送信しない。候補未選択では保存しない。
                enabled = !saving && selected != null,
            ) { Text("このコースに設定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("キャンセル") }
        },
    )
}

/**
 * 出席目標の危険科目を 1 枚のコンパクトなカードで知らせる。
 *
 * 科目名は先頭 1 件だけを出し、残りは「ほか N 科目」にまとめて最大 2 行に収める。
 * 対象が無ければ何も描画しない（ホームの縦の占有を増やさない）。
 */
@Composable
private fun AttendanceRiskCard(risks: List<AttendanceRiskRow>) {
    val summary = attendanceRiskSummary(risks) ?: return
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text("出席目標に注意 ${risks.size}科目", style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
            risks.take(2).forEach { risk ->
                Text("${risk.name}：" + if (risk.remainingAbsences < 0) "記録済み分で目標達成不可" else "記録済み分では欠席あと${risk.remainingAbsences}回",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (risks.size > 2) Text("ほか${risks.size - 2}科目", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
private fun NextClassBlock(next: ClassSlotState, now: LocalDateTime?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("次 ${next.period}限 ${next.name}${next.roomSuffix()}", fontWeight = FontWeight.Bold)
        val start = next.start
        if (start != null) {
            Text(
                buildString {
                    append("%02d:%02d〜".format(start.hour, start.minute))
                    if (now != null) {
                        append("  開始まで ")
                        append(
                            ClassTimelineResolver.formatDuration(
                                ClassTimelineResolver.secondsUntil(start, now.toLocalTime()),
                            ),
                        )
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** One compact strip: keep the current slot pinned; only the other slots scroll. */
@Composable
private fun TodaySlotsRow(slots: List<ClassSlotState>) {
    val current = slots.firstOrNull { it.phase == ClassPhase.ONGOING }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (current != null) {
            TodaySlotChip(current, Modifier.weight(0.55f))
        }
        Row(
            Modifier.weight(if (current != null) 0.45f else 1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            slots.filter { it !== current }.forEach { slot ->
                TodaySlotChip(slot, Modifier.widthIn(max = 160.dp))
            }
        }
    }
}

@Composable
private fun TodaySlotChip(slot: ClassSlotState, modifier: Modifier) {
            val ongoing = slot.phase == ClassPhase.ONGOING
            val mark = when (slot.phase) {
                ClassPhase.ONGOING -> "●"
                ClassPhase.FINISHED -> "✓"
                ClassPhase.UPCOMING -> "○"
            }
            Surface(
                modifier = modifier,
                color = if (ongoing) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                shape = MaterialTheme.shapes.small,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        color = if (ongoing) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                        shape = CircleShape,
                    ) {
                        Text(
                            if (ongoing) "●${slot.period}限" else "$mark${slot.period}限",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (ongoing) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                        )
                    }
                    Text(
                        slot.name,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (ongoing) FontWeight.Bold else FontWeight.Normal,
                        color = if (ongoing) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
}

@Composable
private fun CurrentClassBlock(slot: ClassSlotState) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            slot.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PhaseLabel(slot.phase)
            Text(
                compactRoomTime(slot),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 授業自体の残り時間。出席確認の残り時間とは別に、ラベルで区別する。
        slot.remainingSeconds?.let { remaining ->
            Text(
                "授業あと " + ClassTimelineResolver.formatDuration(remaining),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 「出席状況（CLASS）」のセクション。
 *
 * CLASS の画面に写した授業ブロックを表示順のまま並べ、科目名の隣にその行の状態だけを出す。
 * 行が無い場合は科目名も状態も推測せず、自動確認の実行中か「状況未確認」だけを示す。
 * 読み取り時刻はセクションに 1 回だけ出し、行ごとには繰り返さない。
 */
@Composable
private fun AttendancePanelSection(
    courses: List<MobileAttendanceEvidence.PageCourseDisplay>,
    mobile: ClassAttendanceBackgroundController.MobileStatus,
    scheduleRunning: Boolean,
    canRefreshMobile: Boolean,
    onRefreshMobile: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "出席状況（CLASS）",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            // 自動確認を待たずに、その場で読み取り直せる小さな導線。
            TextButton(
                onClick = onRefreshMobile,
                enabled = !mobile.running && canRefreshMobile,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    if (mobile.running) "確認中…" else "再確認",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        if (courses.isEmpty()) {
            Text(
                when {
                    mobile.running -> "確認中…"
                    mobile.authRequired -> "CLASS にログインしてください"
                    scheduleRunning -> "授業変更の確認後に自動確認します…"
                    mobile.failed -> "自動確認できませんでした（状況未確認）"
                    else -> MobileAttendanceEvidence.STATUS_UNKNOWN_LABEL
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            courses.forEach { course ->
                AttendancePanelRow(course.attendance, course.courseName)
            }
            // 手動反映という表現を避け、中立的に最後の確認時刻だけをセクションに 1 回示す。
            lastCheckedLabel(courses.first().attendance)?.let { checked ->
                Text(
                    checked,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 1 授業ブロック分の行。科目名の隣に、その行で明示された状態だけを出す。 */
@Composable
private fun AttendancePanelRow(
    attendance: MobileAttendanceEvidence.Display,
    courseName: String,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(courseName, style = MaterialTheme.typography.bodyMedium)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AttendanceStatusPill(attendance)
            if (attendance.isPending) {
                attendance.remainingSeconds?.let { remaining ->
                    Text(
                        "受付あと " + ClassTimelineResolver.formatDuration(remaining),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        if (attendance.isPending) {
            attendance.windowLabel?.let { window ->
                Text(
                    "受付 $window",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 実施中／終了／予定を短いラベルで表す。 */
@Composable
private fun PhaseLabel(phase: ClassPhase) {
    val (label, container, content) = when (phase) {
        ClassPhase.ONGOING -> Triple(
            "実施中",
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.onPrimary,
        )
        ClassPhase.FINISHED -> Triple(
            "終了",
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ClassPhase.UPCOMING -> Triple(
            "予定",
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Surface(color = container, shape = CircleShape) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = content,
            maxLines = 1,
        )
    }
}

/**
 * 出席状況の小さなピル。
 *
 * 明示された「出席完了」「受付中」だけを出し、それ以外は「状況未確認」にまとめる。
 * 自動確認の短いメッセージや推測・案内は行に出さない。
 */
@Composable
private fun AttendanceStatusPill(attendance: MobileAttendanceEvidence.Display) {
    val (label, container, content) = when {
        attendance.isConfirmed -> Triple(
            "出席完了",
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.onPrimary,
        )
        attendance.isPending -> Triple(
            "受付中",
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )
        else -> Triple(
            MobileAttendanceEvidence.STATUS_UNKNOWN_LABEL,
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Surface(color = container, shape = CircleShape) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 教室が分かる場合だけ添え、時刻と 1 行にまとめる。 */
private fun compactRoomTime(slot: ClassSlotState): String {
    val roomPart = when {
        slot.isOnline -> "オンライン"
        !slot.room.isNullOrBlank() -> slot.room
        else -> null
    }
    val start = slot.start
    val end = slot.end
    val timePart = if (start != null && end != null) {
        "%02d:%02d〜%02d:%02d".format(start.hour, start.minute, end.hour, end.minute)
    } else {
        start?.let { "%02d:%02d〜".format(it.hour, it.minute) }
    }
    return listOfNotNull(roomPart, timePart).joinToString("・")
}

/** 最後に確認した時刻（手動・自動を問わない中立的な表記）。 */
private fun lastCheckedLabel(display: MobileAttendanceEvidence.Display): String? {
    val capturedAt = display.capturedAt ?: return null
    val clock = "%02d:%02d".format(capturedAt.hour, capturedAt.minute)
    val age = display.ageSeconds?.let { MobileAttendanceEvidence.formatAge(it) + "前" }
    return if (age == null) "$clock 時点" else "$clock 時点（$age）"
}

/** 教室が無い・オンラインのコマを推測せず、そのまま区別して表示する。 */
private fun ClassSlotState.roomSuffix(): String = when {
    isOnline -> "（オンライン）"
    !room.isNullOrBlank() -> "（$room）"
    else -> ""
}

/**
 * 出席コード入力時の接続確認用の小さなチップ。
 *
 * 常時大きな説明は出さず、初期状態は折りたたみ。接続種別の詳細・学内判定ができない旨・
 * CLASS への接続やログインを保証しない注意は、展開したときだけ表示する。
 */
@Composable
private fun NetworkCard(
    campusLabel: String?,
    checking: Boolean,
    network: NetworkStatus,
    onRefresh: () -> Unit,
) {
    var showDetails by rememberSaveable { mutableStateOf(false) }
    val unverified = network.isKnown && network.isConnected && !network.isValidated
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { showDetails = !showDetails },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    Icons.Filled.Wifi,
                    contentDescription = null,
                    tint = if (unverified) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    if (checking) "CLASSで接続を確認中…" else campusLabel ?: "CLASSの学内判定を取得",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (unverified) {
                    Text(
                        "未検証",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                TextButton(
                    onClick = { showDetails = !showDetails },
                    modifier = Modifier.widthIn(min = 64.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        if (showDetails) "閉じる" else "詳細",
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
            if (showDetails) {
                Column(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp, end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(NetworkStatusFormatter.summaryLabel(network), style = MaterialTheme.typography.bodySmall)
                    Text(NetworkStatusFormatter.typeLabel(network), style = MaterialTheme.typography.bodySmall)
                    Text(
                        NetworkStatusFormatter.stateLabel(network),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (unverified) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        campusLabel ?: "CLASSの学内判定は取得できていません",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "CLASSの表示を自動確認します。表示がない場合、学外とは断定しません。\n" +
                            NetworkStatusFormatter.CLASS_CONNECTION_CAVEAT,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onRefresh, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                        Text("再取得", modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
    }
}

/**
 * 今日のひとこと。
 *
 * AI が未設定（[configured] が false、または状態が未設定）のときは、
 * 本文や生成ボタンを出さず、一行の案内と設定への導線だけを残す。
 */
@Composable
private fun AiCard(
    aiState: AiCardState,
    configured: Boolean,
    onGenerate: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val aiUnavailable = !configured || aiState is AiCardState.NotConfigured
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (aiUnavailable) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            } else {
                MaterialTheme.colorScheme.tertiaryContainer
            },
        ),
    ) {
        if (aiUnavailable) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "今日のひとこと・オフ／未設定",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.widthIn(min = 64.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text("設定", maxLines = 1, softWrap = false)
                }
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null)
                    Text("今日のひとこと", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                }
                when (aiState) {
                    is AiCardState.Loaded -> Text(aiState.text)
                    AiCardState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.padding(end = 8.dp))
                        Text("生成中…")
                    }

                    is AiCardState.Error -> Text("エラー: ${aiState.message}", color = MaterialTheme.colorScheme.error)
                    AiCardState.NotConfigured -> Text("設定でAIのAPIを設定すると利用できます。")
                    AiCardState.Idle -> Text("ボタンを押すと今日のアドバイスを生成します。")
                }
                TextButton(onClick = onGenerate) { Text("生成する") }
            }
        }
    }
}

@Composable
private fun AssignmentRow(a: AssignmentEntity, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(a.title, fontWeight = FontWeight.Bold, maxLines = 2)
            Text(a.courseName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "期限: ${NotificationHelper.formatDeadline(a.deadline)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
