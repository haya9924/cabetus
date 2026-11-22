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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/** 既存課題に生じた変更（締切・タイトル）。通知の本文組み立てに使う。 */
data class AssignmentChange(
    val assignment: AssignmentEntity,
    /** 人間が読める変更点。例: "締切: 7/12 23:59"。 */
    val changes: List<String>,
)

sealed interface FetchResult {
    data class Success(
        val detected: Int,
        val newAssignments: List<AssignmentEntity>,
        val dueSoon: List<AssignmentEntity>,
        val changed: List<AssignmentChange>,
        /** 取得に失敗したページ数。0 でなければ一部のみ更新されている。 */
        val failedRequests: Int = 0,
        /** 初回取得（既存課題がDBに無い）かどうか。初回は新規通知を抑える用途に使う。 */
        val isInitialImport: Boolean = false,
    ) : FetchResult {
        val isPartial: Boolean get() = failedRequests > 0
    }

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

    /**
     * [fetchAll] 全体を直列化する。Worker と手動取得が同時に走ると、
     * 互いの途中経過を既存行（差分判定の基準）として読んでしまうため。
     */
    private val fetchMutex = Mutex()

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
        fetchMutex.withLock { fetchAllLocked(scanLevel) }

    private suspend fun fetchAllLocked(scanLevel: ScanLevel): FetchResult =
        withContext(Dispatchers.IO) {
            when (ensureLoggedIn()) {
                LoginStatus.LOGIN_REQUIRED -> return@withContext FetchResult.LoginRequired
                LoginStatus.NETWORK_ERROR ->
                    return@withContext FetchResult.NetworkError("LETUS への接続に失敗しました")
                LoginStatus.OK -> Unit
            }

            val existingBefore = assignmentDao.getAll().associateBy { it.id }

            // ① コース発見
            // discovered は常に enabled=true で構築される。既存行の enabled（ユーザー設定）を
            // 上書きしないよう、新規追加とメタデータ更新のみを DAO 側のトランザクションで行う。
            val discovery = discoverCourses()
            if (discovery.loginRequired) return@withContext FetchResult.LoginRequired
            var failedRequests = discovery.failedRequests
            if (discovery.courses.isNotEmpty()) {
                courseDao.mergeDiscovered(discovery.courses)
            }
            val enabled = courseDao.getEnabled()

            // コース一覧が空で、その原因が取得失敗なら「0件成功」にしない。
            if (enabled.isEmpty()) {
                return@withContext if (failedRequests > 0) {
                    FetchResult.NetworkError("コース一覧を取得できませんでした")
                } else {
                    FetchResult.Success(0, emptyList(), emptyList(), emptyList())
                }
            }

            // ② 課題候補抽出（同時3並列）
            val courseFetches = mapWithConcurrency(enabled, 3) { course ->
                when (val page = getPage(course.url)) {
                    is PageResult.Ok -> runCatching {
                        val links = LetusParsing.extractLinks(page.html, course.url)
                            .filter { LetusParsing.isAssignmentLikeLink(it.title, it.url, scanLevel) }
                        CourseFetch(
                            course = course,
                            candidates = links.map { link ->
                                Candidate(
                                    id = LetusParsing.candidateId(course.id, link.url),
                                    courseId = course.id,
                                    courseName = course.name,
                                    title = link.title,
                                    url = link.url,
                                )
                            },
                        )
                    }.getOrElse {
                        CourseFetch(course = course, failed = true)
                    }

                    PageResult.LoginRequired -> CourseFetch(course = course, loginRequired = true)
                    PageResult.Failed -> CourseFetch(course = course, failed = true)
                }
            }
            if (courseFetches.any { it.loginRequired }) {
                return@withContext FetchResult.LoginRequired
            }
            val failedCourseRequests = courseFetches.count { it.failed }
            failedRequests += failedCourseRequests
            if (failedCourseRequests == enabled.size) {
                return@withContext FetchResult.NetworkError("コースページを取得できませんでした")
            }
            val candidates = courseFetches.flatMap { it.candidates }.distinctBy { it.id }

            // ③ 各候補の締切・提出状況（同時5並列）
            val now = System.currentTimeMillis()
            val candidateFetches = mapWithConcurrency(candidates, 5) { c ->
                when (val page = getPage(c.url)) {
                    is PageResult.Ok -> runCatching {
                        val plain = LetusParsing.htmlToPlainText(page.html)
                        val deadlineText = LetusParsing.extractDeadlineText(plain)
                        val deadline = if (deadlineText.isNotEmpty()) {
                            LetusParsing.parseDeadline(deadlineText)
                        } else {
                            null
                        }
                        val submission = LetusParsing.extractSubmissionStatusFromHtml(page.html, c.url)
                        val lifecycle = LetusParsing.resolveLifecycle(plain, submission, deadline, now)
                        val prev = existingBefore[c.id]
                        val current = AssignmentEntity(
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
                        CandidateFetch(
                            assignment = AssignmentMerge.merge(current, prev),
                            // 変更検出はマージ前の今回値で行う。マージ後では解析失敗時に
                            // 前回の締切が残り、今回の解析状態を区別できない。
                            changeLabels = AssignmentMerge.changeLabels(prev, current),
                        )
                    }.getOrElse {
                        CandidateFetch(failed = true)
                    }

                    PageResult.LoginRequired -> CandidateFetch(loginRequired = true)
                    PageResult.Failed -> CandidateFetch(failed = true)
                }
            }
            if (candidateFetches.any { it.loginRequired }) {
                return@withContext FetchResult.LoginRequired
            }

            val failedDetailRequests = candidateFetches.count { it.failed }
            failedRequests += failedDetailRequests
            // 候補は存在するのに全件の詳細取得に失敗した場合は、成功0件扱いにしない。
            if (candidates.isNotEmpty() && failedDetailRequests == candidates.size) {
                return@withContext FetchResult.NetworkError("課題ページを取得できませんでした")
            }

            val assignments = candidateFetches.mapNotNull { it.assignment }
            // 1件も更新できず、かつ失敗したページがある場合は、成功0件として扱わない。
            if (assignments.isEmpty() && failedRequests > 0) {
                return@withContext FetchResult.NetworkError("課題を取得できませんでした")
            }
            if (assignments.isNotEmpty()) assignmentDao.upsertAll(assignments)

            val isInitialImport = existingBefore.isEmpty()
            val newOnes = assignments.filter {
                existingBefore[it.id] == null && AssignmentMerge.isNewNotificationEligible(it, now)
            }
            val dueSoon = assignments.filter {
                !it.ignored &&
                    it.submissionStatus != org.cabetus.data.local.SubmissionStatus.SUBMITTED &&
                    it.submissionStatus != org.cabetus.data.local.SubmissionStatus.COMPLETED &&
                    it.deadline != null &&
                    it.deadline in (now + 1)..(now + 24L * 60 * 60 * 1000) &&
                    it.lifecycleStatus == org.cabetus.data.local.LifecycleStatus.ACTIVE
            }
            // 既存課題の変更（締切・タイトル）を検出。新規は newOnes 側で扱うため除外。
            // 締切は deadlineText 全体ではなく解析済みの締切時刻で比較する。
            // 差分は upsert 前に取得した existingBefore と比較するので、次回は再通知されない。
            val changed = candidateFetches.mapNotNull { fetch ->
                val assignment = fetch.assignment ?: return@mapNotNull null
                if (fetch.changeLabels.isEmpty()) {
                    null
                } else {
                    AssignmentChange(assignment, fetch.changeLabels)
                }
            }

            FetchResult.Success(
                detected = assignments.size,
                newAssignments = newOnes,
                dueSoon = dueSoon,
                changed = changed,
                failedRequests = failedRequests,
                isInitialImport = isInitialImport,
            )
        }

    /**
     * 履修コースを列挙する。
     * Moodle 4 の /my/ 系ページはコース一覧をJSで描画するためHTMLに
     * リンクが含まれないことがある。そのため、まずページから sesskey を取り
     * AJAX API（core_course_get_enrolled_courses_by_timeline_classification）で
     * 確実に列挙し、失敗時のみHTMLリンク走査へフォールバックする。
     */
    private fun discoverCourses(): CourseDiscovery {
        val now = System.currentTimeMillis()
        val map = LinkedHashMap<String, MoodleCourseEntity>()
        var failedRequests = 0

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
        val myPage = getPage(LetusConstants.MY_URL)
        if (myPage == PageResult.LoginRequired) {
            return CourseDiscovery(emptyList(), failedRequests, loginRequired = true)
        }
        if (myPage == PageResult.Failed) failedRequests++
        val myHtml = (myPage as? PageResult.Ok)?.html

        val sesskey = myHtml?.let { LetusParsing.extractSesskey(it) }
        if (sesskey != null) {
            val ajax = fetchEnrolledCoursesViaAjax(sesskey)
            if (ajax.loginRequired) {
                return CourseDiscovery(emptyList(), failedRequests, loginRequired = true)
            }
            if (ajax.courses != null) {
                ajax.courses.forEach { add(it.title, it.url) }
            } else if (ajax.failed) {
                failedRequests++
            }
        }

        // ② フォールバック: サーバーレンダリングHTMLのリンク走査
        if (map.isEmpty()) {
            val pages = mutableListOf<Pair<String, String>>()
            if (myHtml != null) pages += LetusConstants.MY_URL to myHtml
            for (indexUrl in LetusConstants.COURSE_INDEX_URLS) {
                if (indexUrl == LetusConstants.MY_URL) continue
                when (val page = getPage(indexUrl)) {
                    is PageResult.Ok -> pages += indexUrl to page.html
                    PageResult.LoginRequired ->
                        return CourseDiscovery(emptyList(), failedRequests, loginRequired = true)
                    PageResult.Failed -> failedRequests++
                }
            }
            for ((baseUrl, html) in pages) {
                LetusParsing.extractLinks(html, baseUrl).forEach { add(it.title, it.url) }
            }
        }

        // フォールバックでコースを発見できた場合、AJAX等の失敗は結果に影響しない。
        if (map.isNotEmpty()) failedRequests = 0
        return CourseDiscovery(map.values.toList(), failedRequests, loginRequired = false)
    }

    private fun fetchEnrolledCoursesViaAjax(sesskey: String): AjaxCourses = try {
        val url = LetusConstants.AJAX_SERVICE_URL +
            "?sesskey=$sesskey&info=core_course_get_enrolled_courses_by_timeline_classification"
        val body = LetusParsing.enrolledCoursesRequestBody()
            .toRequestBody("application/json".toMediaType())
        client.newCall(Request.Builder().url(url).post(body).build()).execute().use { resp ->
            if (!resp.isSuccessful) return AjaxCourses(failed = true)
            if (!LetusParsing.isAuthenticatedLetusUrl(resp.request.url.toString())) {
                return AjaxCourses(loginRequired = true)
            }
            val text = resp.body?.string().orEmpty()
            if (LetusParsing.isNotLoggedInPage(text)) return AjaxCourses(loginRequired = true)
            val courses = LetusParsing.parseEnrolledCoursesOrNull(text)
            AjaxCourses(courses = courses, failed = courses == null)
        }
    } catch (_: Exception) {
        AjaxCourses(failed = true)
    }

    private fun getPage(url: String): PageResult = try {
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) return PageResult.Failed
            if (!LetusParsing.isAuthenticatedLetusUrl(resp.request.url.toString())) {
                return PageResult.LoginRequired
            }
            val html = resp.body?.string().orEmpty()
            if (LetusParsing.isNotLoggedInPage(html)) PageResult.LoginRequired
            else PageResult.Ok(html)
        }
    } catch (_: Exception) {
        PageResult.Failed
    }

    private sealed interface PageResult {
        data class Ok(val html: String) : PageResult
        data object LoginRequired : PageResult
        data object Failed : PageResult
    }

    private data class AjaxCourses(
        val courses: List<LetusParsing.Link>? = null,
        val loginRequired: Boolean = false,
        val failed: Boolean = false,
    )

    private data class CourseDiscovery(
        val courses: List<MoodleCourseEntity>,
        val failedRequests: Int,
        val loginRequired: Boolean,
    )

    private data class CourseFetch(
        val course: MoodleCourseEntity,
        val candidates: List<Candidate> = emptyList(),
        val failed: Boolean = false,
        val loginRequired: Boolean = false,
    )

    private data class CandidateFetch(
        val assignment: AssignmentEntity? = null,
        /** マージ前の今回値と前回値から求めた変更通知の文言。 */
        val changeLabels: List<String> = emptyList(),
        val failed: Boolean = false,
        val loginRequired: Boolean = false,
    )

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
