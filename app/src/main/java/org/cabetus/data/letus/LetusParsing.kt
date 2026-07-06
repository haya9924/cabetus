package org.cabetus.data.letus

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.cabetus.data.local.LifecycleStatus
import org.cabetus.data.local.SubmissionStatus
import org.jsoup.Jsoup
import java.net.URI
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * LETUS のHTML/本文から情報を抽出する純ロジック。副作用なし・Android非依存で
 * JVM ユニットテスト可能。参考リポジトリの background/index.ts を移植。
 */
object LetusParsing {

    private val JST: ZoneId = ZoneId.of("Asia/Tokyo")

    fun normalizeText(text: String?): String =
        (text ?: "").trim().replace(Regex("\\s+"), " ")

    data class Link(val title: String, val url: String)

    /** HTML から絶対URL付きのリンク一覧を抽出する。 */
    fun extractLinks(html: String, baseUrl: String): List<Link> {
        val doc = Jsoup.parse(html, baseUrl)
        return doc.select("a[href]").mapNotNull { a ->
            val abs = a.absUrl("href").ifBlank { return@mapNotNull null }
            val url = abs.substringBefore('#')
            val title = normalizeText(a.text())
            if (title.isEmpty()) null else Link(title, url)
        }
    }

    /** HTML をプレーンテキスト化（締切/提出状況判定用）。 */
    fun htmlToPlainText(html: String): String =
        normalizeText(Jsoup.parse(html).text())

    fun isTargetActivityUrl(url: String, level: ScanLevel): Boolean {
        val u = url.lowercase()
        val paths = when (level) {
            ScanLevel.STRICT -> LetusConstants.STRICT_MODULE_PATHS
            ScanLevel.STANDARD -> LetusConstants.STRICT_MODULE_PATHS + LetusConstants.STANDARD_EXTRA_PATHS
            ScanLevel.BROAD ->
                LetusConstants.STRICT_MODULE_PATHS + LetusConstants.STANDARD_EXTRA_PATHS + LetusConstants.BROAD_EXTRA_PATHS
        }
        return paths.any { u.contains(it) }
    }

    fun isClearlyNonAssignmentUrl(url: String): Boolean {
        val u = url.lowercase()
        return LetusConstants.EXCLUDED_PATHS.any { u.contains(it) }
    }

    fun hasAssignmentKeyword(text: String, url: String): Boolean {
        val t = normalizeText(text).lowercase()
        val u = url.lowercase()
        return LetusConstants.ASSIGNMENT_KEYWORDS.any { k ->
            val lk = k.lowercase()
            t.contains(lk) || u.contains(lk)
        }
    }

    fun isAssignmentLikeLink(text: String, url: String, level: ScanLevel): Boolean {
        val t = normalizeText(text)
        if (t.length < 2 || t.length > 220) return false
        if (isClearlyNonAssignmentUrl(url)) return false
        if (isTargetActivityUrl(url, level)) return true
        if (level == ScanLevel.BROAD) return hasAssignmentKeyword(t, url)
        return false
    }

    fun isCourseUrl(url: String): Boolean = try {
        val hostOk = url.contains(LetusConstants.HOST)
        hostOk && url.contains(LetusConstants.COURSE_VIEW) && url.contains("id=")
    } catch (_: Exception) {
        false
    }

    // ─── 締切 ─────────────────────────────────────────────

    private val JP_DATE = Regex(
        "(20\\d{2})\\s*年\\s*(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日" +
            "(?:\\s*[(（][^)）]*[)）])?\\s*(?:(\\d{1,2})\\s*(?:時|:|：)\\s*(\\d{1,2})?\\s*分?)?",
    )
    private val JP_DATE_NO_YEAR = Regex(
        "(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日" +
            "(?:\\s*[(（][^)）]*[)）])?\\s*(?:(\\d{1,2})\\s*(?:時|:|：)\\s*(\\d{1,2})?\\s*分?)?",
    )

    /** 締切キーワードの位置から本文を切り出す。見つからなければ空文字。 */
    fun extractDeadlineText(plainText: String): String {
        val text = normalizeText(plainText)
        val lower = text.lowercase()
        var best = -1
        for (kw in LetusConstants.DEADLINE_KEYWORDS) {
            val idx = lower.indexOf(kw.lowercase())
            if (idx >= 0 && (best == -1 || idx < best)) best = idx
        }
        return if (best >= 0) text.substring(best, minOf(text.length, best + 320)) else ""
    }

    /** 締切テキストをエポックミリ秒（JST）に変換。パース不能なら null。 */
    fun parseDeadline(deadlineText: String, now: LocalDateTime = LocalDateTime.now(JST)): Long? {
        val text = normalizeText(deadlineText)

        JP_DATE.find(text)?.let { m ->
            val year = m.groupValues[1].toInt()
            val month = m.groupValues[2].toInt()
            val day = m.groupValues[3].toInt()
            val hasHour = m.groupValues[4].isNotEmpty()
            val hour = if (hasHour) m.groupValues[4].toInt() else 23
            val minute = if (hasHour) m.groupValues[5].ifEmpty { "0" }.toInt() else 59
            return toEpoch(year, month, day, hour, minute)
        }
        JP_DATE_NO_YEAR.find(text)?.let { m ->
            val month = m.groupValues[1].toInt()
            val day = m.groupValues[2].toInt()
            val hasHour = m.groupValues[3].isNotEmpty()
            val hour = if (hasHour) m.groupValues[3].toInt() else 23
            val minute = if (hasHour) m.groupValues[4].ifEmpty { "0" }.toInt() else 59
            return toEpoch(now.year, month, day, hour, minute)
        }
        return null
    }

