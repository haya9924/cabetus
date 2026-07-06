package org.cabetus.data.pdf

/**
 * PDF から抽出した1文字（または連続グリフ）の位置情報。
 * y は上端からの距離（下方向が増加）。Android 非依存で JVM テスト可能。
 */
data class TextRun(
    val page: Int,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val text: String,
)

/** 行内でまとめられた語。 */
data class Word(val x: Float, val y: Float, val endX: Float, val text: String)

/**
 * 1行。同一 y 帯の文字の集まり。
 * - [chars]: 文字単位（x昇順）。セル位置合わせの厳密なマッチング用。
 * - [words]: gap で結合した語。ヘッダ等のテキスト再構成用。
 */
data class Row(
    val y: Float,
    val chars: List<TextRun>,
    val words: List<Word>,
) {
    val text: String get() = words.joinToString(" ") { it.text }
}

/**
 * TextRun 群を行・語にまとめるユーティリティ。
 */
object Words {

    fun pageCount(runs: List<TextRun>): Int =
        (runs.maxOfOrNull { it.page } ?: -1) + 1

    /** 文字を行(y)ごとにまとめる。 */
    fun rowsOf(runs: List<TextRun>, page: Int, yTolerance: Float = 5f): List<Row> {
        val pageRuns = runs.filter { it.page == page && it.text.isNotBlank() }
            .sortedWith(compareBy({ it.y }, { it.x }))
        if (pageRuns.isEmpty()) return emptyList()

        val rowsAcc = mutableListOf<MutableList<TextRun>>()
        for (run in pageRuns) {
            val row = rowsAcc.lastOrNull()
            if (row != null && kotlin.math.abs(row.first().y - run.y) <= yTolerance) {
                row.add(run)
            } else {
                rowsAcc.add(mutableListOf(run))
            }
        }

        return rowsAcc.map { rowRuns ->
            val sorted = rowRuns.sortedBy { it.x }
            val avgY = rowRuns.map { it.y }.average().toFloat()
            Row(avgY, sorted, wordsFromChars(sorted))
        }.sortedBy { it.y }
    }

    /** 文字列を gap で語に結合する。 */
    fun wordsFromChars(chars: List<TextRun>): List<Word> {
        if (chars.isEmpty()) return emptyList()
        val sorted = chars.sortedBy { it.x }
        val words = mutableListOf<Word>()
        var curX = sorted.first().x
        var curEnd = sorted.first().x + sorted.first().width
        var curY = sorted.first().y
        val sb = StringBuilder(sorted.first().text)
        for (i in 1 until sorted.size) {
            val r = sorted[i]
            val gap = r.x - curEnd
            val threshold = maxOf(3.5f, 0.35f * r.height)
            if (gap > threshold) {
                words.add(Word(curX, curY, curEnd, sb.toString()))
                sb.setLength(0)
                sb.append(r.text)
                curX = r.x
                curY = r.y
            } else {
                sb.append(r.text)
            }
            curEnd = r.x + r.width
        }
        words.add(Word(curX, curY, curEnd, sb.toString()))
        return words
    }
}
