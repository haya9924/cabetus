package org.cabetus.domain

import org.cabetus.data.letus.LetusConstants
import java.net.URI
import java.text.Normalizer

/**
 * ホームの「実施中授業」から、その授業の LETUS（Moodle）コースページを開くための純粋リゾルバ。
 *
 * 目的は「別の授業のページへ飛ばさない」ことなので、次の順序で**一意に決まったときだけ**
 * コースページを返す。
 *
 * 1. 明示マッピング（CLASS の科目コード → LETUS のコース URL）がある場合はそれを使う。
 *    ただし URL が LETUS のコースページとして検証できない場合は、科目名での推測へは
 *    フォールバックしない（誤った授業を開くより、トップへ逃がす）。
 * 2. 明示マッピングが無い場合は、**LETUS コース名の末尾にある括弧書きの科目コード**を調べ、
 *    CLASS の科目コードと完全一致するコースがちょうど 1 件のときだけそのコースを使う。
 *    例: LETUS 側 `Listening & SpeakingⅠab組 (9943116)` と CLASS 側 `9943116`。
 *    科目コードは数字のみに限定しない（`994321G` のような英数字コードも実在する）。
 *    同じコードの候補が複数なら曖昧としてトップへ逃がす。
 * 3. コード候補が 0 件のときだけ、**正規化した科目名の完全一致**を調べる（既存のフォールバック）。
 *    一致するコースがちょうど 1 件のときだけそのコースを使う。
 *    0 件（見つからない）・複数件（曖昧）・URL 不正のときはトップページへ逃がす。
 *
 * 正規化は NFKC（全角英数字・互換文字の統一、全角括弧→半角括弧）と空白の畳み込みだけを行う。
 * 大文字小文字の無視・部分一致・読み・表記ゆれの推測はしない（別授業へ飛ばさないため）。
 *
 * Room や Android に依存しないので JVM でユニットテストできる。
 */
object LetusClassLinkResolver {

    /** 授業を特定できないときに開く LETUS のトップページ。 */
    const val LETUS_TOP_URL = "https://letus.ed.tus.ac.jp/"

    const val COURSE_BUTTON_LABEL = "この授業のLETUSを開く"
    const val TOP_BUTTON_LABEL = "LETUSトップを開く（授業を特定できません）"

    /** LETUS のコースを表す最小限の入力（Room の Entity に依存しない）。 */
    data class MoodleCourseRef(
        val id: String,
        val name: String,
        val url: String,
    )

    /** コースを特定できた根拠。 */
    enum class Source {
        /** 明示マッピング（CLASS 科目コード → LETUS コース URL）。 */
        EXPLICIT_MAPPING,

        /** LETUS コース名の末尾の括弧書き（科目コード）と CLASS の科目コードの完全一致（一意）。 */
        COURSE_CODE,

        /** 正規化した科目名の完全一致（一意）。 */
        NAME_MATCH,
    }

    /** コースを特定できずトップページへ逃がした理由。 */
    enum class FallbackReason {
        /** 科目名を確認できない（空・コードのみなど）。 */
        UNKNOWN_COURSE_NAME,

        /** 科目コードも、正規化した科目名も完全一致するコースが無い。 */
        NO_MATCHING_COURSE,

        /** 科目コードまたは科目名が一致するコースが複数ある。 */
        AMBIGUOUS_COURSE_NAME,

        /** 一致したコースの URL を LETUS のコースページとして検証できない。 */
        INVALID_COURSE_URL,

        /** 明示マッピングの URL を LETUS のコースページとして検証できない。 */
        INVALID_EXPLICIT_MAPPING,
    }

    sealed interface Result {
        /** 実際に開く URL。必ず LETUS（コースページまたはトップ）。 */
        val url: String

        /** 授業のコースページを特定できたか。false のときはトップページ。 */
        val isCourseSpecific: Boolean

        /** ボタンに出す文言。 */
        val buttonLabel: String

        /** ボタン下に出す補足（どのコースを開くか／なぜ特定できないか）。 */
        val detailText: String

