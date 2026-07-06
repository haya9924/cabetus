package org.cabetus.data.pdf

/** 時間割PDFの1コマ解析結果。 */
data class ParsedTimetableCell(
    val dayOfWeek: Int, // 1=月..6=土
    val period: Int,
    val courseCode: String,
    val courseName: String,
    val instructor: String,
    val room: String?,
    val isOnline: Boolean,
    val isMultiSession: Boolean,
    val credits: Double?,
)

data class TimetableResult(
    val cells: List<ParsedTimetableCell>,
)

/**
 * 学生時間割表PDFのパーサ。
 * 曜日列は曜日ヘッダ(月曜日..土曜日)の x で、時限帯は時限番号(x≈40)の y で決まる。
 * 各セルは 科目名/教員/教室/科目コード/[複数回]/単位 を縦に積み、1セルに複数科目ブロックあり得る。
 */
object TimetableParser {

    private val CODE = Regex("^[0-9A-Z]{7}$")
    private val UNIT = Regex("^([0-9]+(?:\\.[0-9]+)?)\\s*単位$")
    // 「葛：E501教室」「葛：E物理学実験室」等。キャンパス略字 + 室名（教室/実験室）
    private val ROOM = Regex("^(.)[:：](.+室)$")
    private val DAY_HEADER = Regex("^([月火水木金土日])曜日$")
    private val PERIOD_NUM = Regex("^([1-7])$")
    private const val MULTI = "[複数回]"

    fun parse(runs: List<TextRun>): TimetableResult {
        val cells = mutableListOf<ParsedTimetableCell>()
        val pages = Words.pageCount(runs)
        for (page in 0 until pages) {
            val rows = Words.rowsOf(runs, page)
            if (rows.isEmpty()) continue

            // 曜日ヘッダ（x, dayIndex）
            val dayHeaders = rows.flatMap { it.words }
                .mapNotNull { w -> DAY_HEADER.matchEntire(w.text)?.let { dayIndex(it.groupValues[1]) to w.x } }
                .sortedBy { it.second }
            if (dayHeaders.isEmpty()) continue

            // 時限番号（period, y）
            val periodMarks = rows.flatMap { r -> r.words.map { it to r.y } }
                .filter { it.first.x < 50f && PERIOD_NUM.matches(it.first.text) }
                .map { it.first.text.toInt() to it.second }
                .sortedBy { it.second }
            if (periodMarks.isEmpty()) continue

            // 全語を (day, period) 別に振り分け（数字ヘッダ・曜日ヘッダ自身は除外）
            data class Placed(val day: Int, val period: Int, val row: Row, val word: Word)

            val bucket = LinkedHashMap<Pair<Int, Int>, MutableList<Row>>()
            // セル語を行ごとに (day, period) 帯へ入れる
            for (row in rows) {
                val period = periodForY(periodMarks, row.y) ?: continue
                // 曜日ごとに、その曜日列に属する語だけを含む行を作る
                for ((day, _) in dayHeaders) {
                    val wordsInDay = row.words.filter { dayForX(dayHeaders, it.x) == day && it.x >= 50f }
                    if (wordsInDay.isEmpty()) continue
                    val subRow = Row(row.y, row.chars.filter { dayForX(dayHeaders, it.x) == day }, wordsInDay)
                    bucket.getOrPut(day to period) { mutableListOf() }.add(subRow)
                }
            }

            // 各 (day, period) のセル内をブロック分割
            for ((key, cellRows) in bucket) {
                val (day, period) = key
                val sorted = cellRows.sortedBy { it.y }
                var block = mutableListOf<Row>()
                for (r in sorted) {
                    block.add(r)
                    val lineText = r.text.replace(" ", "")
                    if (UNIT.matches(lineText)) {
                        buildCell(day, period, block)?.let { cells.add(it) }
                        block = mutableListOf()
                    }
                }
                // 単位行が無いブロック（末尾）も念のため
                if (block.isNotEmpty()) buildCell(day, period, block)?.let { cells.add(it) }
            }
        }
        return TimetableResult(cells)
    }

    private fun buildCell(day: Int, period: Int, block: List<Row>): ParsedTimetableCell? {
        val lines = block.map { it.text.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return null

        var code: String? = null
        var room: String? = null
        var online = false
        var multi = false
        var credits: Double? = null
        val nameLines = mutableListOf<String>()

        for (raw in lines) {
            val line = raw.replace(" ", "")
            when {
                CODE.matches(line) -> code = line
                UNIT.matches(line) -> credits = UNIT.matchEntire(line)?.groupValues?.get(1)?.toDoubleOrNull()
                line.contains("遠隔") || line.contains("オンライン") -> online = true
                ROOM.matches(line) ->
                    room = ROOM.matchEntire(line)?.groupValues?.get(2)?.removeSuffix("教室")
                line == MULTI || line.contains("複数回") -> multi = true
                else -> nameLines.add(raw)
            }
        }
        val theCode = code ?: return null

        // 科目名と教員を推定: nameLines の最後を教員、それ以外を名前とみなす
        val instructor = if (nameLines.size >= 2) nameLines.last().replace(Regex("\\s+"), " ") else ""
        val nameParts = if (nameLines.size >= 2) nameLines.dropLast(1) else nameLines
        val name = nameParts.joinToString("").replace(Regex("\\s+"), "")

        return ParsedTimetableCell(
            dayOfWeek = day,
            period = period,
            courseCode = theCode,
            courseName = name,
            instructor = instructor,
            room = room,
            isOnline = online,
            isMultiSession = multi,
            credits = credits,
        )
    }

    private fun dayIndex(ch: String): Int = when (ch) {
        "月" -> 1; "火" -> 2; "水" -> 3; "木" -> 4; "金" -> 5; "土" -> 6; else -> 7
    }

    /** x をどの曜日列に属するか、ヘッダの中点境界で判定。 */
    private fun dayForX(headers: List<Pair<Int, Float>>, x: Float): Int {
        // ヘッダは content より右にオフセット(~+19)しているため、
        // 最も近いヘッダの曜日を返す
        return headers.minByOrNull { kotlin.math.abs(it.second - x) }?.first ?: headers.first().first
    }

    private fun periodForY(marks: List<Pair<Int, Float>>, y: Float): Int? {
        // y 以下で最大の時限マーカー
        val candidate = marks.filter { it.second - 3f <= y }.maxByOrNull { it.second }
        return candidate?.first
    }
}
