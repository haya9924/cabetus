package org.cabetus.data.classweb

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.MonthDay

/**
 * CLASS スマートフォン版「出席詳細」の DOM から授業回テーブルを読む純パーサ。
 *
 * 実機で確認できた構造は次のとおり（2025-11 時点の調査メモ）:
 * - `.mainContent` の中に `div[id$=jugyoKaisuTbl].ui-datatable.attendanceInfo` がある
 * - その中の `table` のヘッダーは「回数」「実施日」「曜日時限」「出欠」「試験」
 * - データ行の `tbody` は `id$=jugyoKaisuTbl_data`
 * - 実施日は「04/10」のような年なしの月日表記
 * - 曜日時限は「木1」のような漢字曜日 + 数字
 * - 出欠は「〇」または空欄（空欄は未記録）
 *
 * 年はセルに含まれないため、パーサは年を推測しない。[Row.monthDay] として
 * 月日だけを保持し、年は呼び出し側が文脈（開講年度など）で確定する。
 * 出欠の空欄は「未記録」として扱い、欠席などの分類を勝手に補わない。
 * 解釈できなかった値は黙って捨てず [Issue] として明示する。
 *
 * このパーサは読み取り専用で、Room の既存出席モデル（ClassSessionEntity 等）へは
 * 保存しない。呼び出し側もこの段階では DB へ書き込まない。
 */
object ClassAttendanceDetailParser {

    val REQUIRED_HEADERS: List<String> = listOf("回数", "実施日", "曜日時限", "出欠", "試験")

    private const val CONTAINER_ID_SUFFIX = "jugyoKaisuTbl"
    private const val TBODY_ID_SUFFIX = "jugyoKaisuTbl_data"

    /** 出欠記号の暫定分類。〇=出席、空欄は未記録として null のままにする。 */
    enum class ProvisionalAttendance {
        PRESENT,
        LATE,
        ABSENT,
        HOLIDAY,
        OTHER,
    }

    /** raw を失わないための1行分の中間モデル。 */
    data class Row(
        /** tbody 内のデータ行番号（1始まり）。 */
        val rowIndex: Int,
        val sessionNoText: String,
        val sessionNo: Int?,
        val dateText: String,
        /** 年が明示されている場合だけ設定する。年なしの月日は推測せず null。 */
        val date: LocalDate?,
        /**
         * 年を含まない月日の中間表現。例: 「04/10」→ [MonthDay.of](4, 10)。
         * 年が明示された日付でも設定する。年は呼び出し側が文脈で確定する。
         */
        val monthDay: MonthDay?,
        val dayPeriodText: String,
        /** 1=月 … 7=日。解釈できなければ null。 */
        val dayOfWeek: Int?,
        /** 例: 「木1・2」なら [1, 2]。解釈できなければ空。 */
        val periods: List<Int>,
        val attendanceMark: String,
        /** raw 記号の暫定分類。空欄（未記録）も未知の記号も raw は保持し null。 */
        val attendance: ProvisionalAttendance?,
        val examText: String,
        /** ヘッダー順の生セル文字列。列の増減があっても監査できるようにする。 */
        val rawValues: List<String>,
    ) {
        /** 出欠欄に記録があるか。空欄は未記録として false になり、欠席とは区別される。 */
        val isAttendanceRecorded: Boolean
            get() = attendanceMark.isNotEmpty()

        /**
         * 年が確定した [year] で日付を補完する。
         * セルに年が明示されていればそちらを優先する。2/29 と非閏年など
         * 成立しない組み合わせは null を返し、勝手に別の日付へ丸めない。
         */
        fun resolveDate(year: Int): LocalDate? =
            date ?: monthDay?.let { md ->
                // MonthDay.atYear は 2/29 を 2/28 へ丸めてしまうため、
                // LocalDate.of で検証し、成立しない日付は null にする。
                runCatching { LocalDate.of(year, md.monthValue, md.dayOfMonth) }.getOrNull()
            }
    }

