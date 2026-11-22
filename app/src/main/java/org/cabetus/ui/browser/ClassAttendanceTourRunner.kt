package org.cabetus.ui.browser

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.cabetus.data.classweb.ClassAttendanceDetailDomExtractor
import org.cabetus.data.classweb.ClassAttendanceImportPreview
import org.cabetus.data.classweb.ClassAttendanceListDomExtractor
import org.cabetus.data.classweb.ClassAttendanceTour
import org.cabetus.data.classweb.asProvisional
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CLASS の出欠一覧を、ユーザーが明示的に開始したときだけ前景で巡回するランナー。
 *
 * 1行ごとに次の固定手順だけを実行する。
 * 1. 一覧だけが表示されていることを DOM で確認し、既存リンク（`a.ui-commandlink`）をクリックする
 * 2. クリック後、**一覧が消えて詳細 DOM に切り替わるまで実際の DOM を待つ**
 *    （同じ URL のまま非同期に画面が入れ替わるため、クリック直後の古い DOM を読まない）
 * 3. 詳細テーブルを [ClassAttendanceDetailDomExtractor] で読み取る
 * 4. 詳細画面の「戻る」ボタンをクリックする
 * 5. **詳細が消えて一覧だけに戻るまで待ち**、件数・並び・リンク先が開始時と同じか確認する
 *
 * 安全上の制約:
 * - 出欠登録など副作用のあるボタンは押さない（「戻る」だけを文字完全一致で押す）
 * - 一覧の行数・並び・リンク先が変わったら停止する（別の科目を読まない）
 * - 一覧以外の画面（時間割・出欠詳細・別画面の `ul[id$=_list]`）では巡回を開始しない
 * - 想定外の DOM・ページ移動・タイムアウト・ユーザー中止・WebView 破棄で停止する
 * - 一覧の件数は [ClassAttendanceListDomExtractor.MAX_ROWS] を上限にし、無制限ループにしない
 * - ページ送り（ページネーション）は未確認のため、現在表示中の一覧だけを対象にする
 * - 読み取った内容はプレビューにだけ使い、保存・自動統合は行わない
 *
 * WebView の操作は呼び出し側から注入した関数で行い、このクラスは Android に依存しない。
 * `evaluateJavascript` は UI スレッドから呼ばれる前提（[enqueue] を参照）。
 */
