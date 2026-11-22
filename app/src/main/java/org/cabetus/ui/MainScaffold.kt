package org.cabetus.ui

import android.widget.Toast
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import org.cabetus.ui.assignments.AssignmentsScreen
import org.cabetus.ui.browser.BrowserIntents
import org.cabetus.ui.browser.BrowserUrlPolicy
import org.cabetus.ui.gp.GpAttendanceImport
import org.cabetus.ui.gp.GpCalculatorScreen
import org.cabetus.ui.hidden.HiddenScreen
import org.cabetus.ui.home.HomeScreen
import org.cabetus.ui.login.LoginScreen
import org.cabetus.ui.more.MoreScreen
import org.cabetus.ui.nav.Routes
import org.cabetus.ui.nav.TopTab
import org.cabetus.ui.notifications.NotificationHistoryScreen
import org.cabetus.ui.settings.SettingsScreen
import org.cabetus.ui.syllabus.SyllabusScreen
import org.cabetus.ui.timetable.TimetableScreen

@Composable
fun MainScaffold(
    openForegroundBrowser: suspend (() -> Boolean) -> Boolean,
    pendingRoute: String? = null,
    onPendingConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun openTab(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            // Home is the root, never restore a saved nested stack on top of it.
            restoreState = route != TopTab.HOME.route
        }
    }

    LaunchedEffect(pendingRoute) {
        if (pendingRoute != null) {
            // ウィジェット・通知からの遷移。タブ切替と同じオプションで
            // 重複スタックを作らないようにする（多重に積むとタブが効かなく見える）。
            if (TopTab.entries.any { it.route == pendingRoute }) openTab(pendingRoute)
            else navController.navigate(pendingRoute) { launchSingleTop = true }
            onPendingConsumed()
        }
    }

    Scaffold(
        bottomBar = {
            val backStackEntry by navController.currentBackStackEntryAsState()
            val currentDest = backStackEntry?.destination
            // タブ画面のときのみボトムナビを表示
            val onTab = TopTab.entries.any { tab ->
                currentDest?.hierarchy?.any { it.route == tab.route } == true
            }
            if (onTab) {
                NavigationBar {
                    TopTab.entries.forEach { tab ->
                        val selected = currentDest?.hierarchy?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                openTab(tab.route)
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = TopTab.HOME.route,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        ) {
            composable(TopTab.HOME.route) {
                HomeScreen(
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onOpenAssignments = { openTab(TopTab.ASSIGNMENTS.route) },
                    onOpenNotifications = { navController.navigate(Routes.NOTIFICATIONS) },
                )
            }
            composable(TopTab.ASSIGNMENTS.route) {
                AssignmentsScreen(
                    onOpenHidden = { navController.navigate(Routes.HIDDEN) },
                )
            }
            composable(TopTab.TIMETABLE.route) {
                TimetableScreen(
                    onOpenSyllabus = { code -> navController.navigate("${Routes.SYLLABUS}/$code") },
                )
            }
            composable(TopTab.MORE.route) {
                MoreScreen(
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onOpenClass = {
                        scope.launch {
                            if (!openForegroundBrowser { BrowserIntents.open(context, BrowserUrlPolicy.CLASS_URL) }) {
                                Toast.makeText(context, "ブラウザを開けませんでした。少し待って再試行してください。", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onOpenGpCalculator = { navController.navigate(Routes.GP_CALCULATOR) },
                )
            }

            composable(Routes.GP_CALCULATOR) {
                GpCalculatorScreen(
                    onBack = { navController.popBackStack() },
                    attendanceImport = { apply -> GpAttendanceImport(onApply = apply) },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenLogin = { navController.navigate(Routes.LOGIN) },
                )
            }
            composable(Routes.HIDDEN) {
                HiddenScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.NOTIFICATIONS) {
                NotificationHistoryScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.LOGIN) {
                LoginScreen(onDone = { navController.popBackStack() })
            }
            composable("${Routes.SYLLABUS}/{code}") { entry ->
                val code = entry.arguments?.getString("code").orEmpty()
                SyllabusScreen(courseCode = code, onBack = { navController.popBackStack() })
            }
        }
    }
}
