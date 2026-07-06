package org.cabetus.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CalendarViewWeek
import androidx.compose.material.icons.filled.Home
import androidx.compose.ui.graphics.vector.ImageVector

/** ボトムナビの4タブ。 */
enum class TopTab(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    HOME("home", "ホーム", Icons.Filled.Home),
    ASSIGNMENTS("assignments", "課題", Icons.AutoMirrored.Filled.Assignment),
    TIMETABLE("timetable", "時間割", Icons.Filled.CalendarViewWeek),
    MORE("more", "その他", Icons.Filled.Apps),
}

/** タブ以外の画面ルート。 */
object Routes {
    const val SETTINGS = "settings"
    const val LOGIN = "login"
    const val PDF_IMPORT = "pdf_import"
    const val SYLLABUS = "syllabus" // syllabus/{code}
    const val SETUP = "setup"
    const val HIDDEN = "hidden"
}
