package org.cabetus.domain

import java.text.Normalizer

/**
 * CLASS の授業変更掲示（タイトルだけのスナップショット）を、登録済み科目へ保守的に振り分ける純粋ロジック。
 *
 * 誤った科目へ掲示を付けないため、次の場合だけ科目を確定する。
 * - 認識できる種類の接頭辞（`休講:`, `【教室変更】` など）の後ろが、正規化した科目名と完全一致する。
 * - `科目名:` / `科目コード:` の明示フィールドがあり、値が区切られている。
 *
 * 日付・教室・年度・読み・部分一致からの推測はしない。複数の候補・矛盾する明示フィールド・
 * 途中で切れたタイトルは [Result.unmatched] に残す。タイトルは元の表記と入力順のまま保持する。
 */
object ClassScheduleNoticeOrganizer {

    data class Course(val code: String, val name: String)

    enum class Kind { CANCELLATION, ROOM_CHANGE, OTHER }

    data class Notice(val title: String, val kind: Kind)

    data class Result(
        val byCourse: Map<String, List<Notice>>,
        val unmatched: List<Notice>,
    )

    private const val MAX_TITLE_LENGTH = 500
    private const val MAX_FIELD_LENGTH = 200

    /** 時間割PDF・CLASS Web と同じ、大文字英数字7桁の正式な科目コード。 */
    private val OFFICIAL_COURSE_CODE = Regex("^[0-9A-Z]{7}$")
    private val SPACES = Regex("\\s+")

    private const val KIND_LABEL_PATTERN = "休講取消|休講|教室変更|教室移動|実施場所変更"

    /** `休講:`, `教室変更 -` のような、区切りを伴う接頭辞だけを受け付ける。 */
    private val PLAIN_KIND_PREFIX = Regex("^($KIND_LABEL_PATTERN)\\s*(?:[:\\-–—]\\s*|\\s+)")

    /** `【教室変更】`, `[休講]:` のような括り付き接頭辞。 */
    private val BRACKET_KIND_PREFIXES = listOf(
        Regex("^【\\s*($KIND_LABEL_PATTERN)\\s*】\\s*(?:[:\\-–—]\\s*)?"),
        Regex("^\\[\\s*($KIND_LABEL_PATTERN)\\s*\\]\\s*(?:[:\\-–—]\\s*)?"),
    )

    private val KIND_BY_LABEL = mapOf(
        "休講取消" to Kind.OTHER,
        "休講" to Kind.CANCELLATION,
        "教室変更" to Kind.ROOM_CHANGE,
        "教室移動" to Kind.ROOM_CHANGE,
        "実施場所変更" to Kind.ROOM_CHANGE,
    )

    // A field is closed only by its own paired brackets, another explicitly labelled field,
    // or the end of the title. A slash/comma alone may be separating multiple course names.
    private val FIELD_LABEL = Regex("(?<![^\\s\\[【「(,:;|])(科目名|科目コード)\\s*:")
    private val FIELD_SEPARATOR = Regex("[\\s,;|、]+$")
    private val BRACKETS = mapOf('[' to ']', '【' to '】', '「' to '」', '(' to ')')
    private val CLOSING_DELIMITERS = setOf(']', '】', '」', '』', ')', '}')

    private data class KindPrefix(val kind: Kind, val remainder: String)

    private sealed interface FieldParse {
        data object None : FieldParse
        data object Invalid : FieldParse
        data class Parsed(val name: String?, val code: String?) : FieldParse
    }

    private data class CourseIndex(
        val byCode: Map<String, List<Course>>,
        val byName: Map<String, List<Course>>,
    )

    /**
     * @param titles CLASS の掲示タイトル（元の表示順）
     * @param courses 登録済み科目。同じコードの重複（同じ科目の複数コマ）は1件に畳み込む。
     */
    fun organize(titles: List<String>, courses: Collection<Course>): Result {
        val index = buildIndex(courses)
        val byCourse = LinkedHashMap<String, MutableList<Notice>>()
        val unmatched = mutableListOf<Notice>()

        for (title in titles) {
            val normalized = normalizeText(title)
            val prefix = kindPrefix(normalized)
            val kind = prefix?.kind ?: Kind.OTHER
            val notice = Notice(title, kind)

            if (normalized.isEmpty() || normalized.length > MAX_TITLE_LENGTH) {
                unmatched += notice
                continue
            }

            val fields = parseFields(normalized)
            val code = when (fields) {
                FieldParse.Invalid -> null
                is FieldParse.Parsed -> resolveExplicit(index, fields.name, fields.code)
                FieldParse.None ->
                    // 接頭辞が無いタイトルは、科目名と完全一致しても掲示として紐付けない。
                    prefix?.let { resolveRemainder(index, it.remainder) }
            }

            if (code == null) {
                unmatched += notice
            } else {
                byCourse.getOrPut(code) { mutableListOf() } += notice
            }
        }

        return Result(
            byCourse = byCourse.mapValues { (_, notices) -> notices.toList() },
            unmatched = unmatched.toList(),
        )
    }

