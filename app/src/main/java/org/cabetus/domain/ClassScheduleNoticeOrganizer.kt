package org.cabetus.domain

import java.text.Normalizer
import java.util.Locale

/**
 * CLASS の授業変更掲示（タイトルだけのスナップショット）を、登録済み科目へ保守的に振り分ける純粋ロジック。
 *
 * 誤った科目へ掲示を付けないため、次の場合だけ科目を確定する。
 * - 認識できる種類の接頭辞（`休講:`, `【教室変更】` など）の後ろが、正規化した科目名と完全一致する。
 * - `科目名:` / `科目コード:` の明示フィールドがあり、値が区切られている。
 * - 明示フィールドが無いタイトルに、区切られた正式な科目コード（または登録済みのコード別名）が1つだけある。
 * - 明示フィールドが無いタイトルに、掲示の文法で区切られた科目名が1つだけある。
 *
 * 日付・教室・年度・読み・部分一致からの推測はしない。複数の候補・矛盾する明示フィールド・
 * 途中で切れたタイトルは [Result.unmatched] に残す。タイトルは元の表記と入力順のまま保持する。
 */
object ClassScheduleNoticeOrganizer {

    data class Course(
        val code: String,
        val name: String,
        /** CLASS の掲示で正式コードの代わりに使われる、大文字英数字7桁のコード。 */
        val officialCodeAlias: String? = null,
    )

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
    private val FIELD_LABEL = Regex("(?<![^\\s\\[【「(,:;|、])(科目名|科目コード)\\s*:")
    private val FIELD_SEPARATOR = Regex("[\\s,;|、]+$")
    private val BRACKETS = mapOf(
        '[' to ']', '【' to '】', '「' to '」', '『' to '』', '(' to ')', '{' to '}',
    )
    private val CLOSING_DELIMITERS = setOf(']', '】', '」', '』', ')', '}')

    /**
     * タイトル中に現れる正式コード・コード別名のトークン。前後が空白か
     * [CODE_BOUNDARY_CHARS] のときだけ採用する。`_`, `-`, `/` などは境界として扱わない。
     */
    private val CODE_TOKEN = Regex("[0-9A-Za-z]{7}")
    private const val CODE_BOUNDARY_CHARS =
        "()[]{}【】「」『』〈〉《》<>＜＞,，、.。:：;；!?！？*＊'\"“”‘’・"

    /** 科目名の直前として許す、句読点・括弧・助詞。 */
    private const val NAME_LEFT_BOUNDARY_CHARS =
        "【】[](){}「」『』〈〉《》<>＜＞,，、.。:：;；!?！？*＊'\"“”‘’・"
    private const val NAME_LEFT_PARTICLES = "のにはがをでへとも"

    /** 科目名の直後として許す区切り。`/` は複数科目の可能性があるため含めない。 */
    private const val NAME_TRAILING_SEPARATORS =
        " \t\u3000、。,.!?！？:：;；・-–—〜~|｜*＊'\"“”‘’"

    private val KIND_IN_TEXT = Regex(KIND_LABEL_PATTERN)
    private const val KIND_BOUNDARY_CHARS =
        "【】[](){}「」『』〈〉《》<>＜＞,，、.。:：;；!?！？*＊'\"“”‘’・-–—〜~|｜のにはがをでへとも"

    /** `...の教室変更について`, `...の休講` のような、科目名の後ろに続く掲示の文法。 */
    private val KIND_TRAILING_TOKEN =
        Regex("^の?(?:$KIND_LABEL_PATTERN)(?:についてのお知らせ|について|のお知らせ|の連絡|等)?")
    private val COMMON_TRAILING_TOKEN =
        Regex("^(?:差し替え|差替|補講実施|補講|振替|変更|中止|実施|延期)")
    private val DECORATION_TOKEN = Regex(
        "^(?:\\d{1,4}(?:[/\\-]\\d{1,2}|月\\d{1,2}日?|月|日|時限?|限|時|分|回|週|年)?|[月火水木金土日]曜日?)",
    )

    private data class KindPrefix(val kind: Kind, val remainder: String)

    private sealed interface FieldParse {
        data object None : FieldParse
        data object Invalid : FieldParse
        data class Parsed(val name: String?, val code: String?) : FieldParse
    }

    private sealed interface EmbeddedCode {
        data object None : EmbeddedCode
        data class Resolved(val code: String) : EmbeddedCode
        data object Conflict : EmbeddedCode
    }

    private data class NameSpan(val name: String, val start: Int, val end: Int)

