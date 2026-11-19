package org.cabetus

import org.cabetus.data.classweb.ClassTimetableEntryDom
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassTimetableEntryDomTest {
    @Test fun onlyTheSmartphoneEntranceAndStudentTimetableCanBeOpened() {
        val script = ClassTimetableEntryDom.SCRIPT
        assertTrue(script.contains("location.protocol !== 'https:'"))
        assertTrue(script.contains("location.hostname !== 'class.admin.tus.ac.jp'"))
        assertTrue(script.contains("location.port !== '443'"))
        assertTrue(script.contains("label === 'スマートフォンENTER'"))
        assertTrue(script.contains("MobileShibbolethAuthServlet"))
        assertTrue(script.contains("label === '学生時間割表'"))
        assertTrue(script.contains("links.length !== 1"))
        assertFalse(script.contains("学生出欠状況確認"))
        assertFalse(script.contains("モバイル出席登録"))
        assertFalse(script.contains(".submit("))
    }
}