    enum class IssueCode {
        MAIN_CONTENT_NOT_FOUND,
        DATATABLE_NOT_FOUND,
        DATATABLE_AMBIGUOUS,
        TABLE_NOT_FOUND,
        TABLE_AMBIGUOUS,
        HEADER_ROW_NOT_FOUND,
        MISSING_REQUIRED_HEADER,
        DUPLICATE_HEADER,
        UNUSED_HEADER,
        TBODY_NOT_FOUND,
        TBODY_AMBIGUOUS,
        ROW_CELL_COUNT_MISMATCH,
        EMPTY_ROW,
        SESSION_NO_UNPARSED,
        DATE_MISSING,
        DATE_UNPARSED,
        DAY_PERIOD_MISSING,
        DAY_PERIOD_UNPARSED,
        UNKNOWN_ATTENDANCE_MARK,
        DUPLICATE_SESSION_NO,
    }

    enum class IssueSeverity { ERROR, WARNING }

    data class Issue(
        val code: IssueCode,
        val severity: IssueSeverity,
        val message: String,
        /** tbody 内のデータ行番号（1始まり）。行に紐づかない場合は null。 */
        val rowIndex: Int? = null,
        val column: String? = null,
    )

    data class Result(
        /** ヘッダー順の表示文字列（不明な追加列も含む）。 */
        val headers: List<String>,
        val rows: List<Row>,
        val issues: List<Issue>,
    ) {
        val hasErrors: Boolean
            get() = issues.any { it.severity == IssueSeverity.ERROR }

        val errors: List<Issue>
            get() = issues.filter { it.severity == IssueSeverity.ERROR }

        val warnings: List<Issue>
            get() = issues.filter { it.severity == IssueSeverity.WARNING }
    }