    private fun buildIndex(courses: Collection<Course>): CourseIndex {
        // 同じコード・同じ名前の重複コマは1科目として扱う。同じコードで名前が違う場合は
        // コードだけでは科目を確定できないため、明示コードの解決時に曖昧として扱う。
        val distinct = LinkedHashMap<Pair<String, String>, Course>()
        for (course in courses) {
            if (course.code.isEmpty()) continue
            distinct[course.code to course.name] = course
        }
        val uniqueCourses = distinct.values.toList()
        return CourseIndex(
            byCode = uniqueCourses
                .filter { OFFICIAL_COURSE_CODE.matches(it.code) }
                .groupBy { it.code },
            byName = uniqueCourses
                .filter { normalizeText(it.name).let { name -> name.isNotEmpty() && name != it.code } }
                .groupBy { normalizeText(it.name) },
        )
    }

    private fun resolveRemainder(index: CourseIndex, remainder: String): String? {
        if (remainder.isEmpty()) return null
        // 値の途中に閉じ括弧が残るものは、区切られた科目名として扱わない。
        if (remainder.first() in CLOSING_DELIMITERS) return null
        val candidates = index.byName[remainder] ?: return null
        return candidates.singleOrNull()?.code
    }

    private fun resolveExplicit(index: CourseIndex, name: String?, code: String?): String? {
        val codeCourse: Course?
        if (code != null) {
            val candidates = index.byCode[code] ?: return null
            if (candidates.size != 1) return null
            codeCourse = candidates[0]
        } else {
            codeCourse = null
        }

        if (codeCourse != null) {
            // 正式コードが一意なら、同じ名前の別コードがあってもコードを優先する。
            // ただし明示された科目名がそのコードの科目名と食い違う場合は確定しない。
            if (name != null && normalizeText(codeCourse.name) != name) return null
            return codeCourse.code
        }

        if (name != null) {
            val candidates = index.byName[name] ?: return null
            return candidates.singleOrNull()?.code
        }
        return null
    }

    private fun parseFields(text: String): FieldParse {
        val matches = FIELD_LABEL.findAll(text).toList()
        if (matches.isEmpty()) return FieldParse.None
        var name: String? = null
        var code: String? = null
        for ((index, match) in matches.withIndex()) {
            val start = match.range.last + 1
            val next = matches.getOrNull(index + 1)
            val after = next?.range?.first ?: text.length
            val before = text.substring(0, match.range.first).trimEnd().lastOrNull()
            val close = before?.let(BRACKETS::get)
            val value = if (close != null) {
                val end = text.indexOf(close, start)
                if (end !in start until after) return FieldParse.Invalid
                text.substring(start, end).trim()
            } else {
                val field = text.substring(start, after).trimEnd()
                val unseparated = if (next == null) field else {
                    FIELD_SEPARATOR.replace(field.trimEnd('[', '【', '「', '('), "")
                }
                unseparated.trim()
            }
            if (value.isEmpty() || value.length > MAX_FIELD_LENGTH || value.first() in CLOSING_DELIMITERS) {
                return FieldParse.Invalid
            }
            when (match.groupValues[1]) {
                "科目名" -> {
                    if (name != null) return FieldParse.Invalid
                    name = value
                }
                "科目コード" -> {
                    if (code != null || !OFFICIAL_COURSE_CODE.matches(value)) return FieldParse.Invalid
                    code = value
                }
            }
        }
        return FieldParse.Parsed(name, code)
    }

    private fun kindPrefix(text: String): KindPrefix? {
        val match = BRACKET_KIND_PREFIXES.firstNotNullOfOrNull { it.find(text) }
            ?: PLAIN_KIND_PREFIX.find(text) ?: return null
        val kind = KIND_BY_LABEL[match.groupValues[1]] ?: return null
        return KindPrefix(kind, text.substring(match.range.last + 1).trim())
    }

    /** NFKC と空白の畳み込みだけを行う。大文字小文字の変更・読み・部分一致はしない。 */
    private fun normalizeText(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .trim()
            .replace(SPACES, " ")
}
