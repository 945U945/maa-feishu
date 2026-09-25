package com.aliothmoon.maameow.constant

/**
 * 全局路由表。
 *
 * 相比原 MAA-Meow 工程，移除了全部游戏相关页面路由
 * （成就、仓库识别、自动战斗、抄作业等），
 * 新增控件探针。
 */
object Routes {
    /** 首页：打卡状态总览 */
    const val HOME = "home"

    /** 定时任务列表 */
    const val SCHEDULE = "schedule"

    /** 定时任务编辑，需带 strategyId 参数 */
    const val SCHEDULE_EDIT = "schedule_edit/{strategyId}"

    /** 定时任务触发日志 */
    const val SCHEDULE_TRIGGER_LOG = "schedule_trigger_log"

    /** 控件探针：导出界面控件树 */
    const val PROBE = "probe"

    /** 设置 */
    const val SETTINGS = "settings"

    /** 运行日志 */
    const val LOG_HISTORY = "log_history"

    /** 错误日志 */
    const val ERROR_LOG = "error_log"

    /** 通知设置 */
    const val NOTIFICATION = "notification"

    /** 壁纸设置 */
    const val WALLPAPER = "wallpaper"

    /** 构造带参数的任务编辑路由 */
    fun scheduleEdit(strategyId: String): String = "schedule_edit/$strategyId"
}
