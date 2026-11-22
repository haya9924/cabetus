package org.cabetus.domain

import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import org.cabetus.data.local.*
import org.cabetus.data.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/** 読取専用の投影。PDFテーブルへは書き込まない。負のIDはCLASS由来を表す。 */
@Singleton
class EffectiveClassSessions @Inject constructor(
    private val pdf: ClassSessionDao,
    private val web: ClassWebAttendanceDao,
    private val settings: SettingsRepository,
    private val timetable: TimetableDao,
) {
    val all = combine(pdf.observeAll(), web.observeAll(), settings.settings, settings.classAttendanceMappings, timetable.observeAll()) { old, fetched, config, mappings, cells ->
        val active = cells.map { it.courseCode }.toSet()
        val mapped = fetched.map { row ->
            val target = mappings["${row.academicYear}:${row.courseCode}"]
            if (row.courseCode !in active && target != null && target in active && target.startsWith("CLASSWEB-")) row.copy(courseCode=target) else row
        }
        project(old, mapped, config.academicYear)
    }
    suspend fun forCourse(code: String) = all.first().filter { it.courseCode == code }

    companion object {
        fun project(pdf: List<ClassSessionEntity>, web: List<ClassWebAttendanceEntity>, year: Int): List<ClassSessionEntity> {
            val current = web.filter { it.academicYear == year }
                .groupBy { Triple(it.courseCode, it.date, it.period) }
                .mapNotNull { (_, rows) ->
                    // 学期間で矛盾する同一回は適用しない。
                    if (rows.map { it.rawMark }.distinct().size > 1) null else rows.maxByOrNull { it.fetchedAt }
                }
            val keys = current.map { Triple(it.courseCode,it.date,it.period) }.toSet()
            return pdf.filterNot { Triple(it.courseCode,it.date,it.period) in keys } + current.map {
                ClassSessionEntity(id=-1, courseCode=it.courseCode, sessionNo=it.sessionNo ?: 0,
                    date=it.date, period=it.period, mark=it.rawMark)
            }
        }
    }
}