        data class Course(
            /** 正規化して組み立てたコースページ URL（`.../course/view.php?id=ID`）。 */
            override val url: String,
            /** Moodle のコース ID（数字のみ）。 */
            val courseId: String,
            /** LETUS 側のコース名（利用者に開く先を示すため）。 */
            val courseName: String,
            val source: Source,
        ) : Result {
            override val isCourseSpecific: Boolean get() = true

            override val buttonLabel: String get() = COURSE_BUTTON_LABEL

            override val detailText: String
                get() = "LETUSの「$courseName」を開きます（" + when (source) {
                    Source.EXPLICIT_MAPPING -> "この授業に明示的に対応付け済み"
                    Source.COURSE_CODE -> "コース名末尾の科目コードがこの授業と一致"
                    Source.NAME_MATCH -> "科目名の完全一致"
                } + "）。"
        }

        data class LetusTop(
            val reason: FallbackReason,
        ) : Result {
            override val url: String get() = LETUS_TOP_URL

            override val isCourseSpecific: Boolean get() = false

            override val buttonLabel: String get() = TOP_BUTTON_LABEL

            override val detailText: String
                get() = when (reason) {
                    FallbackReason.UNKNOWN_COURSE_NAME ->
                        "科目名を確認できないため、授業のLETUSコースを特定しません。トップページを開きます。"

                    FallbackReason.NO_MATCHING_COURSE ->
                        "LETUSに同じ科目コード・同じ科目名のコースが見つからないため、授業のLETUSコースを特定しません。トップページを開きます。"

                    FallbackReason.AMBIGUOUS_COURSE_NAME ->
                        "LETUSに同じ科目コードまたは同じ科目名のコースが複数あるため、授業のLETUSコースを特定しません。トップページを開きます。"

                    FallbackReason.INVALID_COURSE_URL ->
                        "一致したコースのURLを確認できないため、授業のLETUSコースを特定しません。トップページを開きます。"

                    FallbackReason.INVALID_EXPLICIT_MAPPING ->
                        "この授業のLETUSコースの対応付けを確認できないため、トップページを開きます。"
                }
        }
    }

    /**
     * 実施中授業の LETUS リンク先を決める。
     *
     * @param courseCode CLASS の科目コード（明示マッピングのキー）。
     * @param courseName 画面に出す科目名（時間割セル or 科目マスタ）。
     * @param explicitUrlByCourseCode 明示マッピング。既存データに存在しない場合は空。
     * @param moodleCourses 発見済みの LETUS コース（[MoodleCourseRef]）。
     */
    fun resolve(
        courseCode: String,
        courseName: String,
        explicitUrlByCourseCode: Map<String, String> = emptyMap(),
        moodleCourses: List<MoodleCourseRef>,
    ): Result {
        // 1) 明示マッピングを最優先。あるのに不正なら推測せずトップへ。
        val explicitUrl = explicitUrlByCourseCode[courseCode]?.trim()
        if (!explicitUrl.isNullOrEmpty()) {
            val id = courseViewId(explicitUrl)
                ?: return Result.LetusTop(FallbackReason.INVALID_EXPLICIT_MAPPING)
            val moodleName = moodleCourses
                .firstOrNull { courseViewId(it.url) == id }
                ?.name
                ?.let(::normalizeName)
                ?.takeIf { it.isNotEmpty() }
            return Result.Course(
                url = courseUrl(id),
                courseId = id,
                courseName = moodleName ?: "この授業",
                source = Source.EXPLICIT_MAPPING,
            )
        }

        // 2) LETUS コース名の末尾の括弧書き（科目コード）と CLASS の科目コードの完全一致。
        //    例: "Listening & SpeakingⅠab組 (9943116)" と CLASS の 9943116。
        //    数字のみに限定しない（994321G のような英数字コードも実在する）。
        //    大文字小文字は区別し、部分一致・あいまい一致はしない。
        val codeKey = normalizeName(courseCode)
        if (codeKey.isNotEmpty()) {
            // 同じコースが重複して保存されていても「曖昧」にはしない。
            val codeMatches = moodleCourses
                .filter { trailingParenthesizedCode(it.name) == codeKey }
                .distinctBy { courseViewId(it.url) ?: "raw:${it.url.trim()}" }
            if (codeMatches.isNotEmpty()) {
                return when (codeMatches.size) {
                    1 -> {
                        val match = codeMatches.single()
                        val id = courseViewId(match.url)
                            ?: return Result.LetusTop(FallbackReason.INVALID_COURSE_URL)
                        Result.Course(
                            url = courseUrl(id),
                            courseId = id,
                            courseName = normalizeName(match.name),
                            source = Source.COURSE_CODE,
                        )
                    }

                    else -> Result.LetusTop(FallbackReason.AMBIGUOUS_COURSE_NAME)
                }
            }
        }

        // 3) 科目名の正規化完全一致。一意なときだけ採用する。
        val nameKey = normalizeName(courseName)
        if (nameKey.isEmpty() || nameKey == courseCode.trim()) {
            return Result.LetusTop(FallbackReason.UNKNOWN_COURSE_NAME)
        }

        // 同じコースが重複して保存されていても「曖昧」にはしない。
        val matches = moodleCourses
            .filter { normalizeName(it.name) == nameKey }
            .distinctBy { courseViewId(it.url) ?: "raw:${it.url.trim()}" }

        return when (matches.size) {
            0 -> Result.LetusTop(FallbackReason.NO_MATCHING_COURSE)
            1 -> {
                val match = matches.single()
                val id = courseViewId(match.url)
                    ?: return Result.LetusTop(FallbackReason.INVALID_COURSE_URL)
                Result.Course(
                    url = courseUrl(id),
                    courseId = id,
                    courseName = normalizeName(match.name),
                    source = Source.NAME_MATCH,
                )
            }

            else -> Result.LetusTop(FallbackReason.AMBIGUOUS_COURSE_NAME)
        }
    }

