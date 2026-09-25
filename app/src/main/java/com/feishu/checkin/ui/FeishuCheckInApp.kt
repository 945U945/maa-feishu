package com.feishu.checkin.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.feishu.checkin.R
import com.feishu.checkin.ui.screen.history.HistoryScreen
import com.feishu.checkin.ui.screen.home.HomeScreen
import com.feishu.checkin.ui.screen.settings.ProbeScreen
import com.feishu.checkin.ui.screen.settings.SettingsScreen

/**
 * 底部导航项。
 *
 * 用 enum 而不是散落的字符串：路由名集中一处，
 * 改名时编译期就能发现所有引用点（上一版有过「改了路由名但某处没改」
 * 导致点击无反应的经历）。
 */
enum class TopLevelDestination(
    val route: String,
    val icon: ImageVector,
    val labelRes: Int,
) {
    HOME("home", Icons.Filled.Home, R.string.nav_home),
    HISTORY("history", Icons.Filled.History, R.string.nav_history),
    SETTINGS("settings", Icons.Filled.Settings, R.string.nav_settings),
}

/** 探针页路由（不属于底部导航，从设置页进入） */
private const val ROUTE_PROBE = "probe"

/**
 * 应用导航骨架。
 *
 * ## 为什么探针页单独一条路由而不是第四个 tab
 *
 * 底部导航有四个以上 tab 时用户会犹豫「该点哪个」。
 * 探针是低频的排障工具，放在设置页里的入口比占一个 tab 更合适 ——
 * 这是「导航层级反映使用频率」的原则。
 */
@Composable
fun FeishuCheckInApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    // 探针页不显示底部导航栏 —— 它是从设置页深入进去的详情页，
    // 保留底部栏会让用户以为还在同一层级，产生导航错觉
    val showBottomBar = currentDestination?.route != ROUTE_PROBE

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    TopLevelDestination.entries.forEach { destination ->
                        val selected = currentDestination?.hierarchy?.any {
                            it.route == destination.route
                        } == true

                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(destination.route) {
                                    // 弹出到起始页：避免底部导航反复切换时
                                    // 回退栈无限增长（点了十次 tab 要按十次返回键，
                                    // 这是 Compose 导航里最常见的体验问题）
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    // 避免重复导航到同一个目的地产生多个实例
                                    launchSingleTop = true
                                    // 切回时恢复该 tab 之前的滚动位置等状态
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = destination.icon,
                                    contentDescription = stringResource(destination.labelRes),
                                )
                            },
                            label = { Text(stringResource(destination.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.HOME.route,
            // NavHost 需要自己处理内边距：底部导航栏的高度会变化
            // （显示/隐藏探针页时），因此这里用 padding 而不是把
            // Modifier 传给各个页面 —— 后者会导致切换时页面跳动
            modifier = Modifier.padding(
                bottom = if (showBottomBar) padding.calculateBottomPadding() else 0.dp,
            ),
        ) {
            composable(TopLevelDestination.HOME.route) {
                HomeScreen()
            }

            composable(TopLevelDestination.HISTORY.route) {
                HistoryScreen()
            }

            composable(TopLevelDestination.SETTINGS.route) {
                SettingsScreen(
                    onOpenProbe = { navController.navigate(ROUTE_PROBE) },
                )
            }

            composable(ROUTE_PROBE) {
                ProbeScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