    private data class CourseIndex(
        val byName: Map<String, List<Course>>,
        val byToken: Map<String, List<Course>>,
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
            val embeddedKind = inferKind(normalized)
            val kind = prefix?.kind ?: embeddedKind ?: Kind.OTHER
            val notice = Notice(title, kind)

            if (normalized.isEmpty() || normalized.length > MAX_TITLE_LENGTH || isMalformedTitle(normalized)) {
                unmatched += notice
                continue
            }

            val fields = parseFields(normalized)
            val code = when (fields) {
                FieldParse.Invalid -> null
                is FieldParse.Parsed -> resolveExplicit(index, fields.name, fields.code)
                FieldParse.None -> resolveEmbedded(
                    index,
                    normalized,
                    allowName = prefix != null || embeddedKind != null,
                )
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
        // 同じコード・同じ名前・同じ別名の重複コマは1科目として扱う。同じコードで名前が違う場合は
        // コードだけでは科目を確定できないため、明示コードの解決時に曖昧として扱う。
        val distinct = LinkedHashMap<Triple<String, String, String?>, Course>()
        for (course in courses) {
            if (course.code.isEmpty()) continue
            val alias = course.officialCodeAlias
                ?.let { normalizeText(it).uppercase(Locale.ROOT) }
                ?.takeIf { OFFICIAL_COURSE_CODE.matches(it) }
            val normalized = course.copy(officialCodeAlias = alias)
            distinct[Triple(course.code, course.name, alias)] = normalized
        }
        val uniqueCourses = distinct.values.toList()

        val tokenCourses = LinkedHashMap<String, MutableList<Course>>()
        fun addToken(token: String, course: Course) {
            val tokenEntries = tokenCourses.getOrPut(token) { mutableListOf() }
            if (tokenEntries.none { it.code == course.code && it.name == course.name }) {
                tokenEntries += course
            }
        }
        for (course in uniqueCourses) {
            if (OFFICIAL_COURSE_CODE.matches(course.code)) addToken(course.code, course)
            course.officialCodeAlias?.let { addToken(it, course) }
        }

        return CourseIndex(
            byName = uniqueCourses
                .filter { normalizeText(it.name).let { name -> name.isNotEmpty() && name != it.code } }
                .groupBy { normalizeText(it.name) },
            byToken = tokenCourses.mapValues { (_, tokenEntries) -> tokenEntries.toList() },
        )
    }

    private fun resolveExplicit(index: CourseIndex, name: String?, code: String?): String? {
        val codeCourse: Course?
        if (code != null) {
            val candidates = index.byToken[code] ?: return null
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

    /** 明示フィールドが無いタイトルだけを対象に、コードと科目名の強い手がかりを突き合わせる。 */
    private fun resolveEmbedded(index: CourseIndex, text: String, allowName: Boolean): String? {
        val embeddedCode = findEmbeddedCode(index, text)
        if (embeddedCode is EmbeddedCode.Conflict) return null
        val code = (embeddedCode as? EmbeddedCode.Resolved)?.code

        val spans = findNameSpans(index, text, allowName)
        val spanNames = linkedSetOf<String>()
        val nameCodes = linkedSetOf<String>()
        for (span in spans) {
            val courses = index.byName[span.name] ?: continue
            spanNames += span.name
            for (course in courses) nameCodes += course.code
        }

        // 別々の科目名が同時に現れたら、どちらか一方を選ばない。
        if (spanNames.size > 1) return null
        if (nameCodes.isEmpty()) return code
        if (nameCodes.size == 1) {
            val nameCode = nameCodes.first()
            // 一意な科目名と、それとは別の科目を指すコードが同居する場合は確定しない。
            return if (code == null || code == nameCode) nameCode else null
        }
        // 同名で複数コードの科目がある場合だけ、明示コードで一意に絞り込める。
        return if (code != null && code in nameCodes) code else null
    }

    private fun findEmbeddedCode(index: CourseIndex, text: String): EmbeddedCode {
        val codes = linkedSetOf<String>()
        for (match in CODE_TOKEN.findAll(text)) {
            if (!isCodeBoundary(text, match.range.first - 1)) continue
            if (!isCodeBoundary(text, match.range.last + 1)) continue
            if (isInUrlFragment(text, match.range)) continue
            val courses = index.byToken[match.value.uppercase(Locale.ROOT)] ?: continue
            val candidateCodes = courses.map { it.code }.distinct()
            if (candidateCodes.size != 1) return EmbeddedCode.Conflict
            codes += candidateCodes[0]
        }
        return when (codes.size) {
            0 -> EmbeddedCode.None
            1 -> EmbeddedCode.Resolved(codes.first())
            else -> EmbeddedCode.Conflict
        }
    }

    private fun findNameSpans(index: CourseIndex, text: String, allowUnquoted: Boolean): List<NameSpan> {
        val spans = mutableListOf<NameSpan>()
        for (name in index.byName.keys) {
            var from = 0
            while (from < text.length) {
                val start = text.indexOf(name, from)
                if (start < 0) break
                val end = start + name.length
                if (isAllowedNameLeftBoundary(text, start) &&
                    (isQuotedName(text, start, end) || (allowUnquoted && isAllowedNameTrailing(text.substring(end))))
                ) {
                    spans += NameSpan(name, start, end)
                }
                from = start + 1
            }
        }
        if (spans.size <= 1) return spans
        // 長い科目名が短い科目名を含む場合は、長い方の一致だけを残す。
        return spans.filter { span ->
            spans.none { other ->
                other !== span &&
                    other.start <= span.start &&
                    other.end >= span.end &&
                    (other.end - other.start) > (span.end - span.start)
            }
        }
    }

    private fun isQuotedName(text: String, start: Int, end: Int): Boolean {
        val open = text.getOrNull(start - 1) ?: return false
        val close = BRACKETS[open] ?: return false
        return text.getOrNull(end) == close
    }

    private fun isAllowedNameLeftBoundary(text: String, start: Int): Boolean {
        if (start == 0) return true
        val before = text[start - 1]
        if (before.isWhitespace() ||
            before in NAME_LEFT_BOUNDARY_CHARS ||
            before in NAME_LEFT_PARTICLES
        ) {
            return true
        }
        // 日付・時限に直接続く場合だけ許可する（例: 11月21日物理学...、5限物理...）。
        return before in "日月限時分" && start >= 2 && text[start - 2].isDigit()
    }

    private fun isAllowedNameTrailing(rest: String): Boolean {
        var index = 0
        while (index < rest.length) {
            val ch = rest[index]
            if (ch.isWhitespace() || ch in NAME_TRAILING_SEPARATORS) {
                index++
                continue
            }
            val close = BRACKETS[ch]
            if (close != null) {
                val end = rest.indexOf(close, index + 1)
                if (end < 0) return false
                val inner = rest.substring(index + 1, end).trim()
                if (!isAllowedBracketTrailing(inner)) return false
                index = end + 1
                continue
            }
            val remaining = rest.substring(index)
            val match = matchAtStart(KIND_TRAILING_TOKEN, remaining)
                ?: matchAtStart(COMMON_TRAILING_TOKEN, remaining)
                ?: matchAtStart(DECORATION_TOKEN, remaining)
                ?: return false
            if (match.value.isEmpty()) return false
            index += match.value.length
        }
        return true
    }

    private fun isAllowedBracketTrailing(inner: String): Boolean {
        if (inner.isEmpty()) return false
        if (OFFICIAL_COURSE_CODE.matches(inner)) return true
        return isAllowedNameTrailing(inner)
    }

    private fun matchAtStart(regex: Regex, text: String): MatchResult? =
        regex.find(text)?.takeIf { it.range.first == 0 }

    private fun isCodeBoundary(text: String, index: Int): Boolean {
        if (index !in text.indices) return true
        val ch = text[index]
        return ch.isWhitespace() || ch in CODE_BOUNDARY_CHARS
    }

    private fun isInUrlFragment(text: String, range: IntRange): Boolean {
        var start = range.first
        while (start > 0 && !text[start - 1].isWhitespace()) start--
        var end = range.last + 1
        while (end < text.length && !text[end].isWhitespace()) end++
        val token = text.substring(start, end)
        if ("://" in token || token.startsWith("www.") || '/' in token) return true
        val dot = token.indexOf('.')
        return dot >= 0 && dot != token.lastIndex
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
                    val normalizedCode = value.uppercase(Locale.ROOT)
                    if (code != null || !OFFICIAL_COURSE_CODE.matches(normalizedCode)) return FieldParse.Invalid
                    code = normalizedCode
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

    /** 接頭辞の無いタイトルでも、括弧や助詞で区切られた種類だけを控えめに拾う。 */
    private fun inferKind(text: String): Kind? {
        val kinds = mutableSetOf<Kind>()
        var from = 0
        while (from <= text.length) {
            val match = KIND_IN_TEXT.find(text, from) ?: break
            if (isKindBoundary(text, match.range.first - 1) &&
                isKindBoundary(text, match.range.last + 1)
            ) {
                KIND_BY_LABEL[match.value]?.let(kinds::add)
            }
            from = match.range.first + 1
        }
        return kinds.singleOrNull()
    }

    private fun isKindBoundary(text: String, index: Int): Boolean {
        if (index !in text.indices) return true
        val ch = text[index]
        return ch.isWhitespace() || ch in KIND_BOUNDARY_CHARS
    }

    /** 先頭の括弧が閉じていない、途中で切れたタイトルは手がかりがあっても採用しない。 */
    private fun isMalformedTitle(text: String): Boolean {
        val first = text.firstOrNull() ?: return true
        if (first in CLOSING_DELIMITERS) return true
        val close = BRACKETS[first] ?: return false
        return text.indexOf(close) < 0
    }

    /** NFKC と空白の畳み込みだけを行う。大文字小文字の変更・読み・部分一致はしない。 */
    private fun normalizeText(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .trim()
            .replace(SPACES, " ")
}