    private fun toEpoch(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long? = try {
        LocalDateTime.of(year, month, day, hour, minute)
            .atZone(JST).toInstant().toEpochMilli()
    } catch (_: Exception) {
        null
    }

    // ─── 提出状況・ライフサイクル ────────────────────────────

    fun extractSubmissionStatus(plainText: String, url: String): SubmissionStatus {
        val text = normalizeText(plainText).lowercase()
        val isQuiz = url.lowercase().contains("/mod/quiz/")
        if (isQuiz) {
            if (text.contains("ステータス 終了") || text.contains("status finished")) {
                return SubmissionStatus.COMPLETED
            }
            if (text.contains("受験済み") || text.contains("attempt finished")) {
                return SubmissionStatus.COMPLETED
            }
            if (text.contains("利用できません") || text.contains("not available") ||
                text.contains("未受験") || text.contains("not attempted")
            ) {
                return SubmissionStatus.NOT_SUBMITTED
            }
            return SubmissionStatus.UNKNOWN
        }
        if (text.contains("提出済み") || text.contains("submitted")) return SubmissionStatus.SUBMITTED
        if (text.contains("未提出") || text.contains("not submitted")) return SubmissionStatus.NOT_SUBMITTED
        return SubmissionStatus.UNKNOWN
    }

    private fun isBeforeStart(plainText: String): Boolean {
        val t = normalizeText(plainText)
        return t.contains("開始予定") && t.contains("利用できません")
    }

    private fun isDeadlinePassed(deadline: Long?, nowMillis: Long): Boolean =
        deadline != null && deadline < nowMillis

    fun resolveLifecycle(
        plainText: String,
        submission: SubmissionStatus,
        deadline: Long?,
        nowMillis: Long = System.currentTimeMillis(),
    ): LifecycleStatus {
        if (isBeforeStart(plainText)) return LifecycleStatus.BEFORE_START
        if (submission == SubmissionStatus.SUBMITTED || submission == SubmissionStatus.COMPLETED) {
            return LifecycleStatus.SUBMITTED
        }
        if (isDeadlinePassed(deadline, nowMillis)) return LifecycleStatus.PASSED
        return LifecycleStatus.ACTIVE
    }

    /** 課題候補IDを生成（コースID+URLのbase64url）。 */
    fun candidateId(courseId: String, url: String): String = encodeId("$courseId:$url")

    fun courseId(url: String): String = encodeId(url)

    private fun encodeId(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return java.util.Base64.getEncoder().encodeToString(bytes)
            .replace("=", "").replace("+", "-").replace("/", "_")
    }

    fun isNotLoggedInPage(html: String): Boolean =
        LetusConstants.NOT_LOGGED_IN_MARKERS.any { html.contains(it) }

    // ─── ログイン判定（実測フローに基づく） ────────────────────────

    /**
     * リダイレクト追従後の最終URLが「認証済みの LETUS ページ」かどうか。
     * 未ログイン時は idp.admin.tus.ac.jp / login.microsoftonline.com（別ホスト）や
     * /auth/shibboleth/ 等（認証系パス）に到達するため、
     * ホストが letus 以外・認証系パスのどちらでも未認証とみなす。
     */
    fun isAuthenticatedLetusUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host ?: return false
        if (!host.equals(LetusConstants.HOST, ignoreCase = true)) return false
        val path = (uri.path ?: "").lowercase()
        return LetusConstants.AUTH_PATH_MARKERS.none { path.contains(it) }
    }

    /** 最終URLと本文からログイン済みかを判定する。 */
    fun isLoggedInResponse(finalUrl: String, body: String): Boolean =
        isAuthenticatedLetusUrl(finalUrl) && !isNotLoggedInPage(body)

    // ─── Moodle AJAX API（コース列挙） ────────────────────────────

    private val SESSKEY = Regex("\"sesskey\"\\s*:\\s*\"([A-Za-z0-9]+)\"")
    private val json = Json { ignoreUnknownKeys = true }

    /** ログイン中ページの M.cfg から sesskey を抽出する。 */
    fun extractSesskey(html: String): String? =
        SESSKEY.find(html)?.groupValues?.get(1)

    /**
     * core_course_get_enrolled_courses_by_timeline_classification に渡す
     * リクエストボディ（JSON）。
     */
    fun enrolledCoursesRequestBody(): String =
        """[{"index":0,"methodname":"core_course_get_enrolled_courses_by_timeline_classification","args":{"offset":0,"limit":0,"classification":"all","sort":"fullname"}}]"""

    /**
     * 上記APIのレスポンスから (コース名, URL) を取り出す。
     * 形式: [{"error":false,"data":{"courses":[{"id":..,"fullname":"..","viewurl":".."}]}}]
     * エラー時・想定外の形式なら空リスト。
     */
    fun parseEnrolledCourses(body: String): List<Link> = runCatching {
        val root = json.parseToJsonElement(body).jsonArray
        val first = root.firstOrNull()?.jsonObject ?: return emptyList()
        val isError = first["error"]?.jsonPrimitive?.content == "true"
        if (isError) return emptyList()
        val courses = first["data"]?.jsonObject?.get("courses")?.jsonArray ?: return emptyList()
        courses.mapNotNull { el ->
            val obj = el.jsonObject
            val url = obj["viewurl"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val rawName = obj["fullname"]?.jsonPrimitive?.content ?: return@mapNotNull null
            // fullname はHTMLエスケープされていることがあるため展開する
            val name = normalizeText(Jsoup.parse(rawName).text())
            if (name.isEmpty()) null else Link(name, url.substringBefore('#'))
        }
    }.getOrDefault(emptyList())
}
