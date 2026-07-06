package org.cabetus.data.letus

import android.content.Context
import org.cabetus.data.local.AssignmentDao
import org.cabetus.data.local.AssignmentEntity
import org.cabetus.data.local.MoodleCourseDao
import org.cabetus.data.local.MoodleCourseEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

sealed interface FetchResult {
    data class Success(
        val detected: Int,
        val newAssignments: List<AssignmentEntity>,
        val dueSoon: List<AssignmentEntity>,
    ) : FetchResult

    data object LoginRequired : FetchResult
    data class NetworkError(val message: String) : FetchResult
}

/**
 * LETUS からコース・課題を取得して Room に保存する中心リポジトリ。
 */
@Singleton
class LetusRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
    private val courseDao: MoodleCourseDao,
    private val assignmentDao: AssignmentDao,
) {

    enum class LoginStatus { OK, LOGIN_REQUIRED, NETWORK_ERROR }

    /** ログイン状態を確認し、必要ならサイレント再認証を1度試みる。 */
    suspend fun ensureLoggedIn(): LoginStatus = withContext(Dispatchers.IO) {
        val first = checkLogin()
        if (first != LoginStatus.LOGIN_REQUIRED) return@withContext first
        val reAuthed = SilentReAuth.tryReAuth(context)
        if (!reAuthed) return@withContext LoginStatus.LOGIN_REQUIRED
        checkLogin()
    }

    private fun checkLogin(): LoginStatus = try {
        val resp = client.newCall(Request.Builder().url(LetusConstants.MY_URL).build()).execute()
        resp.use {
            if (!it.isSuccessful) return LoginStatus.NETWORK_ERROR
            // 未ログイン時は 303 → /auth/shibboleth → idp.admin.tus.ac.jp（SAML）
            // → login.microsoftonline.com と別ホストへ飛ぶ（実測）。
            // リダイレクト追従後の最終URLが letus の認証系以外のページであることを確認する。
            val finalUrl = it.request.url.toString()
            val body = it.body?.string().orEmpty()
            if (LetusParsing.isLoggedInResponse(finalUrl, body)) {
                LoginStatus.OK
            } else {
                LoginStatus.LOGIN_REQUIRED
            }
        }
    } catch (e: Exception) {
        LoginStatus.NETWORK_ERROR
    }

    /** コース・課題を取得。 */
    suspend fun fetchAll(scanLevel: ScanLevel = ScanLevel.STANDARD): FetchResult =
        withContext(Dispatchers.IO) {
            when (ensureLoggedIn()) {
                LoginStatus.LOGIN_REQUIRED -> return@withContext FetchResult.LoginRequired
                LoginStatus.NETWORK_ERROR ->
                    return@withContext FetchResult.NetworkError("LETUS への接続に失敗しました")
                LoginStatus.OK -> Unit
            }

            val existingBefore = assignmentDao.getAll().associateBy { it.id }

            // ① コース発見
            val courses = discoverCourses()
            if (courses.isNotEmpty()) courseDao.upsertAll(courses)
            val enabled = courseDao.getEnabled()

            // ② 課題候補抽出（同時3並列）
            val candidates = mapWithConcurrency(enabled, 3) { course ->
                runCatching {
                    val html = get(course.url) ?: return@runCatching emptyList()
                    LetusParsing.extractLinks(html, course.url)
                        .filter { LetusParsing.isAssignmentLikeLink(it.title, it.url, scanLevel) }
                        .map { link ->
                            Candidate(
                                id = LetusParsing.candidateId(course.id, link.url),
                                courseId = course.id,
                                courseName = course.name,
                                title = link.title,
                                url = link.url,
                            )
                        }
                }.getOrDefault(emptyList())
            }.flatten().distinctBy { it.id }

            // ③ 各候補の締切・提出状況（同時5並列）
            val now = System.currentTimeMillis()
            val assignments = mapWithConcurrency(candidates, 5) { c ->
                runCatching {
                    val html = get(c.url) ?: return@runCatching null
                    val plain = LetusParsing.htmlToPlainText(html)
                    val deadlineText = LetusParsing.extractDeadlineText(plain)
                    val deadline = if (deadlineText.isNotEmpty()) {
                        LetusParsing.parseDeadline(deadlineText)
                    } else {
                        null
                    }
                    val submission = LetusParsing.extractSubmissionStatus(plain, c.url)
                    val lifecycle = LetusParsing.resolveLifecycle(plain, submission, deadline, now)
                    val prev = existingBefore[c.id]
                    AssignmentEntity(
                        id = c.id,
                        courseId = c.courseId,
                        courseName = c.courseName,
                        title = c.title,
                        url = c.url,
                        deadline = deadline,
                        deadlineText = deadlineText,
                        submissionStatus = submission,
                        lifecycleStatus = lifecycle,
                        detectedAt = prev?.detectedAt ?: now,
                        firstSeenAt = prev?.firstSeenAt ?: now,
                        lastCheckedAt = now,
                        ignored = prev?.ignored ?: false,
                    )
                }.getOrNull()
            }.filterNotNull()

            if (assignments.isNotEmpty()) assignmentDao.upsertAll(assignments)

            val newOnes = assignments.filter { existingBefore[it.id] == null }
            val dueSoon = assignments.filter {
                it.deadline != null &&
                    it.deadline in (now + 1)..(now + 24L * 60 * 60 * 1000) &&
                    it.lifecycleStatus == org.cabetus.data.local.LifecycleStatus.ACTIVE
            }

            FetchResult.Success(assignments.size, newOnes, dueSoon)
        }

    /**
     * 履修コースを列挙する。
     * Moodle 4 の /my/ 系ページはコース一覧をJSで描画するためHTMLに
     * リンクが含まれないことがある。そのため、まずページから sesskey を取り
     * AJAX API（core_course_get_enrolled_courses_by_timeline_classification）で
     * 確実に列挙し、失敗時のみHTMLリンク走査へフォールバックする。
     */
    private fun discoverCourses(): List<MoodleCourseEntity> {
        val now = System.currentTimeMillis()
        val map = LinkedHashMap<String, MoodleCourseEntity>()

        fun add(name: String, url: String) {
            val cleanUrl = url.substringBefore('#')
            if (!LetusParsing.isCourseUrl(cleanUrl)) return
            val id = LetusParsing.courseId(cleanUrl)
            if (name.length in 2..200 && !map.containsKey(id)) {
                map[id] = MoodleCourseEntity(
                    id = id, name = name, url = cleanUrl, enabled = true, updatedAt = now,
                )
            }
        }

        // ① AJAX API（推奨経路）
        val myHtml = get(LetusConstants.MY_URL)
        val sesskey = myHtml?.let { LetusParsing.extractSesskey(it) }
        if (sesskey != null) {
            fetchEnrolledCoursesViaAjax(sesskey).forEach { add(it.title, it.url) }
        }

        // ② フォールバック: サーバレンダリングHTMLのリンク走査
        if (map.isEmpty()) {
            val pages = buildList {
                if (myHtml != null) add(LetusConstants.MY_URL to myHtml)
                for (indexUrl in LetusConstants.COURSE_INDEX_URLS) {
                    if (indexUrl == LetusConstants.MY_URL) continue
                    get(indexUrl)?.let { add(indexUrl to it) }
                }
            }
            for ((baseUrl, html) in pages) {
                LetusParsing.extractLinks(html, baseUrl).forEach { add(it.title, it.url) }
            }
        }
        return map.values.toList()
    }

    private fun fetchEnrolledCoursesViaAjax(sesskey: String): List<LetusParsing.Link> = try {
        val url = LetusConstants.AJAX_SERVICE_URL +
            "?sesskey=$sesskey&info=core_course_get_enrolled_courses_by_timeline_classification"
        val body = LetusParsing.enrolledCoursesRequestBody()
            .toRequestBody("application/json".toMediaType())
        client.newCall(Request.Builder().url(url).post(body).build()).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            LetusParsing.parseEnrolledCourses(resp.body?.string().orEmpty())
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun get(url: String): String? = try {
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    } catch (_: Exception) {
        null
    }

    private data class Candidate(
        val id: String,
        val courseId: String,
        val courseName: String,
        val title: String,
        val url: String,
    )

    private suspend fun <T, R> mapWithConcurrency(
        items: List<T>,
        concurrency: Int,
        block: suspend (T) -> R,
    ): List<R> = coroutineScope {
        val sem = Semaphore(concurrency)
        items.map { item ->
            async { sem.withPermit { block(item) } }
        }.map { it.await() }
    }
}
