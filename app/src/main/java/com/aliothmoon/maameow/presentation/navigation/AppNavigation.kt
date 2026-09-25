package com.aliothmoon.maameow.presentation.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.aliothmoon.maameow.constant.Routes
import com.aliothmoon.maameow.presentation.view.home.HomeView
import com.aliothmoon.maameow.presentation.view.notification.NotificationSettingsView
import com.aliothmoon.maameow.presentation.view.probe.ProbeView
import com.aliothmoon.maameow.presentation.view.settings.ErrorLogView
import com.aliothmoon.maameow.presentation.view.settings.LogHistoryView
import com.aliothmoon.maameow.presentation.view.settings.SettingsView
import com.aliothmoon.maameow.presentation.view.settings.WallpaperSettingsView
import com.aliothmoon.maameow.presentation.viewmodel.ErrorLogViewModel
import com.aliothmoon.maameow.presentation.viewmodel.HomeViewModel
import com.aliothmoon.maameow.presentation.viewmodel.LogHistoryViewModel
import com.aliothmoon.maameow.presentation.viewmodel.NotificationSettingsViewModel
import com.aliothmoon.maameow.presentation.viewmodel.ProbeViewModel
import com.aliothmoon.maameow.presentation.viewmodel.SettingsViewModel
import com.aliothmoon.maameow.schedule.ui.ScheduleEditView
import com.aliothmoon.maameow.schedule.ui.ScheduleListView
import com.aliothmoon.maameow.schedule.ui.ScheduleTriggerLogView
import org.koin.androidx.compose.koinViewModel

/**
 * 应用主界面骨架。
 *
 * 结构：Scaffold + 底部导航 + NavHost。
 * 主 Tab 路由（首页/定时/探针/设置）在 NavHost 中正常注册，
 * 由 MainTabNavigator 处理跨 Tab 跳转请求。
 *
 * 相比原 MAA-Meow 工程，移除了：
 * - 公告弹窗、首启引导、资源下载遮罩（游戏运营功能）
 * - 画中画与后台任务预览
 * - 成就页面与其调试入口
 */
@Composable
fun AppNavigation(
    mainTabNavigator: MainTabNavigator,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // 响应来自子页面的跨 Tab 跳转请求
    val tabRequest by mainTabNavigator.request.collectAsStateWithLifecycle()
    LaunchedEffect(tabRequest) {
        tabRequest?.let { req ->
            navController.navigate(req.tab.route) {
                popUpTo(Routes.HOME) { inclusive = false }
                launchSingleTop = true
            }
            mainTabNavigator.consume()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            AppBottomNavigation(
                currentRoute = currentRoute.orEmpty(),
                onTabSelected = { tab ->
                    navController.navigate(tab.route) {
                        popUpTo(Routes.HOME) { inclusive = false }
                        launchSingleTop = true
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            NavHost(
                navController = navController,
                startDestination = Routes.HOME,
            ) {
                // ── 主 Tab：首页 ──
                composable(Routes.HOME) {
                    val vm: HomeViewModel = koinViewModel()
                    HomeView(
                        viewModel = vm,
                        onNavigateToProbe = { navController.navigate(Routes.PROBE) },
                        onNavigateToProfiles = { navController.navigate(Routes.SETTINGS) },
                    )
                }

                // ── 主 Tab：定时任务 ──
                composable(Routes.SCHEDULE) {
                    ScheduleListView(
                        onEdit = { id -> navController.navigate(Routes.scheduleEdit(id)) },
                        onViewLogs = { navController.navigate(Routes.SCHEDULE_TRIGGER_LOG) },
                    )
                }

                // ── 主 Tab：控件探针 ──
                composable(Routes.PROBE) {
                    val vm: ProbeViewModel = koinViewModel()
                    ProbeView(viewModel = vm)
                }

                // ── 主 Tab：设置 ──
                composable(Routes.SETTINGS) {
                    val vm: SettingsViewModel = koinViewModel()
                    SettingsView(
                        viewModel = vm,
                        onNavigateToNotification = { navController.navigate(Routes.NOTIFICATION) },
                        onNavigateToLogHistory = { navController.navigate(Routes.LOG_HISTORY) },
                        onNavigateToErrorLog = { navController.navigate(Routes.ERROR_LOG) },
                        onNavigateToWallpaper = { navController.navigate(Routes.WALLPAPER) },
                    )
                }

                // ── 二级页：任务编辑 ──
                composable(
                    route = Routes.SCHEDULE_EDIT,
                    arguments = listOf(navArgument("strategyId") { type = NavType.StringType }),
                ) { entry ->
                    ScheduleEditView(
                        strategyId = entry.arguments?.getString("strategyId").orEmpty(),
                        onBack = { navController.popBackStack() },
                    )
                }

                // ── 二级页：触发日志 ──
                composable(Routes.SCHEDULE_TRIGGER_LOG) {
                    ScheduleTriggerLogView(onBack = { navController.popBackStack() })
                }

                // ── 二级页：通知设置 ──
                composable(Routes.NOTIFICATION) {
                    val vm: NotificationSettingsViewModel = koinViewModel()
                    NotificationSettingsView(
                        viewModel = vm,
                        onBack = { navController.popBackStack() },
                    )
                }

                // ── 二级页：运行日志 ──
                composable(Routes.LOG_HISTORY) {
                    val vm: LogHistoryViewModel = koinViewModel()
                    LogHistoryView(viewModel = vm, onBack = { navController.popBackStack() })
                }

                // ── 二级页：错误日志 ──
                composable(Routes.ERROR_LOG) {
                    val vm: ErrorLogViewModel = koinViewModel()
                    ErrorLogView(viewModel = vm, onBack = { navController.popBackStack() })
                }

                // ── 二级页：壁纸 ──
                composable(Routes.WALLPAPER) {
                    WallpaperSettingsView(onBack = { navController.popBackStack() })
                }
            }
        }
    }
}
