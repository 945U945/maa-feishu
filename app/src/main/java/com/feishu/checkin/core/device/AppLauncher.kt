package com.feishu.checkin.core.device

import android.content.Context
import android.content.Intent
import android.os.Build
import timber.log.Timber

/**
 * 拉起飞书。
 *
 * ## 关键区别：冷启动 vs 唤醒已有任务
 *
 * `getLaunchIntentForPackage()` 返回的是**冷启动入口 Intent**。
 * 如果飞书已经在后台，用它拉起会导致 Activity 重建 ——
 * 界面状态丢失、加载时间变长（飞书冷启动可能要好几秒），
 * 而打卡步骤的超时通常只有 8 秒，很容易因此超时失败。
 *
 * 正确做法是优先「唤醒已有任务」：
 *
 * 1. 有后台任务 → `FLAG_ACTIVITY_RESET_TASK_IF_NEEDED` 把任务带回前台，秒开
 * 2. 没有 → 退回 `getLaunchIntentForPackage()` 冷启动
 *
 * 这个差别在真机上非常明显，是上一版超时失败的主要原因之一。
 */
class AppLauncher(
    private val context: Context,
) {

    /**
     * 把目标应用带到前台。
     *
     * @param packageName 目标包名
     * @return true 表示已成功发出启动请求
     */
    fun launch(packageName: String): Boolean {
        // 路径一：应用已在后台，直接唤醒其任务栈
        if (bringExistingTaskToFront(packageName)) {
            Timber.i("已唤醒 %s 的后台任务", packageName)
            return true
        }

        // 路径二：冷启动
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            Timber.w("未找到 %s 的启动入口（可能未安装或被包可见性限制）", packageName)
            return false
        }
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
        )
        return runCatching {
            context.startActivity(intent)
            Timber.i("已冷启动 %s", packageName)
            true
        }.onFailure { Timber.w(it, "启动 %s 失败", packageName) }
            .getOrDefault(false)
    }

    /**
     * 尝试把已有任务带到前台。
     *
     * 用 `getLaunchIntentForPackage` 拿到入口后再补 `FLAG_ACTIVITY_RESET_TASK_IF_NEEDED`，
     * 系统会优先复用已存在的任务栈而不是新建 Activity。
     *
     * 之所以不用 `ActivityManager.getRunningTasks` 先判断「是否在后台」：
     * 那个 API 从 Android 5.0 起对第三方应用只返回自己的任务，
     * 拿不到别家应用的信息。因此改为「直接尝试复用，失败再冷启动」——
     * 这也是系统推荐的写法。
     */
    private fun bringExistingTaskToFront(packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return false
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT,
        )
        return runCatching {
            context.startActivity(intent)
            true
        }.onFailure { Timber.d(it, "复用任务栈失败，将走冷启动") }
            .getOrDefault(false)
    }

    /**
     * 打开系统设置页。
     *
     * 供「运行环境检查」卡片引导用户去开启权限。
     * 所有 Intent 都做了兜底：部分 ROM 缺少某个设置页，
     * 直接 startActivity 会抛 ActivityNotFoundException 导致崩溃，
     * 因此统一用 runCatching 包裹并在失败时返回 false，
     * 由 UI 提示用户手动前往。
     */
    fun openSystemSettings(action: String): Boolean = runCatching {
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.onFailure { Timber.w(it, "打开设置页失败: %s", action) }
        .getOrDefault(false)

    /** 打开无障碍设置页 */
    fun openAccessibilitySettings(): Boolean =
        openSystemSettings(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)

    /** 打开本应用的电池优化设置页 */
    fun openBatteryOptimizationSettings(): Boolean =
        openSystemSettings(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    /** 打开本应用的详情页（部分 ROM 的自启动开关在这里面） */
    fun openAppDetailsSettings(): Boolean = runCatching {
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.onFailure { Timber.w(it, "打开应用详情页失败") }
        .getOrDefault(false)

    /** 打开精确闹钟授权页（仅 Android 12+ 有意义） */
    fun openExactAlarmSettings(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return openSystemSettings(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
    }
}
