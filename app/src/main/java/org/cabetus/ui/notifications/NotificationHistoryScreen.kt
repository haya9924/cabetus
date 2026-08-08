package org.cabetus.ui.notifications

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.cabetus.data.local.NotificationHistoryEntity
import org.cabetus.data.local.NotificationType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TIME_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("M月d日（E） HH:mm", Locale.JAPANESE)
        .withZone(ZoneId.of("Asia/Tokyo"))

private fun NotificationType.label(): String = when (this) {
    NotificationType.NEW_ASSIGNMENT -> "新規課題"
    NotificationType.LETUS_CHANGE -> "LETUSの変更"
    NotificationType.DEADLINE -> "課題の期日"
    NotificationType.DAILY_SUMMARY -> "今日のまとめ"
    NotificationType.CLASS_START -> "授業開始"
    NotificationType.ATTENDANCE_REMINDER -> "出席リマインド"
    NotificationType.AUTO_ABSENT -> "自動欠席"
    NotificationType.FETCH_FAILURE -> "取得の失敗"
}

private fun NotificationType.icon(): ImageVector = when (this) {
    NotificationType.NEW_ASSIGNMENT -> Icons.AutoMirrored.Filled.Assignment
    NotificationType.LETUS_CHANGE -> Icons.Filled.Edit
    NotificationType.DEADLINE -> Icons.Filled.Schedule
    NotificationType.DAILY_SUMMARY -> Icons.Filled.Notifications
    NotificationType.CLASS_START -> Icons.Filled.School
    NotificationType.ATTENDANCE_REMINDER -> Icons.Filled.FactCheck
    NotificationType.AUTO_ABSENT -> Icons.Filled.PersonOff
    NotificationType.FETCH_FAILURE -> Icons.Filled.Error
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationHistoryScreen(
    onBack: () -> Unit,
    viewModel: NotificationHistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("通知履歴") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    if (state.unreadCount > 0) {
                        TextButton(onClick = { viewModel.markAllRead() }) {
                            Text("すべて既読")
                        }
                    }
                    if (state.items.isNotEmpty()) {
                        TextButton(onClick = { viewModel.clear() }) {
                            Text("消去")
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (state.items.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(padding).padding(48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Filled.Notifications,
                    contentDescription = null,
                    modifier = Modifier.padding(bottom = 8.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "通知履歴はまだありません",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
            ) {
                items(state.items, key = { it.id }) { item ->
                    NotificationRow(
                        item = item,
                        onClick = {
                            viewModel.markAllRead()
                            item.url?.let { url ->
                                runCatching {
                                    context.startActivity(
                                        android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            url.toUri(),
                                        ),
                                    )
                                }
                            }
                        },
                        onDelete = { viewModel.delete(item.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NotificationRow(
    item: NotificationHistoryEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = if (item.read) {
            androidx.compose.material3.CardDefaults.cardColors()
        } else {
            androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            )
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    item.type.icon(),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        item.title,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        item.type.label(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Close, contentDescription = "削除")
                }
            }
            Text(
                item.text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text(
                TIME_FMT.format(Instant.ofEpochMilli(item.createdAt)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
