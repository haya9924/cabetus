package org.cabetus.ui.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.cabetus.data.classweb.ClassTimetableDomExtractor
import org.cabetus.data.classweb.ClassTimetableImportRepository
import org.cabetus.data.classweb.ClassTimetablePreview
import org.cabetus.data.classweb.ClassTimetableSlot
import javax.inject.Inject

data class ClassTimetableImportUiState(
    val busy: Boolean = false,
    val preview: ClassTimetablePreview? = null,
    val message: String? = null,
    val error: String? = null,
)

/**
 * アプリ内ブラウザで開いている CLASS 時間割画面からの取り込みを仲介する。
 *
 * WebView の DOM 読み取りは画面側が固定スクリプトを evaluateJavascript で実行し、
 * その結果文字列だけを [onEvaluationResult] に渡す。ここでは Android ブリッジを公開しない。
 */
@HiltViewModel
class ClassTimetableImportViewModel @Inject constructor(
    private val repository: ClassTimetableImportRepository,
) : ViewModel() {

    private fun ClassTimetableDomExtractor.Slot.toImportSlot() = ClassTimetableSlot(
        dayOfWeek = dayOfWeek,
        period = period,
        courseName = courseName,
        courseCode = courseCode,
        room = room,
    )

    private val _state = MutableStateFlow(ClassTimetableImportUiState())
    val state: StateFlow<ClassTimetableImportUiState> = _state.asStateFlow()

    fun onExtractionStarted() {
        _state.value = ClassTimetableImportUiState(busy = true)
    }

    fun onLocalError(message: String) {
        _state.update { it.copy(busy = false, preview = null, error = message) }
    }

    fun onEvaluationResult(raw: String?) {
        val parsed = ClassTimetableDomExtractor.parseEvaluationResult(raw)
        if (parsed.error != null) {
            _state.update {
                it.copy(
                    busy = false,
                    preview = null,
                    error = buildString {
                        append(parsed.error.userMessage)
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
            val slots = parsed.slots.map { it.toImportSlot() }
            runCatching { repository.preview(slots) }
                .onSuccess { preview ->
                    val warnings = (parsed.warnings + preview.warnings).distinct()
                    _state.update {
                        it.copy(
                            busy = false,
                            preview = preview.copy(warnings = warnings),
                            error = null,
                        )
                    }
                }
                .onFailure {
                    _state.update {
                        it.copy(
                            busy = false,
                            preview = null,
                            error = "取り込み内容の確認に失敗しました。既存データは変更していません。",
                        )
                    }
                }
        }
    }

    fun cancelPreview() {
        if (_state.value.busy) return
        _state.update { it.copy(preview = null) }
    }

    fun confirmSave() {
        val preview = _state.value.preview ?: return
        if (_state.value.busy) return
        if (preview.blocked) {
            _state.update {
                it.copy(
                    busy = false,
                    preview = null,
                    error = preview.blockedReason
                        ?: "年度・学期を分離できないため、取り込みを中止しました。",
                )
            }
            return
        }
        _state.update { it.copy(busy = true) }

        viewModelScope.launch {
            val slots = preview.slots.map {
                ClassTimetableSlot(
                    it.dayOfWeek,
                    it.period,
                    it.courseName,
                    it.courseCode,
                    it.room,
                )
            }
            runCatching { repository.save(slots) }
                .onSuccess { result ->
                    if (result.blocked) {
                        _state.update {
                            it.copy(
                                busy = false,
                                preview = null,
                                error = result.blockedReason
                                    ?: "年度・学期を分離できないため、取り込みを中止しました。",
                            )
                        }
                        return@onSuccess
                    }
                    val details = buildString {
                        append("新規 ${result.insertedCells} コマを追加しました。")
                        if (result.officialCodeCellCount > 0) {
                            append("\n・正式な科目コードを確認: ${result.officialCodeCellCount} コマ")
                        }
                        if (result.unknownCodeCellCount > 0) {
                            append("\n・仮コードで保存: ${result.unknownCodeCellCount} コマ")
                        }
                        if (result.unknownRoomCellCount > 0) {
                            append("\n・教室を確認できず空欄: ${result.unknownRoomCellCount} コマ")
                        }
                        if (result.duplicateCells > 0) {
                            append("\n・重複のためスキップ: ${result.duplicateCells} コマ")
                        }
                        if (result.sameSlotDifferentCourses > 0) {
                            append(
                                "\n・同じコマに別科目があるためスキップ: " +
                                    "${result.sameSlotDifferentCourses} コマ",
                            )
                        }
                        if (result.skippedAmbiguous > 0) {
                            append("\n・特定できないためスキップ: ${result.skippedAmbiguous} コマ")
                        }
                        if (result.skippedCodeConflictCount > 0) {
                            append(
                                "\n・科目コードが既存科目と競合するためスキップ: " +
                                    "${result.skippedCodeConflictCount} コマ",
                            )
                        }
                        if (result.insertedCells == 0) {
                            append("\n既存データに追加されたコマはありません。")
                        }
                    }
                    _state.update {
                        it.copy(busy = false, preview = null, message = details, error = null)
                    }
                }
                .onFailure {
                    _state.update {
                        it.copy(
                            busy = false,
                            error = "保存に失敗しました。既存データは変更していません。",
                        )
                    }
                }
        }
    }

    fun dismissDialog() {
        _state.update { it.copy(message = null, error = null) }
    }
}
