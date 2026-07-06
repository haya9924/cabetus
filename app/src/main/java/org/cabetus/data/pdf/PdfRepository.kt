package org.cabetus.data.pdf

import android.content.Context
import android.net.Uri
import org.cabetus.data.local.ClassCourseDao
import org.cabetus.data.local.ClassCourseEntity
import org.cabetus.data.local.ClassSessionDao
import org.cabetus.data.local.ClassSessionEntity
import org.cabetus.data.local.TimetableCellEntity
import org.cabetus.data.local.TimetableDao
import org.cabetus.data.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** 時間割PDFプレビュー用サマリ。 */
data class TimetablePreview(
    val result: TimetableResult,
    val courseCount: Int,
    val cellCount: Int,
)

/** 出欠PDFプレビュー用サマリ。 */
data class AttendancePreview(
    val result: AttendanceResult,
    val courseCount: Int,
    val sessionCount: Int,
)

/**
 * PDF（時間割・出欠）をパースして Room の共有データ層に保存する。
 */
@Singleton
class PdfRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val classCourseDao: ClassCourseDao,
    private val timetableDao: TimetableDao,
    private val classSessionDao: ClassSessionDao,
    private val settingsRepository: SettingsRepository,
) {

    suspend fun parseTimetable(uri: Uri): TimetablePreview = withContext(Dispatchers.IO) {
        val runs = openRuns(uri)
        val result = TimetableParser.parse(runs)
        TimetablePreview(
            result = result,
            courseCount = result.cells.map { it.courseCode }.distinct().size,
            cellCount = result.cells.size,
        )
    }

    suspend fun parseAttendance(uri: Uri): AttendancePreview = withContext(Dispatchers.IO) {
        val runs = openRuns(uri)
        val result = AttendanceParser.parse(runs)
        AttendancePreview(
            result = result,
            courseCount = result.courses.size,
            sessionCount = result.sessions.size,
        )
    }

    suspend fun saveTimetable(result: TimetableResult) = withContext(Dispatchers.IO) {
        // 科目（単位）をマージ保存
        for (code in result.cells.map { it.courseCode }.distinct()) {
            val cell = result.cells.first { it.courseCode == code }
            mergeClassCourse(
                code = code,
                name = cell.courseName.ifBlank { null },
                instructor = cell.instructor.ifBlank { null },
                credits = cell.credits,
                rate = null,
            )
        }
        // セルを入れ替え
        timetableDao.clear()
        timetableDao.insertAll(
            result.cells.map {
                TimetableCellEntity(
                    dayOfWeek = it.dayOfWeek,
                    period = it.period,
                    courseCode = it.courseCode,
                    courseName = it.courseName,
                    instructor = it.instructor,
                    room = it.room,
                    isOnline = it.isOnline,
                    isMultiSession = it.isMultiSession,
                )
            },
        )
    }

    suspend fun saveAttendance(result: AttendanceResult) = withContext(Dispatchers.IO) {
        if (result.academicYear > 0) settingsRepository.setAcademicYear(result.academicYear)
        for (c in result.courses) {
            mergeClassCourse(
                code = c.code,
                name = c.name.ifBlank { null },
                instructor = c.instructor.ifBlank { null },
                credits = null,
                rate = c.attendanceRate,
            )
        }
        classSessionDao.clear()
        classSessionDao.insertAll(
            result.sessions.map {
                ClassSessionEntity(
                    courseCode = it.code,
                    sessionNo = it.sessionNo,
                    date = it.dateEpoch,
                    period = it.period,
                    mark = it.mark,
                )
            },
        )
    }

    /** 既存の ClassCourse に非nullフィールドだけ上書きしてマージ保存。 */
    private suspend fun mergeClassCourse(
        code: String,
        name: String?,
        instructor: String?,
        credits: Double?,
        rate: Double?,
    ) {
        val existing = classCourseDao.getByCode(code)
        val merged = ClassCourseEntity(
            code = code,
            name = name ?: existing?.name ?: code,
            instructor = instructor ?: existing?.instructor ?: "",
            credits = credits ?: existing?.credits,
            attendanceRate = rate ?: existing?.attendanceRate,
        )
        classCourseDao.upsertAll(listOf(merged))
    }

    private fun openRuns(uri: Uri): List<TextRun> {
        val input = context.contentResolver.openInputStream(uri)
            ?: error("PDF を開けませんでした")
        return input.use { AndroidPdfTextExtractor.extract(it) }
    }
}
