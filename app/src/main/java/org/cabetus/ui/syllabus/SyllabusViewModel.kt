package org.cabetus.ui.syllabus

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.cabetus.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class SyllabusViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
) : ViewModel() {
    val academicYear: StateFlow<Int?> = settingsRepository.settings
        .map { it.academicYear }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}
