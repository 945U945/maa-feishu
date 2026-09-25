package com.feishu.checkin.checkin.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.feishu.checkin.checkin.data.SettingsRepository
import com.feishu.checkin.core.di.entryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.ZoneId

/**
 * 开机 / 应用更新 / 时区变更后重建闹钟。
 *
 * ## 为什么需要这么多广播
 *
 * 系统闹钟不是「持久化到磁盘的配置」，而是**内存中的一个待触发队列**。
 * 以下任一情况都会让它失效，而应用本身不会收到任何通知：
 *
 * | 场景 | 广播 | 后果 |
 * |---|---|---|
 * | 设备重启 | `BOOT_COMPLETED` | 所有闹钟清空 |
 * | 应用被更新 | `MY_PACKAGE_REPLACED` | 闹钟清空（进程被杀） |
 * | 用户改时间 | `TIME_SET` | 触发时刻错位 |
 * | 用户改时区 | `TIMEZONE_CHANGED` | 触发时刻错位（最隐蔽） |
 * | 用户给/收精确闹钟权限 | `SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` | 闹钟被降级或清空 |
 *
 * 最后一项特别容易被漏掉：用户先去关了精确闹钟权限、发现打卡不准、
 * 再打开权限 —— 如果没监听这个广播，闹钟不会自动恢复，
 * 用户会以为「打开了也没用」。
 *
 * ## 关于国产 ROM
 *
 * 上述广播在国产 ROM 上经常被拦（尤其 `BOOT_COMPLETED`）。
 * 这是第三层防线，不能保证生效，因此还有第二层：
 * 应用冷启动时的幂等全量重排（见 `MainActivity` / `FeishuCheckInApp`）。
 *
 * 三层协同的效果是：**只要用户手动打开过一次应用，调度就一定会恢复**。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Timber.i("收到广播: %s", action)

        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        scope.launch {
            try {
                handle(context, action)
            } catch (t: Throwable) {
                Timber.e(t, "处理广播时异常: %s", action)
            } finally {
                runCatching { pendingResult.finish() }
            }
        }
    }

    private suspend fun handle(context: Context, action: String) {
        val deps = context.entryPoint()
        val settingsRepository = deps.settingsRepository()
        val alarmScheduler = deps.alarmScheduler()

        var settings = settingsRepository.current()

        // ── 时区变更：把「已知时区」更新为新时区 ──
        // 必须显式处理：用户出国后系统时区变了，
        // 若不更新记录，下次启动时会重复触发一次重排（虽然无害但日志混乱）
        if (action == Intent.ACTION_TIMEZONE_CHANGED) {
            val newZone = ZoneId.systemDefault().id
            if (newZone != settings.knownZoneId) {
                Timber.i("检测到时区变更: %s → %s", settings.knownZoneId, newZone)
                settingsRepository.setKnownZoneId(newZone)
                settings = settingsRepository.current()
            }
        }

        // ── 精确闹钟权限变化：给用户一个明确提示 ──
        // 这个广播在授权和撤权两种情况下都会发，需要查当前状态才知道是哪种
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            action == ACTION_EXACT_ALARM_PERMISSION_CHANGED
        ) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager
            val granted = runCatching { am?.canScheduleExactAlarms() == true }.getOrDefault(false)
            Timber.i("精确闹钟权限变化: %s", if (granted) "已授予" else "已撤销")
        }

        // ── 统一重排（幂等）──
        if (!settings.enabled) {
            Timber.i("自动打卡未启用，不重排闹钟")
            return
        }

        val next = alarmScheduler.scheduleNext(settings)
        if (next != null) {
            Timber.i("广播 %s 后已恢复闹钟调度", action)
        } else {
            Timber.w("广播 %s 后重排失败", action)
        }
    }

    companion object {
        /**
         * 精确闹钟权限变化的广播 action。
         *
         * 这个常量在 `AlarmManager` 里没有公开的 Java 常量
         * （只有系统内部用的字符串），因此在这里定义。
         * 直接写字符串是官方文档推荐的做法。
         */
        const val ACTION_EXACT_ALARM_PERMISSION_CHANGED =
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"

        /** 需要监听的广播全集，供 Manifest 与自检脚本共用 */
        val ACTIONS: List<String> = listOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            ACTION_EXACT_ALARM_PERMISSION_CHANGED,
        )
    }
}
