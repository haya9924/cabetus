package org.cabetus.ui.setup

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.cabetus.domain.Campus
import org.cabetus.ui.login.LoginScreen
import org.cabetus.ui.pdf.PdfImportScreen

private const val APP_NAME = "cabetus"

/** リポジトリURL（現在プライベート。公開時に実URLへ差し替える）。 */
private const val GITHUB_URL = "https://github.com/"

@Composable
fun SetupScreen(
    onFinished: () -> Unit,
    viewModel: SetupViewModel = hiltViewModel(),
) {
    var step by remember { mutableIntStateOf(0) }
    val campus by viewModel.campus.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { step = 2 }

    when (step) {
        0 -> StepScaffold(
            title = "${APP_NAME}へようこそ 👋",
            body = "$APP_NAME は LETUS の課題と CLASS の時間割・出欠をまとめて表示するアプリです。\n最初にいくつか設定しましょう。",
            onNext = { step = 1 },
            nextLabel = "はじめる",
        )

        1 -> StepScaffold(
            title = "通知を有効にする",
            body = "課題の期日・授業開始・今日のまとめなどをお知らせします。通知を有効にしますか？",
            onNext = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    step = 2
                }
            },
            nextLabel = "通知を有効にする",
            onSkip = { step = 2 },
        )

        2 -> LoginScreen(onDone = { step = 3 })

        3 -> PdfImportScreen(onDone = { step = 4 })

        4 -> StepScaffold(
            title = "キャンパスを選択",
            body = "天気予報に使用します。",
            onNext = { step = 5 },
            content = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Campus.entries.forEach { c ->
                        FilterChip(
                            selected = campus == c,
                            onClick = { viewModel.setCampus(c) },
                            label = { Text(c.displayName) },
                        )
                    }
                }
            },
        )

        5 -> AiStep(
            onSave = { b, k, m -> viewModel.saveAi(b, k, m); step = 6 },
            onSkip = { step = 6 },
        )

        6 -> CompletionStep(
            onComplete = { viewModel.complete(); onFinished() },
            onTweet = {
                val text = Uri.encode("${APP_NAME}を始めました！ $GITHUB_URL #cabetus #キャベツ")
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, "https://twitter.com/intent/tweet?text=$text".toUri()),
                    )
                }
            },
        )
    }
}

@Composable
private fun StepScaffold(
    title: String,
    body: String,
    onNext: () -> Unit,
    nextLabel: String = "次へ",
    onSkip: (() -> Unit)? = null,
    content: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.padding(top = 24.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(body, style = MaterialTheme.typography.bodyLarge)
        content?.invoke()
        Spacer(Modifier.padding(top = 24.dp))
        Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) { Text(nextLabel) }
        if (onSkip != null) {
            TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) { Text("スキップ") }
        }
    }
}

@Composable
private fun AiStep(onSave: (String, String, String) -> Unit, onSkip: () -> Unit) {
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("AIアシスタント（任意）", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("OpenAI互換APIを設定すると「今日のひとこと」が使えます。後から設定でも変更できます。")
        OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("ベースURL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(apiKey, { apiKey = it }, label = { Text("APIキー") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(model, { model = it }, label = { Text("モデル名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.padding(top = 8.dp))
        Button(
            onClick = { onSave(baseUrl, apiKey, model) },
            enabled = baseUrl.isNotBlank() && model.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("保存して次へ") }
        TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) { Text("スキップ") }
    }
}

@Composable
private fun CompletionStep(onComplete: () -> Unit, onTweet: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("🎉", fontSize = 64.sp)
        Text("完了しました！", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.padding(8.dp))
        Text("さっそく使ってみましょう。", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.padding(16.dp))
        Button(onClick = onComplete, modifier = Modifier.fillMaxWidth()) { Text("完了") }
        OutlinedButton(onClick = onTweet, modifier = Modifier.fillMaxWidth()) { Text("Xに投稿する") }
    }
}
