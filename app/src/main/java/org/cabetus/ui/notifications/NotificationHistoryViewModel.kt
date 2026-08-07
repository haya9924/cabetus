package org.cabetus.ui.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.cabetus.data.local.NotificationHistoryDao
import org.cabetus.data.local.NotificationHistoryEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NotificationHistoryUiState(
    val items: List<NotificationHistoryEntity> = emptyList(),
    val unreadCount: Int = 0,
)

@HiltViewModel
class NotificationHistoryViewModel @Inject constructor(
    private val notificationHistoryDao: NotificationHistoryDao,
) : ViewModel() {

    val uiState: StateFlow<NotificationHistoryUiState> = combine(
        notificationHistoryDao.observeAll(),
        notificationHistoryDao.observeUnreadCount(),
    ) { items, unread ->
        NotificationHistoryUiState(items = items, unreadCount = unread)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NotificationHistoryUiState())

    fun markAllRead() {
        viewModelScope.launch { notificationHistoryDao.markAllRead() }
    }

    fun delete(id: Long) {
        viewModelScope.launch { notificationHistoryDao.delete(id) }
    }

    fun clear() {
        viewModelScope.launch { notificationHistoryDao.clear() }
    }
}
