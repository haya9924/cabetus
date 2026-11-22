package org.cabetus.data.classweb

import org.cabetus.data.local.ClassWebAttendanceEntity
import org.cabetus.domain.AttendanceResolver
import org.cabetus.domain.AttendanceStatus
import java.time.LocalDate
import java.time.ZoneId

object ClassAttendanceSyncPlanner {
    fun plan(preview: ClassAttendanceImportPreview, academicYear: Int, term: String,
             courseCode: String, fetchedAt: Long): List<ClassWebAttendanceEntity> {
        require(!preview.provisional && preview.errors.isEmpty()) { "暫定・解析失敗のため保存できません。" }
        require(academicYear in 2000..2100 && term in listOf("前期", "後期"))
        require(ClassTimetableCourseMetadata.normalizeOfficialCourseCode(courseCode) == courseCode)
        require(preview.rows.isNotEmpty())
        val zone = ZoneId.of("Asia/Tokyo")
        val rows = preview.rows.map { row ->
            val md = Regex("^(\\d{1,2})/(\\d{1,2})$").matchEntire(row.dateText.trim())
            val date = row.date ?: run {
                require(md != null) { "日付不明" }
                val month = md.groupValues[1].toInt()
                LocalDate.of(academicYear + if (month <= 3) 1 else 0, month, md.groupValues[2].toInt())
            }
            require(!date.isBefore(LocalDate.of(academicYear,4,1)) && date.isBefore(LocalDate.of(academicYear+1,4,1)))
            require(if (term == "前期") date.monthValue in 4..9 else date.monthValue in 9..12 || date.monthValue in 1..3)
            val period = row.period
            require(period != null && period in 1..7) { "単一の時限を確定できません。" }
            val day = Regex("[月火水木金土日]").find(row.dayPeriodText)?.value
            require(day != null && "月火水木金土日".indexOf(day) + 1 == date.dayOfWeek.value) { "曜日不一致" }
            val mark = row.mark.trim()
            require(mark.isEmpty() || AttendanceResolver.fromMark(mark) != AttendanceStatus.UNRECORDED || mark in listOf("－", "-", "外")) { "未知の出欠記号" }
            ClassWebAttendanceEntity(academicYear,term,courseCode,date.atStartOfDay(zone).toInstant().toEpochMilli(),period,row.sessionNo,mark,fetchedAt)
        }
        val grouped = rows.groupBy { it.date to it.period }
        require(grouped.values.all { list -> list.map { it.rawMark to it.sessionNo }.distinct().size == 1 }) { "同じ授業回に矛盾があります。" }
        return grouped.values.map { it.first() }
    }
}
