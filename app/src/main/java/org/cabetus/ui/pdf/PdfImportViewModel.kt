package org.cabetus.ui.pdf

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.cabetus.data.pdf.AttendancePreview
import org.cabetus.data.pdf.PdfRepository
import org.cabetus.data.pdf.TimetablePreview
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PdfImportUiState(
    val timetablePreview: TimetablePreview? = null,
    val attendancePreview: AttendancePreview? = null,
    val timetableSaved: Boolean = false,
    val attendanceSaved: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class PdfImportViewModel @Inject constructor(
    private val pdfRepository: PdfRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(PdfImportUiState())
    val state: StateFlow<PdfImportUiState> = _state.asStateFlow()

    fun onTimetablePicked(uri: Uri) {
        _state.update { it.copy(busy = true, error = null, timetableSaved = false) }
        viewModelScope.launch {
            runCatching { pdfRepository.parseTimetable(uri) }
                .onSuccess { preview ->
                    _state.update { it.copy(busy = false, timetablePreview = preview) }
                }
                .onFailure { e ->
                    _state.update { it.copy(busy = false, error = "時間割PDFの読み取りに失敗: ${e.message}") }
                }
        }
    }

    fun onAttendancePicked(uri: Uri) {
        _state.update { it.copy(busy = true, error = null, attendanceSaved = false) }
        viewModelScope.launch {
            runCatching { pdfRepository.parseAttendance(uri) }
                .onSuccess { preview ->
                    _state.update { it.copy(busy = false, attendancePreview = preview) }
                }
                .onFailure { e ->
                    _state.update { it.copy(busy = false, error = "出欠PDFの読み取りに失敗: ${e.message}") }
                }
        }
    }

    fun confirmTimetable() {
        val preview = _state.value.timetablePreview ?: return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            runCatching { pdfRepository.saveTimetable(preview.result) }
                .onSuccess {
                    _state.update {
                        it.copy(busy = false, timetableSaved = true, timetablePreview = null)
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(busy = false, error = "保存に失敗: ${e.message}") }
                }
        }
    }

    fun confirmAttendance() {
        val preview = _state.value.attendancePreview ?: return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            runCatching { pdfRepository.saveAttendance(preview.result) }
                .onSuccess {
                    _state.update {
                        it.copy(busy = false, attendanceSaved = true, attendancePreview = null)
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(busy = false, error = "保存に失敗: ${e.message}") }
                }
        }
    }
}