    /**
     * LETUS のコースページ URL からコース ID を取り出す。
     * 次のすべてを満たすときだけ ID を返し、それ以外は null（推測しない）。
     *
     * - `https`・userInfo 無し・ポートは既定（443）
     * - ホストが [LetusConstants.HOST] と完全一致（サブドメイン・類似ホストは不可）
     * - パスが `/course/view.php` と完全一致
     * - クエリ `id` が数字のみ
     */
    fun courseViewId(url: String?): String? {
        val uri = runCatching { URI(url?.trim().orEmpty()) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.userInfo != null) return null
        if (uri.port != -1 && uri.port != 443) return null
        val host = uri.host?.trim()?.trimEnd('.')?.lowercase() ?: return null
        if (host != LetusConstants.HOST) return null
        if (uri.path != LetusConstants.COURSE_VIEW) return null
        val query = uri.rawQuery ?: return null
        val id = query.split('&')
            .firstOrNull { it.substringBefore('=') == "id" }
            ?.substringAfter('=', "")
            .orEmpty()
        return id.takeIf { COURSE_ID.matches(it) }
    }

    /** コース ID から正規のコースページ URL を組み立てる。 */
    fun courseUrl(courseId: String): String =
        LetusConstants.BASE + LetusConstants.COURSE_VIEW + "?id=" + courseId

    /**
     * 科目名の正規化。NFKC（全角英数字・互換文字の統一）と空白の畳み込みだけを行い、
     * 大文字小文字の変更・記号の削除・部分一致はしない。
     */
    fun normalizeName(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replace('\u00a0', ' ')
            .replace('\u3000', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * 正規化したコース名の**末尾**にある括弧書きの中身を取り出す。
     * 例: `Listening & SpeakingⅠab組 (9943116)` → `9943116`。
     * 末尾以外の括弧や、括弧を含まない文字列は対象外（部分一致はしない）。
     */
    private fun trailingParenthesizedCode(name: String): String? =
        TRAILING_PARENTHESIZED
            .find(normalizeName(name))
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /** Moodle のコース ID は数字のみ（過剰に長い値は受け付けない）。 */
    private val COURSE_ID = Regex("^[0-9]{1,12}$")

    /** コース名の末尾の括弧書き（NFKC 済みなので全角括弧は半角になっている）。 */
    private val TRAILING_PARENTHESIZED = Regex("\\(([^()]+)\\)\\s*$")
}
