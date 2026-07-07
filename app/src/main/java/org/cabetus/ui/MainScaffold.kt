package org.cabetus.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import org.cabetus.ui.assignments.AssignmentsScreen
import org.cabetus.ui.hidden.HiddenScreen
import org.cabetus.ui.home.HomeScreen
import org.cabetus.ui.login.LoginScreen
import org.cabetus.ui.more.MoreScreen
import org.cabetus.ui.nav.Routes
import org.cabetus.ui.nav.TopTab
import org.cabetus.ui.pdf.PdfImportScreen
import org.cabetus.ui.settings.SettingsScreen
import org.cabetus.ui.syllabus.SyllabusScreen
import org.cabetus.ui.timetable.TimetableScreen

@Composable
fun MainScaffold(
    pendingRoute: String? = null,
    onPendingConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()

    LaunchedEffect(pendingRoute) {
        if (pendingRoute != null) {
            navController.navigate(pendingRoute)
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
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
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
            modifier = Modifier.padding(padding),
        ) {
            composable(TopTab.HOME.route) {
                HomeScreen(
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onOpenAssignments = { navController.navigate(TopTab.ASSIGNMENTS.route) },
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
                MoreScreen(onOpenSettings = { navController.navigate(Routes.SETTINGS) })
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenLogin = { navController.navigate(Routes.LOGIN) },
                    onOpenPdfImport = { navController.navigate(Routes.PDF_IMPORT) },
                )
            }
            composable(Routes.HIDDEN) {
                HiddenScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.LOGIN) {
                LoginScreen(onDone = { navController.popBackStack() })
            }
            composable(Routes.PDF_IMPORT) {
                PdfImportScreen(onDone = { navController.popBackStack() })
            }
            composable("${Routes.SYLLABUS}/{code}") { entry ->
                val code = entry.arguments?.getString("code").orEmpty()
                SyllabusScreen(courseCode = code, onBack = { navController.popBackStack() })
            }
        }
    }
}
