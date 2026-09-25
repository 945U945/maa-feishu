package com.feishu.checkin.core.device

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.feishu.checkin.accessibility.AccessibilitySupport

/**
 * 运行环境体检项。
 *
 * 每一项都对应一个「不满足就会静默失败」的前提条件 ——
 * 这是这类自动化应用最容易让用户困惑的地方：功能看起来开着，
 * 但因为某个权限没给，到点什么都不发生。
 *
 * 因此体检结果直接驱动首页那张卡片，把问题显式暴露出来。
 */
enum class HealthItem {
    /** 无障碍服务 —— 没有它整个应用没有任何作用 */
    ACCESSIBILITY,

    /** 通知权限 —— 没有它用户看不到执行结果 */
    NOTIFICATION,

    /** 精确闹钟 —— 关掉后闹钟会被系统推迟，打卡时间就不可信了 */
    EXACT_ALARM,

    /** 电池优化白名单 —— 不加入白名单，国产 ROM 会杀掉后台进程 */
    BATTERY_OPTIMIZATION,

    /** 自启动权限 —— 无法程序化检测，只能提示用户自行确认 */
    AUTO_START,
}

/** 单项体检结果 */
data class HealthStatus(
    val item: HealthItem,
    val ok: Boolean,
    /** 不满足时，该跳转到哪个设置页 */
    val settingsAction: String? = null,
    /** true 表示无法自动检测，只能提示用户手动确认 */
    val manualOnly: Boolean = false,
)

/**
 * 权限与环境状态查询。
 *
 * 全部查询集中在这里，而不是散在 UI 各处 —— 因为同一个判断
 * （如「无障碍是否开启」）首页卡片、执行前置校验、设置页都要用，
 * 集中一处才能保证口径一致。
 */
class PermissionChecker(private val context: Context) {

    /**
     * 执行全部体检。
     *
     * 顺序刻意按「重要程度」排列，UI 直接按顺序展示即可，
     * 用户从上往下处理就是最优路径。
     */
    fun checkAll(): List<HealthStatus> = listOf(
        checkAccessibility(),
        checkNotification(),
        checkExactAlarm(),
        checkBatteryOptimization(),
        checkAutoStart(),
    )

    /** 需要用户处理的项数 */
    fun issueCount(): Int = checkAll().count { !it.ok && !it.manualOnly }

    /**
     * 无障碍服务是否已开启。
     *
     * 这是**唯一**真正致命的一项 —— 其余项不满足只是体验降级，
     * 这一项不满足则打卡完全无法进行。
     */
    fun checkAccessibility(): HealthStatus = HealthStatus(
        item = HealthItem.ACCESSIBILITY,
        ok = AccessibilitySupport.isEnabled(context),
        settingsAction = android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS,
    )

    /** 通知权限（Android 13+ 才需要动态申请） */
    fun checkNotification(): HealthStatus {
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // 13 以下默认授予
        }
        return HealthStatus(
            item = HealthItem.NOTIFICATION,
            ok = ok,
            settingsAction = android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS,
        )
    }

    /**
     * 精确闹钟权限。
     *
     * Android 12 起需要用户显式授权。这个权限特别容易漏 ——
     * 它不像普通权限会在安装时弹出，也不在「权限管理」的常规列表里，
     * 而在「闹钟和提醒」这个单独的开关里。
     *
     * 未授权时系统的行为是：闹钟会被推迟到某个不定的时刻 ——
     * 对打卡来说等于完全失效，所以必须检查。
     */
    fun checkExactAlarm(): HealthStatus {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return HealthStatus(item = HealthItem.EXACT_ALARM, ok = true)
        }
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val ok = runCatching { alarmManager?.canScheduleExactAlarms() == true }
            .getOrDefault(false)
        return HealthStatus(
            item = HealthItem.EXACT_ALARM,
            ok = ok,
            settingsAction = android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
        )
    }

    /** 是否已加入电池优化白名单 */
    fun checkBatteryOptimization(): HealthStatus {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val ok = runCatching { pm?.isIgnoringBatteryOptimizations(context.packageName) == true }
            .getOrDefault(false)
        return HealthStatus(
            item = HealthItem.BATTERY_OPTIMIZATION,
            ok = ok,
            settingsAction = android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
        )
    }

    /**
     * 自启动权限。
     *
     * 这一项**无法程序化检测** —— 各家 ROM（小米/华为/OPPO/vivo）的
     * 自启动开关都是私有实现，没有统一 API。
     * 因此这里只能标为「需手动确认」并跳转到应用详情页，
     * 绝不能假报「已就绪」，那会让用户以为一切正常而实际收不到推送。
     */
    fun checkAutoStart(): HealthStatus = HealthStatus(
        item = HealthItem.AUTO_START,
        ok = false,
        settingsAction = android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        manualOnly = true,
    )

    /** 精确闹钟是否可用（执行前置校验用） */
    fun canScheduleExactAlarm(): Boolean = checkExactAlarm().ok
}
