package org.cabetus.data.classweb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.cabetus.domain.AttendanceResolver
import org.cabetus.domain.AttendanceStatus
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** 出欠プレビューの1行。raw の表記を保持し、保存はしない。 */
data class ClassAttendancePreviewRow(
    val sessionNo: Int?,
    val dateText: String,
    val date: LocalDate?,
    val dayPeriodText: String,
    val period: Int?,
    val mark: String,
    val status: AttendanceStatus,
)

/**
 * CLASS 出欠詳細の読み取りプレビュー。
 *
 * 第一段階では表示専用とし、Room への保存は行わない。
 * [errors] は読み取り内容を安全に解釈できなかった点、[warnings] は確認事項を表す。
 * どちらも「保存可否」ではなく、画面で内容を確認するための情報として扱う。
 *
 * [provisional] は、詳細画面が一覧の行の科目と確実に一致すると確認できなかった場合に true。
 * この場合は値を確定値として扱わず、画面で暫定と明示し、保存しない。
 */
data class ClassAttendanceImportPreview(
    val heading: String?,
    val rows: List<ClassAttendancePreviewRow>,
    val warnings: List<String>,
    val errors: List<String>,
    val provisional: Boolean = false,
) {
    val hasErrors: Boolean get() = errors.isNotEmpty()
}

/** 詳細が一覧の科目と一致すると確認できない場合の注記。 */
private const val PROVISIONAL_WARNING =
    "この科目の詳細が一覧の科目名と一致することを確認できませんでした。" +
        "表示内容は暫定です。保存は行いません。CLASS で科目を開き直して確認してください。"

/**
 * 一致を確認できなかったプレビューを「暫定」として明示する。
 * 値は捨てず、確認できなかった事実だけを注記に加える（保存は行わない）。
 */
internal fun ClassAttendanceImportPreview.asProvisional(): ClassAttendanceImportPreview =
    copy(
        warnings = (warnings + PROVISIONAL_WARNING).distinct(),
        provisional = true,
    )

/** 出欠詳細の保存要求に対する結果。第一段階では常に拒否される。 */
data class ClassAttendanceImportSaveResult(
    val blocked: Boolean = true,
    val blockedReasons: List<String> = READ_ONLY_BLOCKED_REASONS,
)

private val READ_ONLY_BLOCKED_REASONS: List<String> = listOf(
    "CLASS 出欠詳細の取り込みは読み取りプレビュー専用です。データベースには保存しません。",
    "全科目の自動巡回・定期同期は未対応です。",
)

/**
 * 出欠詳細の抽出結果を、保存を伴わない表示用プレビューに変換する純ロジック。
 *
 * 年・時限・出欠記号を推測で補わない。解釈できない値は raw を残したまま
 * [ClassAttendanceImportPreview.errors] / [ClassAttendanceImportPreview.warnings] に明示する。
 * 科目コードの判定は行わない。
 */
internal object ClassAttendanceDetailImportPlanner {

    data class Plan(
        val previewRows: List<ClassAttendancePreviewRow>,
        val warnings: List<String>,
        val errors: List<String>,
    ) {
        val hasErrors: Boolean get() = errors.isNotEmpty()
    }

    fun plan(
        html: String,
        extractedWarnings: List<String> = emptyList(),
    ): Plan {
        val parsed = ClassAttendanceDetailParser.parse(html)
        val warnings = (extractedWarnings + parsed.warnings.map { it.message }).toMutableList()
        val errors = parsed.errors.map { it.message }.toMutableList()

        val previewRows = parsed.rows.map { row ->
            ClassAttendancePreviewRow(
                sessionNo = row.sessionNo,
                dateText = row.dateText,
                date = row.date,
                dayPeriodText = row.dayPeriodText,
                period = row.periods.singleOrNull(),
                mark = row.attendanceMark,
                status = AttendanceResolver.fromMark(row.attendanceMark),
            )
        }

        if (parsed.hasErrors) {
            errors.add(
                0,
                "出席詳細テーブルを安全に解釈できない項目があります。値は推測していません。",
            )
        }
        if (parsed.rows.isEmpty()) {
            errors += "出席回を確認できませんでした。"
        }
        if (parsed.rows.any { it.date == null }) {
            warnings += "実施日に年が含まれていない回があります。年は推測せず、月日表記のまま表示します。"
        }
        if (parsed.rows.any { it.periods.size != 1 }) {
            warnings += "曜日時限から単一の時限を確定できない回があります。時限は推測しません。"
        }
        if (parsed.rows.any { !it.isAttendanceRecorded }) {
            warnings += "出欠が空欄の回は「未記録」として表示し、欠席などの分類は補いません。"
        }

        return Plan(
            previewRows = previewRows,
            warnings = warnings.distinct(),
            errors = errors.distinct(),
        )
    }

    fun wrapExtractedTable(tableHtml: String): String = buildString {
        append("<html><body><div class=\"mainContent\">")
        append("<div id=\"extracted:jugyoKaisuTbl\" class=\"ui-datatable attendanceInfo\">")
        append(tableHtml)
        append("</div></div></body></html>")
    }
}

/**
 * CLASS 出欠詳細の読み取りプレビュー。
 *
 * 第一段階では読み取り内容を表示するだけで、Room の授業回・出席データを
 * 追加・更新・削除しない。UI から保存経路を呼べないようにするだけでなく、
 * [save] も常に拒否して DB へ書き込まない。
 */
@Singleton
class ClassAttendanceDetailImportRepository @Inject constructor() {

    suspend fun preview(extracted: ClassAttendanceDetailDomExtractor.ExtractedDetail):
        ClassAttendanceImportPreview = withContext(Dispatchers.IO) {
        val plan = ClassAttendanceDetailImportPlanner.plan(
            html = ClassAttendanceDetailImportPlanner.wrapExtractedTable(extracted.tableHtml),
            extractedWarnings = extracted.warnings,
        )
        ClassAttendanceImportPreview(
            heading = extracted.heading,
            rows = plan.previewRows,
            warnings = plan.warnings,
            errors = plan.errors,
        )
    }

    /**
     * 第一段階では保存しない。呼び出し経路が残っていても DB へは書き込まず、
     * 常にブロック結果を返す。
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun save(extracted: ClassAttendanceDetailDomExtractor.ExtractedDetail):
        ClassAttendanceImportSaveResult = withContext(Dispatchers.IO) {
        ClassAttendanceImportSaveResult()
    }
}
