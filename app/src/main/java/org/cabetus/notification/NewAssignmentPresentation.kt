package org.cabetus.notification

import org.cabetus.data.local.AssignmentEntity
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 新規課題検出通知の表示内容を組み立てる純ロジック。
 *
 * 通知本文・展開表示はどちらもここで決める。Android 依存を持たないため
 * JVM 単体テストで検証できる。
 */
object NewAssignmentPresentation {

    data class Presentation(
        val title: String,
        val summary: String,
        val expandedText: String,
        val assignments: List<AssignmentEntity>,
    )

    private val JST: ZoneId = ZoneId.of("Asia/Tokyo")
    private val DEADLINE_FMT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("M/d(E) HH:mm", Locale.JAPANESE).withZone(JST)

    /** 展開表示に列挙する最大件数。 */
    private const val MAX_EXPANDED = 5

    private const val UNKNOWN_COURSE = "科目名不明"
    private const val UNKNOWN_TITLE = "課題名不明"

    /**
     * @param assignments 新たに検出した課題（順序は問わない）
     * @param isInitialImport true なら初回取得として件数に関わらず初回文言にする
     * @return 対象が空なら null
     */
    fun build(
        assignments: List<AssignmentEntity>,
        isInitialImport: Boolean = false,
    ): Presentation? {
        // id 重複は先勝ちで除去する。
        val distinct = assignments.distinctBy { it.id }
        if (distinct.isEmpty()) return null

        val sorted = distinct.sortedWith(
            compareBy<AssignmentEntity, Long?>(nullsLast()) { it.deadline }
                .thenBy { it.courseName }
                .thenBy { it.title }
                .thenBy { it.id },
        )

        val count = sorted.size
        val courseCount = sorted.map { normalize(it.courseName, UNKNOWN_COURSE) }.distinct().size
        val summary = "${courseCount}科目 / ${count}件"
        val expanded = buildExpanded(sorted)

        return when {
            isInitialImport -> Presentation(
                title = "初回取得: 対象の課題が${count}件",
                summary = summary,
                expandedText = expanded,
                assignments = sorted,
            )
            count == 1 -> {
                val a = sorted.first()
                Presentation(
                    title = "検出した課題: ${normalize(a.title, UNKNOWN_TITLE)}",
                    summary = listOf(
                        "科目: ${normalize(a.courseName, UNKNOWN_COURSE)}",
                        deadlineLine(a),
                    ).joinToString("\n"),
                    expandedText = buildExpanded(sorted),
                    assignments = sorted,
                )
            }
            else -> Presentation(
                title = "課題を${count}件新たに検出",
                summary = summary,
                expandedText = expanded,
                assignments = sorted,
            )
        }
    }

    private fun buildExpanded(sorted: List<AssignmentEntity>): String {
        val head = sorted.take(MAX_EXPANDED).joinToString("\n") { a ->
            "・${normalize(a.courseName, UNKNOWN_COURSE)} / " +
                "${normalize(a.title, UNKNOWN_TITLE)} / ${deadlineLine(a)}"
        }
        val rest = sorted.size - MAX_EXPANDED
        return if (rest > 0) "$head\nほか${rest}件" else head
    }

    /**
     * 締切の表示。deadline が無い場合は、生テキストが空なら「記載なし」、
     * 記述はあるが解釈できていなければ「取得できません」。
     */
    private fun deadlineLine(a: AssignmentEntity): String {
        val deadline = a.deadline
        return when {
            deadline != null -> "締切: ${DEADLINE_FMT.format(java.time.Instant.ofEpochMilli(deadline))}"
            a.deadlineText.isBlank() -> "締切: 記載なし"
            else -> "締切: 取得できません"
        }
    }

    /** 空白・改行を 1 つの空白へ正規化し、空なら代替文言にする。 */
    private fun normalize(value: String, fallback: String): String {
        val normalized = value.replace(Regex("\\s+"), " ").trim()
        return normalized.ifEmpty { fallback }
    }
}
