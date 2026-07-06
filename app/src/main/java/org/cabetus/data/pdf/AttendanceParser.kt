package org.cabetus.data.pdf

import java.time.LocalDate
import java.time.ZoneId

/** 出欠PDFの解析結果。 */
data class ParsedClassCourse(
    val code: String,
    val name: String,
    val instructor: String,
    val attendanceRate: Double?,
)

data class ParsedSession(
    val code: String,
    val sessionNo: Int,
    val dateEpoch: Long,
    val dayOfWeek: Int, // 1=月..7=日
    val period: Int,
    val mark: String?,
)

data class AttendanceResult(
    val academicYear: Int,
    val courses: List<ParsedClassCourse>,
    val sessions: List<ParsedSession>,
)

/**
 * 学生出欠状況表PDFのパーサ。
 * 各科目は3行ブロック:
 *  A: 科目コード + 科目名（教員）+ 各回の日付(MM/DD)
 *  B: 出席率% + 各回の 曜日 と 時限
 *  C: 各回の出欠マーク
 * 座標(x)で回(列)を対応付ける。純ロジックで JVM テスト可能。
 */
object AttendanceParser {

    private val JST: ZoneId = ZoneId.of("Asia/Tokyo")
    private val CODE = Regex("^[0-9A-Z]{7}$")
    private val DATE = Regex("^(\\d{1,2})/(\\d{1,2})$")
    private val RATE = Regex("(\\d+)%")
    private val YEAR = Regex("(\\d{4})\\s*年度")
    private val DIGIT = Regex("^\\d$")
    private val DAY_CHARS = mapOf(
        "月" to 1, "火" to 2, "水" to 3, "木" to 4, "金" to 5, "土" to 6, "日" to 7,
    )
    private val MARK_CHARS = setOf(
        "〇", "○", "◯", "▽", "△", "×", "✕", "公", "休", "外", "－", "-", "到", "追", "再",
    )

    fun parse(runs: List<TextRun>): AttendanceResult {
        val academicYear = findAcademicYear(runs)
        val courseMap = LinkedHashMap<String, ParsedClassCourse>()
        val sessionsByCode = LinkedHashMap<String, MutableList<ParsedSession>>()

        val pages = Words.pageCount(runs)
        for (page in 0 until pages) {
            val rows = Words.rowsOf(runs, page)
            var i = 0
            while (i < rows.size) {
                val rowA = rows[i]
                val codeWord = rowA.words.firstOrNull { CODE.matches(it.text) && it.x < 60f }
                if (codeWord == null) {
                    i++
                    continue
                }
                val code = codeWord.text

                // 日付列（x>=300 かつ MM/DD）
                val dateCols = rowA.words
                    .filter { it.x >= 290f && DATE.matches(it.text) }
                    .sortedBy { it.x }
                if (dateCols.isEmpty()) {
                    i++
                    continue
                }

                // 科目名・教員（コードと最初の日付の間のテキスト）
                val firstDateX = dateCols.first().x
                val midText = rowA.words
                    .filter { it.x > codeWord.endX && it.x < firstDateX - 5f }
                    .sortedBy { it.x }
                    .joinToString(" ") { it.text }
                val (name, instructor) = splitNameInstructor(midText)

                // B行（rate + day/period）と C行（marks）を後続から探す
                val rowB = rows.getOrNull(i + 1)
                val rowC = rows.getOrNull(i + 2)

                if (courseMap[code] == null) {
                    val rate = rowB?.let { findRate(it) }
                    courseMap[code] = ParsedClassCourse(code, name, instructor, rate)
                } else if (courseMap[code]!!.name.isBlank() && name.isNotBlank()) {
                    courseMap[code] = courseMap[code]!!.copy(name = name, instructor = instructor)
                }

                val list = sessionsByCode.getOrPut(code) { mutableListOf() }
                for (col in dateCols) {
                    val dateX = col.x
                    val date = parseDate(col.text, academicYear) ?: continue
                    val day = rowB?.let { dayAt(it, dateX) } ?: dayFromDate(date)
                    val period = rowB?.let { periodAt(it, dateX) } ?: continue
                    val mark = rowC?.let { markAt(it, dateX) }
                    // 重複回避（同一 code の date+period は1回だけ）
                    if (list.any { it.dateEpoch == date && it.period == period }) continue
                    list.add(
                        ParsedSession(
                            code = code,
                            sessionNo = list.size + 1,
                            dateEpoch = date,
                            dayOfWeek = day,
                            period = period,
                            mark = mark,
                        ),
                    )
                }

                // 次のコード行まで進める（マーク行が無い科目でも取りこぼさない）
                i++
            }
        }

        val sessions = sessionsByCode.values.flatten()
            // sessionNo を code 内で振り直す
            .let { all ->
                val perCode = LinkedHashMap<String, Int>()
                all.map { s ->
                    val n = (perCode[s.code] ?: 0) + 1
                    perCode[s.code] = n
                    s.copy(sessionNo = n)
                }
            }

        return AttendanceResult(academicYear, courseMap.values.toList(), sessions)
    }

    private fun findAcademicYear(runs: List<TextRun>): Int {
        // ページ全体のテキストから年度を探す
        val text = runs.joinToString("") { it.text }
        return YEAR.find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: LocalDate.now(JST).let { if (it.monthValue >= 4) it.year else it.year - 1 }
    }

    private fun findRate(row: Row): Double? {
        for (w in row.words) {
            RATE.find(w.text)?.let { return it.groupValues[1].toDoubleOrNull() }
        }
        return null
    }

    private fun splitNameInstructor(mid: String): Pair<String, String> {
        val t = mid.trim()
        // 最後の （...） を教員とみなす
        val open = t.lastIndexOf('（')
        val close = t.lastIndexOf('）')
        return if (open in 0 until close) {
            val name = t.substring(0, open).trim()
            val instr = t.substring(open + 1, close).trim().replace(Regex("\\s+"), " ")
            name to instr
        } else {
            t to ""
        }
    }

    private fun dayAt(row: Row, colX: Float): Int? {
        // 文字単位で最も近い曜日文字
        val cand = row.chars
            .filter { DAY_CHARS.containsKey(it.text) && kotlin.math.abs(it.x - colX) <= 12f }
            .minByOrNull { kotlin.math.abs(it.x - colX) }
        return cand?.let { DAY_CHARS[it.text] }
    }

    private fun periodAt(row: Row, colX: Float): Int? {
        // 時限数字は曜日文字の右（colX+6..colX+28）
        val cand = row.chars
            .filter { DIGIT.matches(it.text) && it.x in (colX + 6f)..(colX + 28f) }
            .minByOrNull { it.x }
        return cand?.text?.toIntOrNull()
    }

    private fun markAt(row: Row, colX: Float): String? {
        val cand = row.chars
            .filter { MARK_CHARS.contains(it.text) && kotlin.math.abs(it.x - colX) <= 12f }
            .minByOrNull { kotlin.math.abs(it.x - colX) }
        return cand?.text
    }

    private fun parseDate(text: String, academicYear: Int): Long? {
        val m = DATE.matchEntire(text) ?: return null
        val month = m.groupValues[1].toInt()
        val day = m.groupValues[2].toInt()
        val year = if (month >= 4) academicYear else academicYear + 1
        return try {
            LocalDate.of(year, month, day).atStartOfDay(JST).toInstant().toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }

    private fun dayFromDate(epoch: Long): Int {
        val d = java.time.Instant.ofEpochMilli(epoch).atZone(JST).dayOfWeek.value // 1=Mon..7=Sun
        return d
    }
}