    /**
     * HTML 全体（または `.mainContent` を含む断片）から出席詳細テーブルを読む。
     * 構造が想定と違う場合は空の rows と ERROR の issues を返す。
     */
    fun parse(html: String): Result {
        val issues = mutableListOf<Issue>()
        val doc = Jsoup.parse(html)

        val mainContents = doc.select(".mainContent")
        if (mainContents.isEmpty()) {
            return Result(
                headers = emptyList(),
                rows = emptyList(),
                issues = listOf(
                    issue(
                        IssueCode.MAIN_CONTENT_NOT_FOUND,
                        ".mainContent が見つかりません",
                    ),
                ),
            )
        }

        val containers = mainContents
            .flatMap { it.select("div[id]") }
            .filter {
                it.id().endsWith(CONTAINER_ID_SUFFIX) &&
                    it.hasClass("ui-datatable") &&
                    it.hasClass("attendanceInfo")
            }
            .distinct()

        when {
            containers.isEmpty() -> return Result(
                headers = emptyList(),
                rows = emptyList(),
                issues = listOf(
                    issue(
                        IssueCode.DATATABLE_NOT_FOUND,
                        "div[id$=$CONTAINER_ID_SUFFIX].ui-datatable.attendanceInfo が見つかりません",
                    ),
                ),
            )

            containers.size > 1 -> return Result(
                headers = emptyList(),
                rows = emptyList(),
                issues = listOf(
                    issue(
                        IssueCode.DATATABLE_AMBIGUOUS,
                        "出席詳細テーブルの候補が複数あります（${containers.size}件）",
                    ),
                ),
            )
        }

        val container = containers.single()
        val tables = container.select("table")
        if (tables.isEmpty()) {
            return Result(
                headers = emptyList(),
                rows = emptyList(),
                issues = listOf(issue(IssueCode.TABLE_NOT_FOUND, "出席詳細の table が見つかりません")),
            )
        }

        val table = if (tables.size == 1) {
            tables.single()
        } else {
            // 入れ子 table があり得るため、必要なヘッダーを全部含む table を優先する。
            val matching = tables.filter { hasAllRequiredHeaders(it) }
            if (matching.size == 1) {
                matching.single()
            } else {
                return Result(
                    headers = emptyList(),
                    rows = emptyList(),
                    issues = listOf(
                        issue(
                            IssueCode.TABLE_AMBIGUOUS,
                            "出席詳細の table 候補を一意に特定できません（${tables.size}件）",
                        ),
                    ),
                )
            }
        }

        val headerRow = findHeaderRow(table)
        if (headerRow == null) {
            return Result(
                headers = emptyList(),
                rows = emptyList(),
                issues = listOf(
                    issue(IssueCode.HEADER_ROW_NOT_FOUND, "テーブルのヘッダー行が見つかりません"),
                ),
            )
        }

        val headerCells = directCells(headerRow)
        val headers = headerCells.map { normalizeText(it.text()) }
        val columnIndex = LinkedHashMap<String, Int>()
        headerCells.forEachIndexed { index, cell ->
            val label = normalizeHeader(cell.text())
            if (label.isEmpty()) return@forEachIndexed
            val required = REQUIRED_HEADERS.firstOrNull { normalizeHeader(it) == label }
            if (required == null) {
                issues += issue(
                    IssueCode.UNUSED_HEADER,
                    "未知の追加列「${headers[index]}」は raw 値のみ保持します",
                    column = headers[index],
                    severity = IssueSeverity.WARNING,
                )
            } else if (columnIndex.containsKey(required)) {
                issues += issue(
                    IssueCode.DUPLICATE_HEADER,
                    "必須ヘッダー「$required」が重複しています",
                    column = required,
                )
            } else {
                columnIndex[required] = index
            }
        }

        REQUIRED_HEADERS.filterNot { columnIndex.containsKey(it) }.forEach { missing ->
            issues += issue(
                IssueCode.MISSING_REQUIRED_HEADER,
                "必須ヘッダー「$missing」がありません",
                column = missing,
            )
        }

        val tbodies = table.select("tbody").filter { it.id().endsWith(TBODY_ID_SUFFIX) }
        when {
            tbodies.isEmpty() -> {
                issues += issue(
                    IssueCode.TBODY_NOT_FOUND,
                    "tbody[id$=$TBODY_ID_SUFFIX] が見つかりません",
                )
                return Result(headers, emptyList(), issues)
            }

            tbodies.size > 1 -> {
                issues += issue(
                    IssueCode.TBODY_AMBIGUOUS,
                    "tbody[id$=$TBODY_ID_SUFFIX] の候補が複数あります（${tbodies.size}件）",
                )
                return Result(headers, emptyList(), issues)
            }
        }

        // 必須列が欠けている状態で行を読むと列を取り違えるため、行は返さない。
        if (REQUIRED_HEADERS.any { !columnIndex.containsKey(it) }) {
            return Result(headers, emptyList(), issues)
        }

        val tbody = tbodies.single()
        val dataRows = tbody.children().filter { it.tagName() == "tr" }
        val rows = mutableListOf<Row>()
        val seenSessionNos = mutableSetOf<Int>()

        dataRows.forEachIndexed { index, tr ->
            val rowIndex = index + 1
            val cells = directCells(tr)
            if (cells.isEmpty()) {
                issues += issue(
                    IssueCode.EMPTY_ROW,
                    "データ行 $rowIndex にセルがありません",
                    rowIndex = rowIndex,
                )
                return@forEachIndexed
            }

            if (cells.size != headerCells.size) {
                issues += issue(
                    IssueCode.ROW_CELL_COUNT_MISMATCH,
                    "データ行 $rowIndex のセル数がヘッダーと一致しません" +
                        "（ヘッダー ${headerCells.size}、行 ${cells.size}）",
                    rowIndex = rowIndex,
                )
            }

            val rawValues = cells.map { normalizeText(it.text()) }
            fun value(header: String): String =
                columnIndex[header]?.let { rawValues.getOrNull(it) }.orEmpty()

            val sessionNoText = value("回数")
            val sessionNo = parseSessionNo(sessionNoText)
            if (sessionNoText.isNotEmpty() && sessionNo == null) {
                issues += issue(
                    IssueCode.SESSION_NO_UNPARSED,
                    "データ行 $rowIndex の回数「$sessionNoText」を数値として解釈できません",
                    rowIndex = rowIndex,
                    column = "回数",
                )
            }
            if (sessionNo != null && !seenSessionNos.add(sessionNo)) {
                issues += issue(
                    IssueCode.DUPLICATE_SESSION_NO,
                    "データ行 $rowIndex の回数「$sessionNo」が重複しています",
                    rowIndex = rowIndex,
                    column = "回数",
                    severity = IssueSeverity.WARNING,
                )
            }

            val dateText = value("実施日")
            val parsedDate = parseDate(dateText)
            when {
                dateText.isEmpty() -> issues += issue(
                    IssueCode.DATE_MISSING,
                    "データ行 $rowIndex の実施日が空です",
                    rowIndex = rowIndex,
                    column = "実施日",
                )

                parsedDate == null -> issues += issue(
                    IssueCode.DATE_UNPARSED,
                    "データ行 $rowIndex の実施日「$dateText」を日付として解釈できません",
                    rowIndex = rowIndex,
                    column = "実施日",
                )
            }

            val dayPeriodText = value("曜日時限")
            val parsedDayPeriod = parseDayPeriod(dayPeriodText)
            when {
                dayPeriodText.isEmpty() -> issues += issue(
                    IssueCode.DAY_PERIOD_MISSING,
                    "データ行 $rowIndex の曜日時限が空です",
                    rowIndex = rowIndex,
                    column = "曜日時限",
                )

                parsedDayPeriod == null -> issues += issue(
                    IssueCode.DAY_PERIOD_UNPARSED,
                    "データ行 $rowIndex の曜日時限「$dayPeriodText」を解釈できません",
                    rowIndex = rowIndex,
                    column = "曜日時限",
                )
            }

            val attendanceMark = value("出欠")
            val attendance = if (attendanceMark.isEmpty()) {
                null
            } else {
                provisionalAttendance(attendanceMark).also {
                    if (it == null) {
                        issues += issue(
                            IssueCode.UNKNOWN_ATTENDANCE_MARK,
                            "データ行 $rowIndex の出欠記号「$attendanceMark」は未対応です" +
                                "（raw は保持します）",
                            rowIndex = rowIndex,
                            column = "出欠",
                        )
                    }
                }
            }

            rows += Row(
                rowIndex = rowIndex,
                sessionNoText = sessionNoText,
                sessionNo = sessionNo,
                dateText = dateText,
                date = parsedDate?.date,
                monthDay = parsedDate?.monthDay,
                dayPeriodText = dayPeriodText,
                dayOfWeek = parsedDayPeriod?.first,
                periods = parsedDayPeriod?.second.orEmpty(),
                attendanceMark = attendanceMark,
                attendance = attendance,
                examText = value("試験"),
                rawValues = rawValues,
            )
        }

        return Result(headers, rows, issues)
    }

