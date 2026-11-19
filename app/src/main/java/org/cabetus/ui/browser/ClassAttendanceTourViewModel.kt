package org.cabetus.ui.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.cabetus.data.classweb.ClassAttendanceDetailDomExtractor
import org.cabetus.data.classweb.ClassAttendanceDetailImportRepository
import org.cabetus.data.classweb.ClassAttendanceImportPreview
import org.cabetus.data.classweb.ClassAttendanceListDomExtractor
import org.cabetus.data.classweb.ClassAttendanceTour
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

data class ClassAttendanceTourUiState(
    val phase: ClassAttendanceTour.Phase = ClassAttendanceTour.Phase.STOPPED,
    val running: Boolean = false,
    val resultVisible: Boolean = false,
    val notice: String = ClassAttendanceListDomExtractor.PAGINATION_NOTICE,
    val total: Int = 0,
    val processed: Int = 0,
    val doneCount: Int = 0,
    val failedCount: Int = 0,
    val skippedCount: Int = 0,
    val currentLabel: String? = null,
    val stopReason: String? = null,
    val cancelled: Boolean = false,
    /** 中止を受け付けたが、まだランナーが停止処理中の状態。 */
    val cancelling: Boolean = false,
) {
    /** 終了したが、失敗・未処理の行が残っている（部分結果）。 */
    val isPartial: Boolean
        get() = !running && total > 0 && doneCount < total
}

/**
 * 出欠一覧の前景自動巡回（プレビュー専用）を仲介する。
 *
 * 巡回の開始はユーザーが画面上で明示的にボタンを押したときだけ。
 * ユーザーが中止したとき・画面を離れたとき・WebView が破棄されたときは
 * [ClassAttendanceTourRunner] へ中止を伝える。
 *
 * 読み取った内容はプレビューとして画面に出すだけで、Room へは保存しない。
 * 自動統合・定期同期も行わない。
 */
