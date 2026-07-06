package org.cabetus.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.cabetus.data.local.AssignmentEntity
import org.cabetus.notification.NotificationHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenAssignments: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val weather by viewModel.weather.collectAsStateWithLifecycle()
    val aiState by viewModel.aiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(topBar = { TopAppBar(title = { Text("ホーム") }) }) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
        ) {
            item { WeatherCard(weather, state.dateLabel) { viewModel.refreshWeather() } }
            item { ClassCard(state) }
            item { AiCard(aiState, state.aiConfigured, onGenerate = { viewModel.generateSummary() }, onOpenSettings = onOpenSettings) }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("残っている課題", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = onOpenAssignments) { Text("すべて見る") }
                }
            }
            if (state.pending.isEmpty()) {
                item { Text("未提出の課題はありません 🎉", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(state.pending) { a ->
                    AssignmentRow(a) {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(android.content.Intent.ACTION_VIEW, a.url.toUri()),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WeatherCard(
    weather: org.cabetus.data.weather.WeatherSummary?,
    dateLabel: String,
    onRefresh: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(weather?.emoji ?: "🌡️", fontSize = 40.sp)
            Column(Modifier.weight(1f)) {
                Text(dateLabel, style = MaterialTheme.typography.labelMedium)
                if (weather != null) {
                    Text(
                        "${weather.label}  ${weather.currentTemp?.let { "${it.toInt()}℃" } ?: ""}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        buildString {
                            weather.maxTemp?.let { append("最高 ${it.toInt()}℃ ") }
                            weather.minTemp?.let { append("最低 ${it.toInt()}℃ ") }
                            weather.precipProbability?.let { append("降水 ${it}%") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text("天気を取得できませんでした", style = MaterialTheme.typography.bodySmall)
                }
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, contentDescription = "更新")
            }
        }
    }
}

@Composable
private fun ClassCard(state: HomeUiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("授業", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            val current = state.currentClass
            val next = state.nextClass
            if (current != null) {
                Text(
                    "🟢 実施中: ${current.period}限 ${current.name}" +
                        (if (current.isOnline) "（オンライン）" else current.room?.let { "（$it）" } ?: ""),
                )
            }
            if (next != null) {
                Text(
                    "▶ 次: ${next.period}限 ${next.name}" +
                        (next.start?.let { "  %02d:%02d〜".format(it.hour, it.minute) } ?: "") +
                        (if (next.isOnline) "（オンライン）" else next.room?.let { "（$it）" } ?: ""),
                )
            }
            if (current == null && next == null) {
                Text(
                    if (state.todayClasses.isEmpty()) "今日の授業はありません" else "本日の授業は終了しました",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AiCard(
    aiState: AiCardState,
    configured: Boolean,
    onGenerate: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null)
                Text("今日のひとこと", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
            when (aiState) {
                is AiCardState.Loaded -> Text(aiState.text)
                AiCardState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(end = 8.dp))
                    Text("生成中…")
                }

                is AiCardState.Error -> Text("エラー: ${aiState.message}", color = MaterialTheme.colorScheme.error)
                AiCardState.NotConfigured -> Text("設定でAIのAPIを設定すると利用できます。")
                AiCardState.Idle -> Text("ボタンを押すと今日のアドバイスを生成します。")
            }
            if (configured) {
                TextButton(onClick = onGenerate) { Text("生成する") }
            } else {
                TextButton(onClick = onOpenSettings) { Text("AIを設定する") }
            }
        }
    }
}

@Composable
private fun AssignmentRow(a: AssignmentEntity, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(a.title, fontWeight = FontWeight.Bold, maxLines = 2)
            Text(a.courseName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "期限: ${NotificationHelper.formatDeadline(a.deadline)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
