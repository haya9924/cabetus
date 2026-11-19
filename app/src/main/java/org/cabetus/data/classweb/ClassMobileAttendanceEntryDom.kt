package org.cabetus.data.classweb

/** Verified on-device menu; navigation only, never attendance registration controls. */
object ClassMobileAttendanceEntryDom {
    val SCRIPT = ClassAttendanceEntryDom.SCRIPT.replace("学生出欠状況確認", "モバイル出席登録")
}
