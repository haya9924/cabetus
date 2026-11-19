package org.cabetus.data.classweb

import org.cabetus.data.local.ClassWebAttendanceDao
import org.cabetus.data.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ClassAttendanceSyncRepository @Inject constructor(
    private val dao: ClassWebAttendanceDao,
    private val settings: SettingsRepository,
    private val previews: ClassAttendanceDetailImportRepository,
) {
    suspend fun save(row: ClassAttendanceListDomExtractor.Row, detail: ClassAttendanceDetailDomExtractor.ExtractedDetail,
                     context: ClassAttendanceSyncDom.Context, shouldCancel: () -> Boolean = { false }): ClassAttendanceImportPreview {
        require(settings.current().academicYear == context.year) { "設定年度とCLASSの年度が一致しません。" }
        val code = ClassAttendanceSyncDom.courseCode(row.label) ?: error("科目コード不明")
        require(context.codes == listOf(code)) { "科目コードが一致しません。" }
        val preview = previews.preview(detail)
        val records = ClassAttendanceSyncPlanner.plan(preview,context.year,context.term,code,System.currentTimeMillis())
        require(settings.current().academicYear == context.year)
        if (shouldCancel()) throw kotlinx.coroutines.CancellationException("出欠更新を中止しました。")
        dao.upsertAll(records)
        return preview.copy(warnings = preview.warnings + "CLASS取得記録を保存しました。手動修正・アプリ記録は保持します。")
    }
}
