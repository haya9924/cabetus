package org.cabetus

import org.cabetus.data.classweb.ClassScheduleNoticesDom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClassScheduleNoticesTest {
    @Test fun duplicatesAreCollapsedWithoutReordering() {
        val raw = """{"titles":[" 休講：科目A ","教室変更：科目B","休講：科目A"],"hasMore":true}"""
        assertEquals(
            ClassScheduleNoticesDom.Snapshot(listOf("休講：科目A", "教室変更：科目B"), true),
            ClassScheduleNoticesDom.parse(raw),
        )
    }

    @Test fun emptyAndUnverifiedAreDifferent() {
        assertEquals(ClassScheduleNoticesDom.Snapshot(emptyList(), false),
            ClassScheduleNoticesDom.parse("""{"titles":[],"hasMore":false}"""))
        listOf(null, "null", "{}", "[]", """{"titles":[42],"hasMore":false}""",
            """{"titles":[],"hasMore":"false"}""", """{"titles":[" "],"hasMore":false}""",
            """{"titles":["${"x".repeat(501)}"],"hasMore":false}""").forEach {
            assertNull(ClassScheduleNoticesDom.parse(it))
        }
    }
}
