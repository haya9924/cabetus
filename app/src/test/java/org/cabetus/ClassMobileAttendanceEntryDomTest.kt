package org.cabetus

import org.cabetus.data.classweb.ClassMobileAttendanceEntryDom
import org.junit.Assert.*
import org.junit.Test

class ClassMobileAttendanceEntryDomTest {
    @Test fun navigationUsesOnlyVerifiedEntryAndMobileMenu() {
        val script = ClassMobileAttendanceEntryDom.SCRIPT
        assertTrue(script.contains("モバイル出席登録"))
        assertTrue(script.contains("スマートフォンENTER"))
        assertTrue(script.contains("location.hostname!=='class.admin.tus.ac.jp'"))
        assertTrue(script.contains("links.length!==1"))
        assertFalse(script.contains("学生出欠状況確認"))
        assertFalse(script.contains(".submit("))
        assertFalse(script.contains("input"))
        assertFalse(script.contains(".value"))
    }
}
