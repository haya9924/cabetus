package org.cabetus.ui.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.cabetus.data.classweb.ClassAttendanceDetailDomExtractor
import org.cabetus.data.classweb.ClassAttendanceDetailImportRepository
import org.cabetus.data.classweb.ClassAttendanceImportPreview
import javax.inject.Inject

data class ClassAttendanceDetailImportUiState(
    val busy: Boolean = false,
    val preview: ClassAttendanceImportPreview? = null,
    val error: String? = null,
    val capturedAt: Long? = null,
)

/**
 * アプリ内ブラウザで開いている CLASS の出欠詳細から、明示ボタンで読み取った内容を
 * プレビュー表示する。第一段階では保存機能を持たない（DB へは書き込まない）。
 *
 * 全科目の自動巡回・定期同期には未対応で、表示中の1科目だけを読み取る。
 * WebView の DOM 読み取りは画面側が固定スクリプトを evaluateJavascript で実行し、
 * その結果文字列だけを [onEvaluationResult] に渡す。Android ブリッジは公開しない。
 * 読み取った内容をログやファイルへ書き出さない。
 */
@HiltViewModel
class ClassAttendanceDetailImportViewModel @Inject constructor(
    private val repository: ClassAttendanceDetailImportRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ClassAttendanceDetailImportUiState())
    val state: StateFlow<ClassAttendanceDetailImportUiState> = _state.asStateFlow()

    fun onExtractionStarted() {
        _state.value = ClassAttendanceDetailImportUiState(busy = true)
    }

    fun onLocalError(message: String) {
        _state.update { it.copy(busy = false, preview = null, error = message) }
    }

    fun onEvaluationResult(raw: String?) {
        val parsed = ClassAttendanceDetailDomExtractor.parseEvaluationResult(raw)
        val detail = parsed.detail
        if (parsed.error != null || detail == null) {
            _state.update {
                it.copy(
                    busy = false,
                    preview = null,
                    error = buildString {
                        append(
                            (parsed.error ?: ClassAttendanceDetailDomExtractor.ErrorCode.INVALID_RESULT)
                                .userMessage,
                        )
                        if (parsed.warnings.isNotEmpty()) {
                            append("\n\n")
                            parsed.warnings.forEach { warning -> append("・$warning\n") }
                        }
                    }.trim(),
                )
            }
            return
        }

        viewModelScope.launch {
            runCatching { repository.preview(detail) }
                .onSuccess { preview ->
                    _state.update {
                        it.copy(
                            busy = false,
                            preview = preview,
                            capturedAt = System.currentTimeMillis(),
                            error = null,
                        )
                    }
                }
                .onFailure {
                    _state.update {
                        it.copy(
                            busy = false,
                            preview = null,
                            error = "出欠内容の読み取りに失敗しました。既存データは変更していません。",
                        )
                    }
                }
        }
    }

    fun cancelPreview() {
        if (_state.value.busy) return
        _state.update { it.copy(preview = null) }
    }

    fun dismissDialog() {
        _state.update { it.copy(error = null) }
    }
}
