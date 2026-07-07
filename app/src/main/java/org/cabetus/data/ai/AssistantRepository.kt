package org.cabetus.data.ai

import org.cabetus.data.settings.AiSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/** AI に渡す今日のコンテキスト。 */
data class AssistantContext(
    val dateLabel: String,
    val todayClasses: List<String>,
    val pendingAssignments: List<String>,
    val weather: String?,
)

sealed interface AssistantResult {
    data class Success(val text: String) : AssistantResult
    data class Error(val message: String) : AssistantResult
    data object NotConfigured : AssistantResult
}

/**
 * OpenAI互換 Chat Completions API を用いた「今日のひとことアシスタント」。
 * ベースURL・APIキー・モデル名は設定から与える（Gemini・OpenRouter・ローカルLLM等をカバー）。
 */
@Singleton
class AssistantRepository @Inject constructor(
    private val client: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMedia = "application/json".toMediaType()

    suspend fun generate(settings: AiSettings, context: AssistantContext): AssistantResult =
        withContext(Dispatchers.IO) {
            if (!settings.isConfigured) return@withContext AssistantResult.NotConfigured

            val prompt = buildPrompt(context)
            val requestJson = ChatRequest(
                model = settings.model,
                messages = listOf(
                    ChatMessage("system", buildSystemPrompt(settings)),
                    ChatMessage("user", prompt),
                ),
                temperature = 0.7,
            )
            val bodyStr = json.encodeToString(ChatRequest.serializer(), requestJson)
            val url = settings.baseUrl.trimEnd('/') + "/chat/completions"
            val builder = Request.Builder()
                .url(url)
                .post(bodyStr.toRequestBody(jsonMedia))
            if (settings.apiKey.isNotBlank()) {
                builder.header("Authorization", "Bearer ${settings.apiKey}")
            }
            try {
                client.newCall(builder.build()).execute().use { resp ->
                    val respBody = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        return@withContext AssistantResult.Error("HTTP ${resp.code}: ${respBody.take(200)}")
                    }
                    val parsed = json.decodeFromString(ChatResponse.serializer(), respBody)
                    val text = parsed.choices.firstOrNull()?.message?.content?.trim()
                    if (text.isNullOrBlank()) {
                        AssistantResult.Error("空の応答")
                    } else {
                        AssistantResult.Success(text)
                    }
                }
            } catch (e: Exception) {
                AssistantResult.Error(e.message ?: "通信エラー")
            }
        }

    /** 標準プロンプトに口調プリセット・カスタム命令を足す（カスタム命令を最後に置き優先させる）。 */
    private fun buildSystemPrompt(settings: AiSettings): String = buildString {
        append(SYSTEM_PROMPT)
        if (settings.tone.instruction.isNotBlank()) {
            appendLine()
            append(settings.tone.instruction)
        }
        if (settings.customInstruction.isNotBlank()) {
            appendLine()
            append("追加の指示: ${settings.customInstruction}")
        }
    }

    private fun buildPrompt(c: AssistantContext): String = buildString {
        appendLine("今日は${c.dateLabel}です。")
        c.weather?.let { appendLine("天気: $it") }
        if (c.todayClasses.isEmpty()) {
            appendLine("今日の授業: なし")
        } else {
            appendLine("今日の授業:")
            c.todayClasses.forEach { appendLine("- $it") }
        }
        if (c.pendingAssignments.isEmpty()) {
            appendLine("未提出の課題: なし")
        } else {
            appendLine("未提出の課題（期日順）:")
            c.pendingAssignments.forEach { appendLine("- $it") }
        }
        appendLine()
        appendLine("上記を踏まえ、今日の過ごし方を励ますように2〜4文の短い日本語でアドバイスしてください。")
    }

    companion object {
        private const val SYSTEM_PROMPT =
            "あなたは大学生を支える親しみやすいアシスタントです。簡潔で前向きな日本語で助言します。"
    }
}

@Serializable
private data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.7,
)

@Serializable
private data class ChatMessage(val role: String, val content: String)

@Serializable
private data class ChatResponse(val choices: List<Choice> = emptyList()) {
    @Serializable
    data class Choice(val message: ChatMessage? = null)
}
