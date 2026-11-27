package org.cabetus.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.cabetus.data.settings.SettingsRepository
import org.cabetus.ui.browser.ClassAttendanceBackgroundController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val backgroundController: ClassAttendanceBackgroundController,
) : ViewModel() {
    fun markLoggedIn() {
        // ログイン画面（設定 → ログイン）で成立したログインも、要再ログインの抑止を解除する。
        backgroundController.notifyAuthUpdated()
        viewModelScope.launch { settingsRepository.setLoggedIn(true) }
    }
}
