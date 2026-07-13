package org.cabetus.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.cabetus.data.settings.AppSettings
import org.cabetus.data.settings.FetchFrequency
import org.cabetus.domain.Campus
import org.cabetus.ui.theme.ThemeMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenLogin: () -> Unit,
    onOpenPdfImport: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val aiTest by viewModel.aiTestResult.collectAsStateWithLifecycle()
    val s = settings ?: AppSettings()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AutoFetchSection(s, viewModel)
            LoginSection(s, onOpenLogin)
            PdfSection(onOpenPdfImport)
            CampusSection(s, viewModel)
            ThemeSection(s, viewModel)
            AiSection(s, aiTest, viewModel)
            NotificationSection(s, viewModel)
            AboutSection()
        }
    }
}

@Composable
private fun AboutSection() {
    SectionCard("アプリ情報") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("アプリ名")
            Text("cabetus")
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("バージョン")
            Text(org.cabetus.BuildConfig.VERSION_NAME)
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutoFetchSection(s: AppSettings, vm: SettingsViewModel) {
    SectionCard("課題の自動取得") {
        Text("取得頻度")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = s.fetch.frequency == FetchFrequency.HOURLY,
                onClick = { vm.setFetch(s.fetch.copy(frequency = FetchFrequency.HOURLY)) },
                label = { Text("毎時") },
            )
            FilterChip(
                selected = s.fetch.frequency == FetchFrequency.DAILY,
                onClick = { vm.setFetch(s.fetch.copy(frequency = FetchFrequency.DAILY)) },
                label = { Text("毎日") },
            )
        }
        if (s.fetch.frequency == FetchFrequency.DAILY) {
            Text("取得時刻: ${s.fetch.dailyHour}時")
            Slider(
                value = s.fetch.dailyHour.toFloat(),
                onValueChange = { vm.setFetch(s.fetch.copy(dailyHour = it.toInt())) },
                valueRange = 0f..23f,
                steps = 22,
            )
        }
        SwitchRow(
            "モバイルデータ通信を使う",
            s.fetch.allowMobileData,
        ) { vm.setFetch(s.fetch.copy(allowMobileData = it)) }
        Text("モバイルデータで見送った回数がこの回数に達したら強制取得: ${s.fetch.forceFetchAfterSkips}回")
        Slider(
            value = s.fetch.forceFetchAfterSkips.toFloat(),
            onValueChange = { vm.setFetch(s.fetch.copy(forceFetchAfterSkips = it.toInt().coerceAtLeast(1))) },
            valueRange = 1f..10f,
            steps = 8,
        )
        Button(onClick = { vm.fetchNow() }, modifier = Modifier.fillMaxWidth()) {
            Text("今すぐ取得")
        }
        if (s.lastFetchResult.isNotBlank()) {
            Text(
                "最終取得: ${s.lastFetchResult}" +
                    (if (s.lastFetchAt > 0) "（${fmtTime(s.lastFetchAt)}）" else ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LoginSection(s: AppSettings, onOpenLogin: () -> Unit) {
    SectionCard("ログイン状態") {
        Text(if (s.loggedIn) "ログイン済み ✓" else "未ログイン")
        OutlinedButton(onClick = onOpenLogin, modifier = Modifier.fillMaxWidth()) {
            Text(if (s.loggedIn) "ログインし直す" else "ログインする")
        }
    }
}

@Composable
private fun PdfSection(onOpenPdfImport: () -> Unit) {
    SectionCard("時間割・出欠PDF") {
        OutlinedButton(onClick = onOpenPdfImport, modifier = Modifier.fillMaxWidth()) {
            Text("PDFを再インポート")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CampusSection(s: AppSettings, vm: SettingsViewModel) {
    SectionCard("キャンパス") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Campus.entries.forEach { c ->
                FilterChip(
                    selected = s.campus == c,
                    onClick = { vm.setCampus(c) },
                    label = { Text(c.displayName) },
                )
            }
        }
        Text(
            "天気予報に使用します。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val ACCENT_PRESETS = listOf(
    0xFF3A5BA0, 0xFF00695C, 0xFF2E7D32, 0xFFEF6C00,
    0xFFC62828, 0xFFAD1457, 0xFF6A1B9A, 0xFF5D4037,
)
private val BACKGROUND_PRESETS = listOf(
    0xFFFFFFFF, 0xFFFAF6EF, 0xFFECEFF1, 0xFFE3E7EC,
    0xFF121212, 0xFF0D1B2A, 0xFF1B1B2F, 0xFF12232E,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeSection(s: AppSettings, vm: SettingsViewModel) {
    SectionCard("テーマ") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val labels = mapOf(ThemeMode.SYSTEM to "端末に従う", ThemeMode.LIGHT to "ライト", ThemeMode.DARK to "ダーク")
            ThemeMode.entries.forEach { m ->
                FilterChip(
                    selected = s.themeMode == m,
                    onClick = { vm.setThemeMode(m) },
                    label = { Text(labels[m] ?: m.name) },
                )
            }
        }
        SwitchRow("ダイナミックカラー (Material You)", s.dynamicColor) { vm.setDynamicColor(it) }

        ColorRow(
            title = "アクセントカラー",
            presets = ACCENT_PRESETS,
            selected = s.customAccent,
            onSelect = { vm.setCustomAccent(it) },
        )
        ColorRow(
            title = "背景色",
            presets = BACKGROUND_PRESETS,
            selected = s.customBackground,
            onSelect = { vm.setCustomBackground(it) },
        )
        if (s.customAccent != null || s.customBackground != null) {
            Text(
                "カスタムカラー設定時はダイナミックカラーより優先されます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ColorRow(
    title: String,
    presets: List<Long>,
    selected: Int?,
    onSelect: (Int?) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    Text(title, style = MaterialTheme.typography.labelLarge)
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // デフォルト（未設定）
        DefaultSwatch(isSelected = selected == null, onClick = { onSelect(null) })
        presets.forEach { argb ->
            val color = Color(argb)
            ColorSwatch(
                color = color,
                isSelected = selected == color.toArgb(),
                onClick = { onSelect(color.toArgb()) },
            )
        }
        // カスタム
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                .clickable { showPicker = true },
            contentAlignment = Alignment.Center,
        ) {
            Text("+", style = MaterialTheme.typography.titleMedium)
        }
    }
    if (showPicker) {
        ColorPickerDialog(
            initial = selected?.let { Color(it) } ?: Color(presets.first()),
            onPick = { onSelect(it.toArgb()) },
            onDismiss = { showPicker = false },
        )
    }
}

@Composable
private fun ColorSwatch(color: Color, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                if (isSelected) 3.dp else 1.dp,
                if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                CircleShape,
            )
            .clickable(onClick = onClick),
    )
}

@Composable
private fun DefaultSwatch(isSelected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(
                if (isSelected) 3.dp else 1.dp,
                if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("既", style = MaterialTheme.typography.labelSmall)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiSection(s: AppSettings, aiTest: String?, vm: SettingsViewModel) {
    var baseUrl by remember(s.ai.baseUrl) { mutableStateOf(s.ai.baseUrl) }
    var apiKey by remember(s.ai.apiKey) { mutableStateOf(s.ai.apiKey) }
    var model by remember(s.ai.model) { mutableStateOf(s.ai.model) }
    var tone by remember(s.ai.tone) { mutableStateOf(s.ai.tone) }
    var customInstruction by remember(s.ai.customInstruction) { mutableStateOf(s.ai.customInstruction) }

    fun current() = org.cabetus.data.settings.AiSettings(
        baseUrl.trim(), apiKey.trim(), model.trim(), tone, customInstruction.trim(),
    )

    SectionCard("AI（OpenAI互換API）") {
        Text(
            "ベースURL・APIキー・モデル名を設定します（例: https://api.openai.com/v1 ）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = baseUrl, onValueChange = { baseUrl = it },
            label = { Text("ベースURL") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = apiKey, onValueChange = { apiKey = it },
            label = { Text("APIキー") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = model, onValueChange = { model = it },
            label = { Text("モデル名") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Text("口調", style = MaterialTheme.typography.labelLarge)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            org.cabetus.data.settings.AiTone.entries.forEach { t ->
                FilterChip(
                    selected = tone == t,
                    onClick = { tone = t },
                    label = { Text(t.label) },
                )
            }
        }
        OutlinedTextField(
            value = customInstruction, onValueChange = { customInstruction = it },
            label = { Text("カスタム指示（例: 一人称は「ボク」）") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { vm.setAi(current()) },
                modifier = Modifier.weight(1f),
            ) { Text("保存") }
            OutlinedButton(
                onClick = { vm.testAi(current()) },
                modifier = Modifier.weight(1f),
            ) { Text("テスト送信") }
        }
        aiTest?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun NotificationSection(s: AppSettings, vm: SettingsViewModel) {
    val n = s.notifications
    SectionCard("通知") {
        SwitchRow("今日のまとめ（AIデイリー通知）", n.dailySummary) {
            vm.setNotifications(n.copy(dailySummary = it))
        }
        if (n.dailySummary) {
            Text("通知時刻: ${"%02d:%02d".format(n.dailySummaryHour, n.dailySummaryMinute)}")
            Text("時", style = MaterialTheme.typography.labelSmall)
            Slider(
                value = n.dailySummaryHour.toFloat(),
                onValueChange = { vm.setNotifications(n.copy(dailySummaryHour = it.toInt())) },
                valueRange = 0f..23f, steps = 22,
            )
            Text("分", style = MaterialTheme.typography.labelSmall)
            Slider(
                value = n.dailySummaryMinute.toFloat(),
                onValueChange = { vm.setNotifications(n.copy(dailySummaryMinute = (it.toInt() / 5) * 5)) },
                valueRange = 0f..55f, steps = 10,
            )
        }
        SwitchRow("授業開始のお知らせ", n.classStart) { vm.setNotifications(n.copy(classStart = it)) }
        SwitchRow("出席チェック忘れのお知らせ", n.attendanceReminder) {
            vm.setNotifications(n.copy(attendanceReminder = it))
        }
        if (n.attendanceReminder) {
            Text("授業終了 ${n.attendanceReminderMinutes} 分前に、出席が未記録なら通知します")
            Slider(
                value = n.attendanceReminderMinutes.toFloat(),
                onValueChange = {
                    vm.setNotifications(n.copy(attendanceReminderMinutes = (it.toInt() / 5) * 5))
                },
                valueRange = 5f..45f, steps = 7,
            )
        }
        SwitchRow("課題の期日が近いお知らせ", n.deadline) { vm.setNotifications(n.copy(deadline = it)) }
        SwitchRow("新規課題のお知らせ", n.newAssignment) { vm.setNotifications(n.copy(newAssignment = it)) }
        SwitchRow("LETUS変更のお知らせ", n.letusChange) { vm.setNotifications(n.copy(letusChange = it)) }
        SwitchRow("取得失敗のお知らせ", n.fetchFailure) { vm.setNotifications(n.copy(fetchFailure = it)) }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private val timeFmt = DateTimeFormatter.ofPattern("M/d HH:mm").withZone(ZoneId.of("Asia/Tokyo"))
private fun fmtTime(epoch: Long): String = timeFmt.format(Instant.ofEpochMilli(epoch))