    private fun issue(
        code: IssueCode,
        message: String,
        rowIndex: Int? = null,
        column: String? = null,
        severity: IssueSeverity = IssueSeverity.ERROR,
    ) = Issue(code, severity, message, rowIndex, column)

    private fun hasAllRequiredHeaders(table: Element): Boolean {
        val row = findHeaderRow(table) ?: return false
        val labels = directCells(row).map { normalizeHeader(it.text()) }.toSet()
        return REQUIRED_HEADERS.all { normalizeHeader(it) in labels }
    }

    /**
     * thead 内（なければ th を持つ行）から、必須ヘッダーを最も多く含む行を選ぶ。
     * フィルター行などが混ざっていても実ヘッダー行を優先できる。
     */
    private fun findHeaderRow(table: Element): Element? {
        val theadRows = table.select("thead tr")
        val candidates = if (theadRows.isNotEmpty()) {
            theadRows
        } else {
            table.select("tr").filter { row -> row.select("th").isNotEmpty() }
        }
        if (candidates.isEmpty()) return null

        return candidates.maxByOrNull { row ->
            val labels = directCells(row).map { normalizeHeader(it.text()) }.toSet()
            REQUIRED_HEADERS.count { normalizeHeader(it) in labels }
        }
    }

    /** 直下の td/th のみ。入れ子 table のセルを誤って混ぜない。 */
    private fun directCells(row: Element): List<Element> =
        row.children().filter { it.tagName() == "td" || it.tagName() == "th" }

    private fun normalizeText(text: String?): String =
        (text ?: "")
            .replace('\u00a0', ' ')
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun normalizeHeader(text: String?): String =
        normalizeText(text).replace(Regex("\\s+"), "")

    private val SESSION_NO = Regex("^第?\\s*(\\d+)\\s*回?$")

    private fun parseSessionNo(text: String): Int? =
        SESSION_NO.matchEntire(normalizeWideChars(normalizeText(text)))
            ?.groupValues?.get(1)?.toIntOrNull()

