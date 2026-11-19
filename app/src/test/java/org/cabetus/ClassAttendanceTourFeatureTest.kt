package org.cabetus

import org.cabetus.ui.browser.ClassAttendanceTourFeature
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 出欠一覧の自動巡回が、実機検証が終わるまで既定で無効であることを固定する。
 *
 * 有効化するときは、実機で一覧の並び・詳細 DOM の切り替わり・「戻る」・中止を
 * 確認したうえで [ClassAttendanceTourFeature.ENABLED] を true にし、
 * README / guide/SECURITY.md の「未検証・無効」の記述も同時に更新する。
 */
class ClassAttendanceTourFeatureTest {

    @Test
    fun isDisabledByDefaultSoTheTourStartUiAndRunnerAreNeverReached() {
        assertFalse(ClassAttendanceTourFeature.ENABLED)
        assertFalse(ClassAttendanceTourFeature.available())
    }

    @Test
    fun explainsThatTheTourIsIncompleteAndUnverified() {
        assertTrue(ClassAttendanceTourFeature.INCOMPLETE_LABEL.isNotBlank())
        assertTrue(ClassAttendanceTourFeature.DISABLED_REASON.isNotBlank())
        assertTrue(ClassAttendanceTourFeature.DISABLED_REASON.contains("未完成"))
        assertTrue(ClassAttendanceTourFeature.DISABLED_REASON.contains("実機"))
        // 単科プレビューは引き続き利用できることを、無効理由の中で明示する。
        assertTrue(ClassAttendanceTourFeature.DISABLED_REASON.contains("プレビュー"))
    }
}