@HiltViewModel
class ClassAttendanceTourViewModel @Inject constructor(
    private val repository: ClassAttendanceDetailImportRepository,
    private val syncRepository: org.cabetus.data.classweb.ClassAttendanceSyncRepository,
    private val timetable: org.cabetus.data.local.TimetableDao,
    private val settings: org.cabetus.data.settings.SettingsRepository,
) : ViewModel() {

    data class MappingChoice(val official: String, val local: String, val label: String, val year: Int)
    val mappingChoices = MutableStateFlow<List<MappingChoice>>(emptyList())
    val mappingMessage = MutableStateFlow<String?>(null)

    fun confirmMapping(choice: MappingChoice) {
        viewModelScope.launch {
            try {
                require(timetable.getAll().any { it.courseCode == choice.local })
                settings.saveClassAttendanceMapping(choice.year,choice.official,choice.local)
                mappingChoices.value = mappingChoices.value - choice
                mappingMessage.value = "科目の対応を保存しました。時間割に反映します。"
            } catch (e: CancellationException) { throw e }
              catch (_: Exception) { mappingMessage.value = "対応を保存できません。年度・科目の重複を確認してください。" }
        }
    }

    fun acceptBackgroundResult(state: ClassAttendanceTour.State) {
        viewModelScope.launch { prepareMappings(state) }
    }

    private suspend fun prepareMappings(state: ClassAttendanceTour.State) {
        val year = settings.current().academicYear
        val cells = timetable.getAll().groupBy { it.courseCode }
        val mapped = settings.classAttendanceMappings.first()
        mappingChoices.value = state.items.filter { it.status == ClassAttendanceTour.ItemStatus.DONE }.mapNotNull { item ->
            val code = org.cabetus.data.classweb.ClassAttendanceSyncDom.courseCode(item.row.label) ?: return@mapNotNull null
            if (code in cells || mapped.containsKey("$year:$code")) return@mapNotNull null
            // 候補は自動確定しない。正式コードと既存科目をユーザーが明示確認する。
            val normalized = java.text.Normalizer.normalize(item.row.label, java.text.Normalizer.Form.NFKC).replace(Regex("\\s+"), " ").trim()
            val slotPrefix = Regex("^((?:[月火水木金土日][1-7]\\s+)+)").find(normalized)?.value ?: return@mapNotNull null
            val slots = Regex("([月火水木金土日])([1-7])").findAll(slotPrefix).map {
                ("月火水木金土日".indexOf(it.groupValues[1]) + 1) to it.groupValues[2].toInt()
            }.toSet()
            val afterSlots = normalized.removePrefix(slotPrefix)
            val candidates = cells.filter { (local, list) ->
                val name = java.text.Normalizer.normalize(list.first().courseName, java.text.Normalizer.Form.NFKC).replace(Regex("\\s+"), " ").trim()
                local.startsWith("CLASSWEB-") && name.isNotBlank() && afterSlots.startsWith("$name ") &&
                    org.cabetus.data.classweb.ClassAttendanceMappingSlots.matches(list.map { it.dayOfWeek to it.period }.toSet(), slots)
            }
            val entry = candidates.entries.singleOrNull() ?: return@mapNotNull null
            val registered = entry.value.map { it.dayOfWeek to it.period }.toSet()
            val missing = slots - registered
            val note = if (missing.isEmpty()) "" else "（時間割に未登録：" + missing.sortedWith(compareBy({ it.first }, { it.second })).joinToString("・") { (day, period) -> "${"月火水木金土日"[day - 1]}$period" } + "。出欠のみ対応）"
            MappingChoice(code,entry.key,"${entry.value.first().courseName} ← $code$note",year)
        }
    }

    private val _state = MutableStateFlow(ClassAttendanceTourUiState())
    val state: StateFlow<ClassAttendanceTourUiState> = _state.asStateFlow()

    private val _result = MutableStateFlow<ClassAttendanceTour.State?>(null)
    /** 巡回結果（終了状態）。プレビュー表示にだけ使う。 */
    val result: StateFlow<ClassAttendanceTour.State?> = _result.asStateFlow()

    private val cancelRequested = AtomicBoolean(false)
    private val webViewAlive = AtomicBoolean(true)

    /**
     * 詳細 DOM の抽出結果をプレビューへ変換する。
     * [ClassAttendanceTourRunner] の previewLoader から呼ぶ。保存は行わない。
     */
    suspend fun preview(
        detail: ClassAttendanceDetailDomExtractor.ExtractedDetail,
    ): ClassAttendanceImportPreview = repository.preview(detail)

    /**
     * 巡回を開始する。二重起動はしない。
     * [runner] は UI 側で WebView の操作関数を注入して生成する。
     */
    suspend fun syncPreview(row: ClassAttendanceListDomExtractor.Row,
                            detail: ClassAttendanceDetailDomExtractor.ExtractedDetail,
                            context: org.cabetus.data.classweb.ClassAttendanceSyncDom.Context): ClassAttendanceImportPreview =
        syncRepository.save(row, detail, context) { cancelRequested.get() || !webViewAlive.get() }

    fun start(runner: ClassAttendanceTourRunner, retryTransient: Boolean = false) {
        if (_state.value.running) return
        cancelRequested.set(false)
        webViewAlive.set(true)
        _result.value = null
        mappingChoices.value = emptyList()
        mappingMessage.value = null
        _state.value = ClassAttendanceTourUiState(running = true)

        viewModelScope.launch {
            val outcome = runCatching {
                var attempt = 0
                var result: ClassAttendanceTourRunner.Outcome
                do {
                    result = runner.run(
                        onState = { tourState -> publish(tourState, running = true) },
                        shouldCancel = { cancelRequested.get() || !webViewAlive.get() },
                    )
                    val stopped = (result as? ClassAttendanceTourRunner.Outcome.Started)?.state
                    val retry = retryTransient && attempt < 2 && stopped?.phase == ClassAttendanceTour.Phase.STOPPED &&
                        stopped.stopReason?.startsWith("時間内に画面が切り替わりませんでした") == true &&
                        !cancelRequested.get() && webViewAlive.get()
                    if (!retry) break
                    attempt++
                    _state.update { it.copy(running = true, resultVisible = false, notice = "画面応答を再確認しています（再試行 $attempt/2）。保存済み記録は保持します。") }
                    kotlinx.coroutines.delay(1000)
                } while (true)
                result
            }.getOrElse { throwable ->
                // ViewModel の破棄など、呼び出し側のキャンセルは再送出する。
                if (throwable is CancellationException) {
                    if (cancelRequested.get() || !webViewAlive.get()) {
                        _state.update { it.copy(running = false, cancelling = false, resultVisible = true, cancelled = true, stopReason = "出欠更新を中止しました。") }
                        return@launch
                    }
                    throw throwable
                }
                ClassAttendanceTourRunner.Outcome.NotStarted(
                    "出欠一覧の巡回を開始できませんでした。" +
                        (throwable.message?.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: ""),
                )
            }

            when (outcome) {
                is ClassAttendanceTourRunner.Outcome.Started -> {
                    publish(outcome.state, running = false)
                    prepareMappings(outcome.state)
                }

                is ClassAttendanceTourRunner.Outcome.NotStarted -> {
                    _result.value = null
                    _state.update {
                        it.copy(
                            running = false,
                            resultVisible = true,
                            stopReason = outcome.message,
                        )
                    }
                }
            }
        }
    }

    /** 開始条件を満たさないとき（一覧の確認が取れない等）に理由を表示する。 */
    fun onNotStarted(message: String) {
        if (_state.value.running) return
        _result.value = null
        _state.update {
            it.copy(resultVisible = true, stopReason = message)
        }
    }

    /** ユーザー操作で巡回を中止する。途中までの結果は保持する。 */
    fun requestCancel() {
        if (!_state.value.running) return
        if (cancelRequested.compareAndSet(false, true)) {
            _state.update { it.copy(cancelling = true) }
        }
    }

    /** 画面を離れるときに呼ぶ。進行中の巡回を停止させる。 */
    fun onWebViewDetached() {
        webViewAlive.set(false)
        cancelRequested.set(true)
    }

    fun dismissResult() {
        _state.update { it.copy(resultVisible = false) }
        _result.value = null
    }

    private fun publish(tourState: ClassAttendanceTour.State, running: Boolean) {
        _result.value = tourState
        _state.update {
            it.copy(
                phase = tourState.phase,
                running = running && !tourState.isTerminal,
                cancelling = (running && !tourState.isTerminal) && it.cancelling,
                resultVisible = it.resultVisible || tourState.isTerminal,
                total = tourState.total,
                processed = tourState.items.count { item ->
                    item.status == ClassAttendanceTour.ItemStatus.DONE ||
                        item.status == ClassAttendanceTour.ItemStatus.FAILED
                },
                doneCount = tourState.doneCount,
                failedCount = tourState.failedCount,
                skippedCount = tourState.skippedCount,
                currentLabel = tourState.currentItem?.row?.label,
                stopReason = tourState.stopReason,
                cancelled = tourState.cancelled,
            )
        }
    }
}