    private val FULL_DATE = Regex(
        "^(\\d{4})\\s*(?:年|[-/.])\\s*(\\d{1,2})\\s*(?:月|[-/.])\\s*(\\d{1,2})\\s*日?$",
    )
    private val MONTH_DAY = Regex(
        "^(\\d{1,2})\\s*(?:月|[-/.])\\s*(\\d{1,2})\\s*日?$",
    )
    private val WEEKDAY_SUFFIX = Regex("[（(][^）)]*[）)]\\s*$")

    private data class ParsedDate(
        val date: LocalDate?,
        val monthDay: MonthDay?,
    )

    /**
     * 年を含む明確な日付は [LocalDate] にし、年なしの「04/10」は月日として読む。
     * どちらの場合も [ParsedDate.monthDay] を埋めるが、年なしの [ParsedDate.date]
     * は null のままにして年を推測しない。存在しない月日（2/30 など）は null。
     */
    private fun parseDate(text: String): ParsedDate? {
        val cleaned = normalizeWideChars(normalizeText(text))
            .replace(WEEKDAY_SUFFIX, "")
            .trim()
        if (cleaned.isEmpty()) return null

        FULL_DATE.matchEntire(cleaned)?.let { m ->
            val date = runCatching {
                LocalDate.of(
                    m.groupValues[1].toInt(),
                    m.groupValues[2].toInt(),
                    m.groupValues[3].toInt(),
                )
            }.getOrNull() ?: return null
            return ParsedDate(date, MonthDay.from(date))
        }

        MONTH_DAY.matchEntire(cleaned)?.let { m ->
            val monthDay = runCatching {
                MonthDay.of(
                    m.groupValues[1].toInt(),
                    m.groupValues[2].toInt(),
                )
            }.getOrNull() ?: return null
            return ParsedDate(null, monthDay)
        }

        return null
    }

    private val DAY_PERIOD = Regex(
        "^([月火水木金土日])(?:曜日?)?\\s*" +
            "(\\d+(?:\\s*[・,、/]\\s*\\d+)*)\\s*(?:限|時限)?$",
    )
    private val DAY_CHARS = mapOf(
        "月" to 1,
        "火" to 2,
        "水" to 3,
        "木" to 4,
        "金" to 5,
        "土" to 6,
        "日" to 7,
    )

    private fun parseDayPeriod(text: String): Pair<Int, List<Int>>? {
        val cleaned = normalizeWideChars(normalizeText(text))
        val m = DAY_PERIOD.matchEntire(cleaned) ?: return null
        val day = DAY_CHARS[m.groupValues[1]] ?: return null
        val periods = m.groupValues[2]
            .split(Regex("[・,、/]"))
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it > 0 }
            .distinct()
        if (periods.isEmpty()) return null
        return day to periods
    }

    private val PROVISIONAL_MARKS = mapOf(
        "〇" to ProvisionalAttendance.PRESENT,
        "○" to ProvisionalAttendance.PRESENT,
        "◯" to ProvisionalAttendance.PRESENT,
        "▽" to ProvisionalAttendance.LATE,
        "△" to ProvisionalAttendance.LATE,
        "到" to ProvisionalAttendance.LATE,
        "追" to ProvisionalAttendance.LATE,
        "再" to ProvisionalAttendance.LATE,
        "×" to ProvisionalAttendance.ABSENT,
        "✕" to ProvisionalAttendance.ABSENT,
        "公" to ProvisionalAttendance.HOLIDAY,
        "休" to ProvisionalAttendance.HOLIDAY,
        "外" to ProvisionalAttendance.OTHER,
        "－" to ProvisionalAttendance.OTHER,
        "-" to ProvisionalAttendance.OTHER,
    )

    private fun provisionalAttendance(mark: String): ProvisionalAttendance? =
        PROVISIONAL_MARKS[normalizeText(mark)]

    /** 全角数字・記号を、日付/時限の限定的な解釈にだけ使う。raw は別途保持する。 */
    private fun normalizeWideChars(text: String): String = buildString {
        for (ch in text) {
            when (ch) {
                in '０'..'９' -> append('0' + (ch - '０'))
                '／' -> append('/')
                '．' -> append('.')
                '－', '−', '—', '–' -> append('-')
                '　' -> append(' ')
                else -> append(ch)
            }
        }
    }
}
