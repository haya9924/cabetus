package org.cabetus

import org.cabetus.data.classweb.*
import org.cabetus.data.local.*
import org.cabetus.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ClassAttendanceSyncPlannerTest {
    private fun preview(date: String="09/28", day: String="月1", mark: String="〇", period: Int?=1) =
        ClassAttendanceImportPreview("化学",listOf(ClassAttendancePreviewRow(2,date,null,day,period,mark,AttendanceResolver.fromMark(mark))),emptyList(),emptyList())
    @Test fun savesVerifiedAcademicYearAndBlank() {
        val row = ClassAttendanceSyncPlanner.plan(preview(mark="　"),2026,"後期","994347W",123).single()
        assertEquals("",row.rawMark)
        assertEquals(123L,row.fetchedAt)
        assertEquals(LocalDate.of(2026,9,28).atStartOfDay(ZoneId.of("Asia/Tokyo")).toInstant().toEpochMilli(),row.date)
    }
    @Test fun resolvesJanuaryToNextYear() {
        val row=ClassAttendanceSyncPlanner.plan(preview("01/11"),2026,"後期","994347W",123).single()
        assertEquals(LocalDate.of(2027,1,11).atStartOfDay(ZoneId.of("Asia/Tokyo")).toInstant().toEpochMilli(),row.date)
    }
    @Test fun rejectsUnverifiedAndAmbiguous() {
        for(p in listOf(preview(day="火1"),preview(period=null),preview(mark="?"),preview().copy(provisional=true))) {
            assertTrue(runCatching{ClassAttendanceSyncPlanner.plan(p,2026,"後期","994347W",1)}.isFailure)
        }
    }
    @Test fun rejectsConflictingDuplicate() {
        val p=preview();val conflict=p.copy(rows=p.rows+preview(mark="×").rows)
        assertTrue(runCatching{ClassAttendanceSyncPlanner.plan(conflict,2026,"後期","994347W",1)}.isFailure)
    }
    @Test fun classProjectionDoesNotMutatePdfOrDuplicateAndFiltersYear() {
        val row=ClassAttendanceSyncPlanner.plan(preview(),2026,"後期","994347W",1).single()
        val pdf=ClassSessionEntity(courseCode=row.courseCode,sessionNo=2,date=row.date,period=1,mark="×")
        assertEquals("〇",EffectiveClassSessions.project(listOf(pdf),listOf(row),2026).single().mark)
        assertEquals("×",pdf.mark)
        assertEquals(listOf(pdf),EffectiveClassSessions.project(listOf(pdf),listOf(row),2025))
        assertEquals(AttendanceStatus.ABSENT,AttendanceResolver.resolve("〇",false,AttendanceStatus.ABSENT).status)
    }
    @Test fun syncScriptHasContextAndExactCodeGuard() {
        assertTrue(ClassAttendanceSyncDom.SCRIPT.contains("div.ui-outputpanel"))
    }
    @Test fun rosterCodeMustBeUnique() {
        assertEquals("994347W",ClassAttendanceSyncDom.courseCode("月1 化学 教員 994347W 出席率100％"))
        assertNull(ClassAttendanceSyncDom.courseCode("994347W 9943439"))
    }
}