class ClassAttendanceTourRunner(
    /** 固定スクリプトを実行し、結果文字列をコールバックへ渡す。 */
    private val enqueue: (script: String, callback: (String?) -> Unit) -> Unit,
    private val currentUrl: () -> String?,
    private val isPageLoading: () -> Boolean,
    private val isAlive: () -> Boolean = { true },
    private val previewLoader: suspend (ClassAttendanceDetailDomExtractor.ExtractedDetail) ->
        ClassAttendanceImportPreview,
    private val syncPreviewLoader: (suspend (ClassAttendanceListDomExtractor.Row, ClassAttendanceDetailDomExtractor.ExtractedDetail, org.cabetus.data.classweb.ClassAttendanceSyncDom.Context) -> ClassAttendanceImportPreview)? = null,
    private val stepTimeoutMs: Long = DEFAULT_STEP_TIMEOUT_MS,
    private val totalTimeoutMs: Long = DEFAULT_TOTAL_TIMEOUT_MS,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
) {

    sealed interface Outcome {
        data class Started(val state: ClassAttendanceTour.State) : Outcome

        /** 一覧を取得できず、巡回を開始しなかった。 */
        data class NotStarted(val message: String) : Outcome
    }

    /** 1回の確認結果。Retry は DOM 更新待ちとして再試行する。 */
    private sealed interface Attempt<out T> {
        data class Ready<T>(val value: T) : Attempt<T>
        data object Retry : Attempt<Nothing>
        data class Failed(val message: String) : Attempt<Nothing>
    }

    private sealed interface Step<out T> {
        data class Ready<T>(val value: T) : Step<T>
        data class Failed(val message: String) : Step<Nothing>
        data object Cancelled : Step<Nothing>
    }

    /** `evaluateJavascript` 1 回分の結果。タイムアウトと中止を区別する。 */
    private sealed interface Evaluation {
        data class Value(val raw: String?) : Evaluation
        data object Cancelled : Evaluation
        data object TimedOut : Evaluation
    }

    /**
     * 一覧を読み取り、巡回を最後まで実行する。
     * 途中で停止した場合（失敗・タイムアウト・ユーザー中止・WebView 破棄）も、
     * それまでに読み取れた行は [ClassAttendanceTour.State] に部分結果として残して返す。
     * 読み取った内容の保存・自動統合は行わない。
     */
    suspend fun run(
        onState: (ClassAttendanceTour.State) -> Unit,
        shouldCancel: () -> Boolean,
    ): Outcome {
        val rosterStep = poll(shouldCancel) { readRosterAttempt(initial = true, shouldCancel) }
        val roster = when (rosterStep) {
            is Step.Ready -> rosterStep.value
            is Step.Failed -> return Outcome.NotStarted(rosterStep.message)
            Step.Cancelled -> return Outcome.NotStarted(CANCELLED_MESSAGE)
        }

        var syncContext: org.cabetus.data.classweb.ClassAttendanceSyncDom.Context? = null
        if (syncPreviewLoader != null) {
            val evaluated = evaluateRaw(org.cabetus.data.classweb.ClassAttendanceSyncDom.SCRIPT, shouldCancel)
            syncContext = (evaluated as? Evaluation.Value)?.raw?.let(org.cabetus.data.classweb.ClassAttendanceSyncDom::parse)
                ?: return Outcome.NotStarted("年度・学期または全件表示を確認できません。ページ送りのある一覧は未対応です。")
            val codes = roster.map { org.cabetus.data.classweb.ClassAttendanceSyncDom.courseCode(it.label) }
            if (codes.any { it == null } || codes.distinct().size != codes.size) return Outcome.NotStarted("科目コードを一意に確認できません。")
        }
        baselineContext = syncContext
        val initial = ClassAttendanceTour.start(roster)
            ?: return Outcome.NotStarted(
                ClassAttendanceListDomExtractor.ErrorCode.LIST_ITEM_MISMATCH.userMessage,
            )
        onState(initial)

        // 途中で停止しても部分結果を失わないよう、常に最新の状態を保持する。
        var latest = initial
        var cancelledHere = false
        val finished = try {
            withTimeoutOrNull(totalTimeoutMs) {
                while (!latest.isTerminal) {
                    if (shouldCancel()) {
                        cancelledHere = true
                        latest = cancelState(latest)
                        onState(latest)
                        break
                    }
                    latest = advance(latest, onState, shouldCancel)
                }
                latest
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // ViewModel 破棄などで呼び出し側がキャンセルした場合も、結果を残してから終了する。
            val stopped = destroyedState(latest)
            onState(stopped)
            throw cancelled
        }
        if (cancelledHere) {
            // ユーザー中止でループを抜けた場合。制限時間と同時に中止した場合も中止を優先する。
            if (!latest.cancelled) {
                latest = cancelState(latest)
                onState(latest)
            }
        } else if (finished == null) {
            latest = ClassAttendanceTour.stop(
                latest,
                TOTAL_TIMEOUT_MESSAGE,
                markCurrentFailed = false,
            )
            onState(latest)
        } else {
            latest = finished
        }
        return Outcome.Started(latest)
    }

    private var lastProbe = "未確認"
    private var baselineContext: org.cabetus.data.classweb.ClassAttendanceSyncDom.Context? = null

    private suspend fun advance(
        state: ClassAttendanceTour.State,
        onState: (ClassAttendanceTour.State) -> Unit,
        shouldCancel: () -> Boolean,
    ): ClassAttendanceTour.State {
        fun update(next: ClassAttendanceTour.State): ClassAttendanceTour.State {
            onState(next)
            return next
        }

        fun stop(reason: String, failed: Boolean = true): ClassAttendanceTour.State =
            if (reason == WEBVIEW_DESTROYED_MESSAGE) {
                update(destroyedState(state))
            } else {
                update(ClassAttendanceTour.stop(state, reason, markCurrentFailed = failed))
            }

        fun cancel(): ClassAttendanceTour.State = update(cancelState(state))

        return when (state.phase) {
            ClassAttendanceTour.Phase.CLICK_ROW -> {
                val item = state.currentItem ?: return stop(UNEXPECTED_STATE_MESSAGE)
                val script = runCatching {
                    ClassAttendanceListDomExtractor.buildClickScript(item.row, state.total)
                }.getOrNull() ?: return stop(UNEXPECTED_STATE_MESSAGE)

                when (val clicked = evaluateAction(script, shouldCancel)) {
                    is Step.Ready -> verifyDetail(
                        update(ClassAttendanceTour.onRowClicked(state)),
                        onState,
                        shouldCancel,
                    )
                    is Step.Failed -> stop(clicked.message)
                    Step.Cancelled -> cancel()
                }
            }

            ClassAttendanceTour.Phase.VERIFY_DETAIL ->
                verifyDetail(state, onState, shouldCancel)

            ClassAttendanceTour.Phase.EXTRACT_DETAIL -> {
                when (val extracted = evaluateDetail(shouldCancel)) {
                    is Step.Ready -> {
                        if (shouldCancel()) {
                            cancel()
                        } else {
                            val identity = evaluateIdentity(
                                state.currentItem?.row?.label,
                                shouldCancel,
                            )
                            when (identity) {
                                is Step.Failed -> stop(identity.message)
                                Step.Cancelled -> cancel()
                                is Step.Ready -> {
                                    val preview = try {
                                        if (syncPreviewLoader != null) {
                                            val row = state.currentItem!!.row
                                            val code = org.cabetus.data.classweb.ClassAttendanceSyncDom.courseCode(row.label)
                                            val evaluated = evaluateRaw(org.cabetus.data.classweb.ClassAttendanceSyncDom.SCRIPT, shouldCancel)
                                            val context = (evaluated as? Evaluation.Value)?.raw?.let(org.cabetus.data.classweb.ClassAttendanceSyncDom::parse)
                                            require(context != null && context.year == baselineContext?.year && context.term == baselineContext?.term && context.codes == listOf(code))
                                            syncPreviewLoader.invoke(row, extracted.value, context)
                                        } else previewLoader(extracted.value)
                                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                      catch (_: Exception) { null }
                                    if (preview == null) {
                                        if (syncPreviewLoader != null) update(ClassAttendanceTour.onDetailFailed(state, "科目・年度・日付・時限を検証できないため保存しませんでした。"))
                                        else stop("出欠内容の読み取り結果を検証できませんでした。")
                                    } else {
                                        update(
                                            ClassAttendanceTour.onDetailExtracted(
                                                state,
                                                if (identity.value || syncPreviewLoader != null) preview else preview.asProvisional(),
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    is Step.Failed -> stop(extracted.message)
                    Step.Cancelled -> cancel()
                }
            }

            ClassAttendanceTour.Phase.CLICK_BACK -> {
                when (
                    val back = evaluateAction(
                        ClassAttendanceListDomExtractor.BACK_SCRIPT,
                        shouldCancel,
                    )
                ) {
                    is Step.Ready -> update(ClassAttendanceTour.onBackClicked(state))
                    is Step.Failed -> stop(back.message)
                    Step.Cancelled -> cancel()
                }
            }

            ClassAttendanceTour.Phase.VERIFY_LIST -> {
                when (
                    val listStep = poll(shouldCancel) {
                        readRosterAttempt(initial = false, shouldCancel)
                    }
                ) {
                    is Step.Ready -> {
                        if (syncPreviewLoader != null) {
                            val evaluated = evaluateRaw(org.cabetus.data.classweb.ClassAttendanceSyncDom.SCRIPT, shouldCancel)
                            val context = (evaluated as? Evaluation.Value)?.raw?.let(org.cabetus.data.classweb.ClassAttendanceSyncDom::parse)
                            if (context == null || context.year != baselineContext?.year || context.term != baselineContext?.term) return stop("年度・学期が変更されたため停止しました。")
                        }
                        update(ClassAttendanceTour.onListReverified(state, listStep.value))
                    }
                    is Step.Failed -> stop(listStep.message)
                    Step.Cancelled -> cancel()
                }
            }

            ClassAttendanceTour.Phase.COMPLETE,
            ClassAttendanceTour.Phase.STOPPED,
            -> state
        }
    }

    /**
     * 詳細 DOM へ実際に切り替わるまで待つ。
     *
     * クリック直後は一覧 DOM がまだ残っていることがあるため、
     * (1) 一覧が表示されていないこと (2) 出欠詳細テーブルが表示されていること
     * (3) 詳細スクリプトの検証に成功すること、のすべてを満たしたときだけ進む。
     */
    private suspend fun verifyDetail(
        state: ClassAttendanceTour.State,
        onState: (ClassAttendanceTour.State) -> Unit,
        shouldCancel: () -> Boolean,
    ): ClassAttendanceTour.State {
        val detailStep = poll(shouldCancel) { verifyDetailAttempt(shouldCancel) }
        return when (detailStep) {
            is Step.Ready -> {
                val next = ClassAttendanceTour.onDetailVerified(state)
                onState(next)
                next
            }
            is Step.Failed -> {
                val next = ClassAttendanceTour.stop(state, detailStep.message)
                onState(next)
                next
            }
            Step.Cancelled -> {
                val next = cancelState(state)
                onState(next)
                next
            }
        }
    }

    /**
     * 中止として停止する。ユーザー操作と WebView 破棄を区別して理由を残す。
     * どちらも処理中の行は失敗扱いにせず、読み取り済みの部分結果を保持する。
     */
    private fun cancelState(state: ClassAttendanceTour.State): ClassAttendanceTour.State =
        if (isAlive()) {
            ClassAttendanceTour.cancel(state, CANCELLED_MESSAGE)
        } else {
            destroyedState(state)
        }

    /** WebView 破棄による停止。行の失敗ではなく、未処理として残す。 */
    private fun destroyedState(state: ClassAttendanceTour.State): ClassAttendanceTour.State =
        ClassAttendanceTour.stop(
            state,
            WEBVIEW_DESTROYED_MESSAGE,
            markCurrentFailed = false,
        )

    private suspend fun verifyDetailAttempt(shouldCancel: () -> Boolean): Attempt<Unit> {
        // 一覧と詳細が同一 document 内で入れ替わるため、まず表示状態を確認する。
        // 一覧がまだ残っている間は、詳細テーブルを読まない（古い DOM の誤読を防ぐ）。
        val screen = readScreenState(shouldCancel)
        when (screen) {
            is Attempt.Failed -> return screen
            Attempt.Retry -> return Attempt.Retry
            is Attempt.Ready -> {
                if (!screen.value.isDetailOnly) return Attempt.Retry
            }
        }

        when (val detail = evaluateDetailVerification(shouldCancel)) {
            is Attempt.Failed -> return detail
            Attempt.Retry -> return Attempt.Retry
            is Attempt.Ready -> Unit
        }
        return evaluateBackVerification(shouldCancel)
    }

    /**
     * 一覧だけが表示され、行の並びが読める状態になるまで待つ。
     *
     * [initial] が true のときは巡回の開始前なので、詳細が表示されたままなら
     * タイムアウトまで待たずに理由を返す。false のときは「戻る」直後の遷移中で
     * 詳細が残っている可能性があるため、一覧に戻るまで待つ。
     */
    private suspend fun readRosterAttempt(
        initial: Boolean,
        shouldCancel: () -> Boolean,
    ): Attempt<List<ClassAttendanceListDomExtractor.Row>> {
        val screen = readScreenState(shouldCancel)
        when (screen) {
            is Attempt.Failed -> {
                // URL・ホストの不一致は待っても直らないため即座に失敗する。
                return screen
            }
            Attempt.Retry -> return Attempt.Retry
            is Attempt.Ready -> {
                val state = screen.value
                when {
                    state.isListOnly -> Unit
                    // 開始時に詳細が表示されている（または両方見えている）なら、
                    // 一覧に戻るまで待たずに理由を返す。
                    initial && state.detailPresent ->
                        return Attempt.Failed(
                            ClassAttendanceListDomExtractor.ErrorCode.DETAIL_VISIBLE.userMessage,
                        )
                    initial && state.listError != null &&
                        state.listError in FATAL_LIST_CODES ->
                        return Attempt.Failed(state.listError.userMessage)
                    // 遷移中（一覧も詳細も無い / 両方ある）は再試行する。
                    else -> return Attempt.Retry
                }
            }
        }

        val listRaw = evaluateRaw(ClassAttendanceListDomExtractor.LIST_SCRIPT, shouldCancel)
        val raw = when (listRaw) {
            is Evaluation.Value -> listRaw.raw
            Evaluation.Cancelled -> return Attempt.Retry
            Evaluation.TimedOut -> return Attempt.Retry
        }
        val roster = ClassAttendanceListDomExtractor.parseListResult(raw)
        lastProbe = "一覧解析=${roster.error ?: "正常"}"
        if (roster.error == null) return Attempt.Ready(roster.rows)
        return if (roster.error in FATAL_LIST_CODES && initial) {
            Attempt.Failed(roster.error.userMessage)
        } else {
            Attempt.Retry
        }
    }

    /**
     * 一覧・詳細の表示状態を取得する。
     * URL が CLASS の完全一致ホストでなくなった場合は即座に失敗する。
     */
    private suspend fun readScreenState(
        shouldCancel: () -> Boolean,
    ): Attempt<ClassAttendanceListDomExtractor.ScreenState> {
        if (!BrowserUrlPolicy.isClassConsole(currentUrl())) {
            return Attempt.Failed(
                ClassAttendanceListDomExtractor.ErrorCode.UNEXPECTED_PAGE.userMessage,
            )
        }
        val raw = evaluateRaw(
            ClassAttendanceListDomExtractor.SCREEN_STATE_SCRIPT,
            shouldCancel,
        )
        val value = when (raw) {
            is Evaluation.Value -> raw.raw
            Evaluation.Cancelled -> return Attempt.Retry
            Evaluation.TimedOut -> return Attempt.Retry
        }
        val screen = ClassAttendanceListDomExtractor.parseScreenState(value)
        lastProbe = "一覧=${screen.listReady} 詳細=${screen.detailPresent} 判定=${screen.error ?: screen.listError}"
        if (!screen.isValid) {
            val error = screen.error ?: ClassAttendanceListDomExtractor.ErrorCode.INVALID_RESULT
            return if (error == ClassAttendanceListDomExtractor.ErrorCode.UNEXPECTED_PAGE) {
                Attempt.Failed(error.userMessage)
            } else {
                Attempt.Retry
            }
        }
        return Attempt.Ready(screen)
    }

    private suspend fun evaluateDetailVerification(
        shouldCancel: () -> Boolean,
    ): Attempt<Unit> {
        val raw = when (
            val evaluated = evaluateRaw(
                ClassAttendanceDetailDomExtractor.VERIFY_SCRIPT,
                shouldCancel,
            )
        ) {
            is Evaluation.Value -> evaluated.raw
            Evaluation.Cancelled -> return Attempt.Retry
            Evaluation.TimedOut -> return Attempt.Retry
        }
        val code = ClassAttendanceDetailDomExtractor.parseVerificationResult(raw)
        return when {
            code == null -> Attempt.Ready(Unit)
            code in FATAL_DETAIL_CODES -> Attempt.Failed(code.userMessage)
            else -> Attempt.Retry
        }
    }

    private suspend fun evaluateBackVerification(
        shouldCancel: () -> Boolean,
    ): Attempt<Unit> {
        val raw = when (
            val evaluated = evaluateRaw(
                ClassAttendanceListDomExtractor.BACK_VERIFY_SCRIPT,
                shouldCancel,
            )
        ) {
            is Evaluation.Value -> evaluated.raw
            Evaluation.Cancelled -> return Attempt.Retry
            Evaluation.TimedOut -> return Attempt.Retry
        }
        val code = ClassAttendanceListDomExtractor.parseActionResult(raw)
        return when {
            code == null -> Attempt.Ready(Unit)
            code == ClassAttendanceListDomExtractor.ErrorCode.BACK_AMBIGUOUS ->
                Attempt.Failed(code.userMessage)
            code == ClassAttendanceListDomExtractor.ErrorCode.UNEXPECTED_PAGE ->
                Attempt.Failed(code.userMessage)
            else -> Attempt.Retry
        }
    }

    private suspend fun evaluateDetail(
        shouldCancel: () -> Boolean,
    ): Step<ClassAttendanceDetailDomExtractor.ExtractedDetail> {
        val raw = when (
            val evaluated = evaluateRaw(
                ClassAttendanceDetailDomExtractor.SCRIPT,
                shouldCancel,
            )
        ) {
            is Evaluation.Value -> evaluated.raw
            Evaluation.Cancelled -> return Step.Cancelled
            Evaluation.TimedOut -> return Step.Failed(
                ClassAttendanceDetailDomExtractor.ErrorCode.SCRIPT_FAILED.userMessage,
            )
        } ?: return Step.Failed(
            ClassAttendanceDetailDomExtractor.ErrorCode.SCRIPT_FAILED.userMessage,
        )
        val parsed = ClassAttendanceDetailDomExtractor.parseEvaluationResult(raw)
        val detail = parsed.detail
        if (parsed.error != null || detail == null) {
            return Step.Failed(
                (parsed.error ?: ClassAttendanceDetailDomExtractor.ErrorCode.INVALID_RESULT)
                    .userMessage,
            )
        }
        return Step.Ready(detail)
    }

    /**
     * 表示中の詳細が、今開いた一覧の行の科目と一致するかを確認する。
     * 一致を確認できなければ（false）、抽出結果は暫定として扱う。
     * URL・ホストが変わった場合だけは、別画面の内容を読まないよう停止する。
     */
    private suspend fun evaluateIdentity(
        rowLabel: String?,
        shouldCancel: () -> Boolean,
    ): Step<Boolean> {
        if (rowLabel.isNullOrBlank()) return Step.Ready(false)
        val script = runCatching {
            ClassAttendanceDetailDomExtractor.buildIdentityScript(rowLabel)
        }.getOrNull() ?: return Step.Ready(false)

        val raw = when (val evaluated = evaluateRaw(script, shouldCancel)) {
            is Evaluation.Value -> evaluated.raw
            Evaluation.Cancelled -> return Step.Cancelled
            Evaluation.TimedOut -> return Step.Ready(false)
        } ?: return Step.Ready(false)

        val check = ClassAttendanceDetailDomExtractor.parseIdentityResult(raw)
        if (check.error == ClassAttendanceDetailDomExtractor.ErrorCode.UNEXPECTED_PAGE) {
            return Step.Failed(check.error.userMessage)
        }
        return Step.Ready(check.verified)
    }

    private suspend fun evaluateAction(
        script: String,
        shouldCancel: () -> Boolean,
    ): Step<Unit> {
        val raw = when (val evaluated = evaluateRaw(script, shouldCancel)) {
            is Evaluation.Value -> evaluated.raw
            Evaluation.Cancelled -> return Step.Cancelled
            Evaluation.TimedOut -> return Step.Failed(
                ClassAttendanceListDomExtractor.ErrorCode.SCRIPT_FAILED.userMessage,
            )
        } ?: return Step.Failed(
            ClassAttendanceListDomExtractor.ErrorCode.SCRIPT_FAILED.userMessage,
        )
        val error = ClassAttendanceListDomExtractor.parseActionResult(raw)
        return if (error == null) {
            Step.Ready(Unit)
        } else {
            Step.Failed(error.userMessage)
        }
    }

    /**
     * 固定スクリプトを 1 回実行する。
     * コールバック待ちの間もユーザー中止・WebView 破棄を監視し、
     * ステップのタイムアウトを待たずに終了できるようにする。
     */
    private suspend fun evaluateRaw(
        script: String,
        shouldCancel: () -> Boolean,
    ): Evaluation {
        if (shouldCancel()) return Evaluation.Cancelled
        if (!isAlive()) return Evaluation.Cancelled

        val result = CompletableDeferred<String?>()
        val delivered = AtomicBoolean(false)
        fun deliver(raw: String?) {
            if (!delivered.compareAndSet(false, true)) return
            result.complete(raw)
        }

        try {
            enqueue(script) { raw -> deliver(raw) }
        } catch (t: Throwable) {
            deliver(null)
        }

        val outcome = withTimeoutOrNull(stepTimeoutMs) {
            while (true) {
                if (shouldCancel() || !isAlive()) return@withTimeoutOrNull null
                if (result.isCompleted) return@withTimeoutOrNull result.await()
                delay(pollIntervalMs)
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }
        if (outcome != null || result.isCompleted) {
            // 中止要求と同時に結果が返った場合も、中止を優先する。
            return if (shouldCancel() || !isAlive()) {
                Evaluation.Cancelled
            } else {
                Evaluation.Value(result.await())
            }
        }
        return if (shouldCancel() || !isAlive()) Evaluation.Cancelled else Evaluation.TimedOut
    }

    /**
     * [attempt] が Ready を返すまで、タイムアウトまで繰り返す。
     * ページ移動・WebView 破棄・ユーザー中止は即座に終了する。
     */
    private suspend fun <T> poll(
        shouldCancel: () -> Boolean,
        attempt: suspend () -> Attempt<T>,
    ): Step<T> {
        val result = withTimeoutOrNull(stepTimeoutMs) {
            while (true) {
                if (shouldCancel()) return@withTimeoutOrNull Step.Cancelled
                if (!isAlive()) {
                    return@withTimeoutOrNull Step.Failed(WEBVIEW_DESTROYED_MESSAGE)
                }
                if (!BrowserUrlPolicy.isClassConsole(currentUrl())) {
                    return@withTimeoutOrNull Step.Failed(
                        ClassAttendanceListDomExtractor.ErrorCode.UNEXPECTED_PAGE.userMessage,
                    )
                }
                // JSFの画面切替ではWebViewの読み込みフラグが完了通知なしで残る場合がある。
                // 同期時は各attemptの可視DOM・一覧/詳細排他・科目/年度照合で判定する。
                if (isPageLoading() && syncPreviewLoader == null) {
                    delay(pollIntervalMs)
                    continue
                }
                when (val outcome = attempt()) {
                    is Attempt.Ready -> return@withTimeoutOrNull Step.Ready(outcome.value)
                    is Attempt.Failed -> return@withTimeoutOrNull Step.Failed(outcome.message)
                    Attempt.Retry -> delay(pollIntervalMs)
                }
            }
            @Suppress("UNREACHABLE_CODE")
            Step.Failed(TIMEOUT_MESSAGE)
        }
        // タイムアウト直前にユーザーが中止した場合は中止として扱う。
        if (result == null && shouldCancel()) return Step.Cancelled
        return result ?: Step.Failed(TIMEOUT_MESSAGE)
    }

    companion object {
        const val DEFAULT_STEP_TIMEOUT_MS: Long = 10_000L
        const val DEFAULT_TOTAL_TIMEOUT_MS: Long = 180_000L
        const val DEFAULT_POLL_INTERVAL_MS: Long = 300L

        private const val CANCELLED_MESSAGE = "ユーザー操作で巡回を中止しました。"
        private const val WEBVIEW_DESTROYED_MESSAGE =
            "画面を破棄したため巡回を停止しました。"
        private const val UNEXPECTED_STATE_MESSAGE =
            "巡回の状態を確認できないため停止しました。"
        private val TIMEOUT_MESSAGE =
            ClassAttendanceListDomExtractor.ErrorCode.TIMEOUT.userMessage
        private const val TOTAL_TIMEOUT_MESSAGE =
            "巡回全体の制限時間を超えたため停止しました。途中までの結果だけを表示します。"

        private val FATAL_DETAIL_CODES = setOf(
            ClassAttendanceDetailDomExtractor.ErrorCode.UNEXPECTED_PAGE,
            ClassAttendanceDetailDomExtractor.ErrorCode.HEADER_MISMATCH,
            ClassAttendanceDetailDomExtractor.ErrorCode.UNSUPPORTED_CELL_SPAN,
            ClassAttendanceDetailDomExtractor.ErrorCode.TOO_LARGE,
        )

        private val FATAL_LIST_CODES = setOf(
            ClassAttendanceListDomExtractor.ErrorCode.UNEXPECTED_PAGE,
            ClassAttendanceListDomExtractor.ErrorCode.TOO_LARGE,
            ClassAttendanceListDomExtractor.ErrorCode.MAIN_CONTENT_AMBIGUOUS,
            ClassAttendanceListDomExtractor.ErrorCode.LIST_AMBIGUOUS,
            ClassAttendanceListDomExtractor.ErrorCode.LIST_HEADING_NOT_FOUND,
        )
    }
}
