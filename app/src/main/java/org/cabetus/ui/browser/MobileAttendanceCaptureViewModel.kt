package org.cabetus.ui.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.cabetus.data.classweb.ClassMobileAttendanceDomExtractor
import org.cabetus.data.classweb.MobileAttendanceRepository
import org.cabetus.data.network.NetworkMonitor
import org.cabetus.data.network.NetworkStatus
import javax.inject.Inject

data class MobileAttendanceCaptureUiState(
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

/**
 * CLASS モバイル出席登録の画面から、明示ボタンまたはブラウザの画面遷移で「出席」の表示だけを読み取り、
 * 時間割と紐付いた分をホーム表示用に保存する。
 *
 * - 読み取りは固定スクリプトのみ。出欠の登録・送信・コード入力は行わない。
 * - 保存するのは時間割と紐付いた「出席完了」だけ（IP ラベル・Cookie・ストレージは読まない）。
 * - 読み取り時刻・対象日付・端末ネットワーク種別を併せて保存し、ホーム側で古さを判定して失効させる。
 *
 * 多重実行の防止と世代管理:
 * - [onExtractionStarted] は読み取りを開始し、その世代トークンを返す（実行中なら null）。
 * - [onEvaluationResult] / [onLocalError] は受け取ったトークンが古ければ何もしない。
 * - [invalidateCapture] は実行中の読み取りを破棄する（直前の成功値は保持、ダイアログなし）。
 * - `silent = true`（ブラウザからの自動読み取り）では成功・失敗のダイアログを出さない。
 *   保存・失効の扱いは手動読み取りと同じ。
 */
@HiltViewModel
class MobileAttendanceCaptureViewModel @Inject constructor(
    private val repository: MobileAttendanceRepository,
    private val networkMonitor: NetworkMonitor,
) : ViewModel() {

    private val _state = MutableStateFlow(MobileAttendanceCaptureUiState())
    val state: StateFlow<MobileAttendanceCaptureUiState> = _state.asStateFlow()

    /** 読み取りの世代。古い結果や古い clear を破棄するために使う。 */
    private var generation = 0L

    /** 実行中の読み取り（保存または失敗の反映まで）。 */
    private var captureJob: Job? = null

    /** リポジトリの clear ジョブ。直列化のため参照を保持する。 */
    private var clearJob: Job? = null

    /** 直近に開始した読み取りがブラウザからの自動（ダイアログを出さない）読み取りか。 */
    private var silent = false

    /**
     * 読み取りの開始を宣言する。実行中なら null を返す。
     *
     * @param silent true の場合は成功・失敗のダイアログを出さない（ブラウザからの自動読み取り）。
     * @return この読み取りの世代トークン。結果の受け渡しに使う。
     */
    fun onExtractionStarted(silent: Boolean = false): Long? {
        if (_state.value.busy) return null
        generation += 1
        val token = generation
        this.silent = silent
        _state.value = MobileAttendanceCaptureUiState(busy = true)
        // 自動更新の開始だけでは直前の成功を消さない。失敗が確認できた場合は破棄する。
        if (!silent) requestClear(token)
        return token
    }

    fun onLocalError(message: String, token: Long = generation) {
        if (token != generation) return
        _state.update {
            if (silent) {
                it.copy(busy = false, message = null, error = null)
            } else {
                it.copy(busy = false, message = null, error = message)
            }
        }
        // 読み取りに失敗したら、以前の読み取り結果を使い続けない。
        requestClear(token)
    }

    /**
     * 抽出スクリプトの結果を受け取る。読み取り時点の端末ネットワーク状態も併せて保存する。
     * トークンが古い（新しい読み取りや [invalidateCapture] が始まっている）場合は何もしない。
     */
    fun onEvaluationResult(raw: String?, token: Long = generation) {
        if (token != generation) return
        val parsed = ClassMobileAttendanceDomExtractor.parseEvaluationResult(raw)
        val observation = parsed.observation
        if (parsed.error != null || observation == null) {
            val errorText = buildString {
                append(
                    (parsed.error ?: ClassMobileAttendanceDomExtractor.ErrorCode.INVALID_RESULT)
                        .userMessage,
                )
                parsed.warnings.forEach { warning -> append("\n・$warning") }
            }.trim()
            _state.update {
                if (silent) {
                    it.copy(busy = false, message = null, error = null)
                } else {
                    it.copy(busy = false, message = null, error = errorText)
                }
            }
            // 解析に失敗したら、以前の読み取り結果を使い続けない。
            requestClear(token)
            return
        }

        // 開始時に予約された clear の完了を待ってから保存する（消去と保存を直列化する）。
        val pendingClear = clearJob
        captureJob = viewModelScope.launch {
            pendingClear?.join()
            if (generation != token) return@launch
            // 読み取り時点の端末ネットワーク種別。取得できない場合は「不明」として保存し、
            // ホーム側では失効させる（推測で学内/学外を決めない）。
            val networkStatus = kotlinx.coroutines.withTimeoutOrNull(NETWORK_TIMEOUT_MS) {
                networkMonitor.status.first()
            } ?: NetworkStatus.Unknown
            if (generation != token) return@launch

            val result = try {
                repository.capture(observation, networkStatus) { generation == token }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                if (generation != token) return@launch
                _state.update {
                    if (silent) {
                        it.copy(busy = false, message = null, error = null)
                    } else {
                        it.copy(busy = false, message = null, error = SAVE_FAILED_MESSAGE)
                    }
                }
                // 保存に失敗したら、以前の読み取り結果を残さない。
                clearRepositoryQuietly()
                return@launch
            }
            if (generation != token) return@launch

            _state.update {
                if (silent) {
                    it.copy(busy = false, message = null, error = null)
                } else {
                    it.copy(
                        busy = false,
                        message = buildString {
                            append(result.message)
                            (parsed.warnings + observation.warnings)
                                .distinct()
                                .forEach { warning -> append("\n・$warning") }
                        },
                        error = null,
                    )
                }
            }
            // 保持できなかった場合も、前回の読み取り結果を残さない。
            if (!result.saved) clearRepositoryQuietly()
        }
    }

    /**
     * 実行中の読み取りを破棄する。保存済みの成功とダイアログは引き継がない（成功値は保持）。
     * ブラウザの画面遷移などで読み取り対象が変わったときに呼ぶ。
     */
    fun invalidateCapture() {
        generation += 1
        captureJob?.cancel()
        clearJob?.cancel()
        _state.value = MobileAttendanceCaptureUiState()
        // ナビゲーションは読取失敗の証拠ではない。直前の成功は通常の期限・ネットワーク判定に任せる。
    }

    /** 授業一覧が空になったときはホーム用の一時結果だけを破棄する。 */
    fun clearLiveCapture() {
        invalidateCapture()
        requestClear(generation)
    }

    fun dismissDialog() {
        _state.update { it.copy(message = null, error = null) }
    }

    /**
     * 先行する読み取り・clear を止めてから、指定世代の clear を 1 本だけ予約する。
     *
     * clear は必ず直列に実行し、完了後も世代が変わっていれば何もしない
     * （古い clear が新しい読み取り結果を消さないようにする）。
     */
    private fun requestClear(token: Long) {
        val previousCapture = captureJob
        val previousClear = clearJob
        previousCapture?.cancel()
        previousClear?.cancel()
        clearJob = viewModelScope.launch {
            previousCapture?.join()
            previousClear?.join()
            if (generation != token) return@launch
            clearRepositoryQuietly()
        }
    }

    /** リポジトリの clear。失敗しても読み取りは続行するが、キャンセルは握りつぶさない。 */
    private suspend fun clearRepositoryQuietly() {
        try {
            repository.clear()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // 消去に失敗しても致命的ではない（次の読み取り・失効で上書きされる）。
        }
    }

    private companion object {
        /** ネットワーク状態の取得待ち時間。取得できなければ「不明」として扱う。 */
        const val NETWORK_TIMEOUT_MS = 1_500L

        const val SAVE_FAILED_MESSAGE = "出席表示の保存に失敗しました。時間割は変更していません。"
    }
}
