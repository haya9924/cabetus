package org.cabetus.ui.browser

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.unit.dp
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class LetusDownloadRequest(val url: String, val userAgent: String, val name: String)

/** 明示確認とシステムの保存先選択を経たファイルだけを保存する。 */
@Composable
internal fun rememberLetusDownloadHandler(): (String, String, String?) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<LetusDownloadRequest?>(null) }
    var saving by remember { mutableStateOf<LetusDownloadRequest?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progressBytes by remember { mutableStateOf(0L to null as Long?) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
        val request = saving
        saving = null
        if (uri != null && request != null) {
            busy = true
            progressBytes = 0L to null
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        try {
                            context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                                LetusDownload(request.userAgent).download(request.url, output) { bytes, total ->
                                    scope.launch(Dispatchers.Main.immediate) { progressBytes = bytes to total }
                                }
                            } ?: error("保存先を開けませんでした。")
                        } catch (e: Exception) {
                            runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                            throw e
                        }
                    }
                    message = "資料を保存しました。"
                } catch (e: Exception) {
                    message = "保存できませんでした。ログイン状態や通信を確認してください。"
                } finally { busy = false }
            }
        }
    }
    pending?.let { request ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("資料を保存") },
            text = { Text(request.name + "\n保存先を選択してください（上限100MB）。") },
            confirmButton = { TextButton(onClick = {
                pending = null
                saving = request
                try { launcher.launch(request.name) } catch (_: Exception) {
                    saving = null
                    message = "保存先を選択できませんでした。"
                }
            }) { Text("保存先を選ぶ") } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("キャンセル") } },
        )
    }
    if (busy) AlertDialog(
        onDismissRequest = {}, title = { Text("ダウンロード中") },
        text = {
            val (bytes, total) = progressBytes
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (total != null && total > 0) {
                    // ストリームのclose完了前に100%とは表示しない。
                    val percent = ((bytes * 100) / total).coerceIn(0, 99).toInt()
                    Text("$percent%")
                    LinearProgressIndicator(progress = { percent / 100f })
                } else {
                    Text("サイズ不明・受信中")
                    LinearProgressIndicator()
                }
                Text("受信済み：${bytes / 1024} KB", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = {},
    )
    message?.let { text ->
        AlertDialog(onDismissRequest = { message = null }, text = { Text(text) }, confirmButton = {
            TextButton(onClick = { message = null }) { Text("閉じる") }
        })
    }
    return { url, userAgent, disposition ->
        if (!LetusDownload.isAllowedUrl(url)) {
            message = "この形式のダウンロードは未対応です。外部ブラウザをご利用ください。"
        } else if (!busy && saving == null) {
            pending = LetusDownloadRequest(url, userAgent,
                LetusDownload.fileNameFromContentDisposition(disposition) ?: LetusDownload.fileNameFromUrl(url))
        }
    }
}
