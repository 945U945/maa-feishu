package com.feishu.checkin.checkin.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.feishu.checkin.checkin.alarm.AlarmScheduler
import com.feishu.checkin.checkin.service.CheckInService
import com.feishu.checkin.core.di.entryPoint
import com.feishu.checkin.checkin.data.SettingsRepository
import com.feishu.checkin.checkin.model.CheckInKind
import com.feishu.checkin.checkin.model.CheckInRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 闹钟触发接收器。
 *
 * ## 核心原则：先续排，后执行
 *
 * 这是从 MAA 学到的、整个调度设计里最关键的一条经验。
 *
 * 直觉上会写成「先执行打卡，执行完再排下一次」——
 * 但这有个致命缺陷：**如果本次执行卡死或崩溃，下一次闹钟就永远不会被排上**，
 * 应用表面上还开着，实际上已经彻底不工作了，而且用户无从察觉。
 *
 * 正确顺序是**反过来的**：
 *
 * ```
 * 1. scheduleNext()   ← 先把下一次排上（秒级操作）
 * 2. 执行打卡          ← 慢操作，可能失败，但已经不影响下次了
 * ```
 *
 * 这样即便第 2 步彻底炸了，明天的闹钟依然准时 ——
 * 故障被限制在「这一次」，而不是扩散成「永久失效」。
 *
 * ## 关于 goAsync
 *
 * 广播接收器的 `onReceive` 必须在 10 秒内返回，否则会被系统杀掉并报 ANR。
 * 而打卡要走十几秒。所以这里用 `goAsync()` 申请一个短暂的后台执行窗口，
 * 并把真正的打卡工作交给**前台服务**去做 ——
 * 因为 `goAsync` 给的窗口也只有约 10 秒，不够用。
 *
 * 因此本接收器的职责只有两件（都在几毫秒内完成）：
 * 1. 续排下一次闹钟
 * 2. 把打卡任务交给前台服务
 */
class CheckInAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmScheduler.ACTION_CHECK_IN) return

        Timber.i("闹钟触发: %s", intent.action)

        // goAsync 让系统知道「我还没处理完，别杀我」。
        // 注意必须在 10 秒内调用 pendingResult.finish()
        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        scope.launch {
            try {
                handle(context)
            } catch (t: Throwable) {
                Timber.e(t, "处理闹钟触发时异常")
            } finally {
                runCatching { pendingResult.finish() }
            }
        }
    }

    private suspend fun handle(context: Context) {
        // 从 Koin 取依赖。用 entryPoint 而不是构造注入，
        // 是因为 BroadcastReceiver 由系统实例化，无法参与依赖注入的构造流程
        val deps = context.entryPoint()
        val settingsRepository = deps.settingsRepository()
        val alarmScheduler = deps.alarmScheduler()

        // ── 第 1 步：先续排下一次（关键！）──
        // 放在最前面，保证后面无论出什么事，调度链都不会断
        runCatching {
            val settings = settingsRepository.current()
            alarmScheduler.scheduleNext(settings)
        }.onFailure { Timber.e(it, "续排下一次闹钟失败") }

        // ── 第 2 步：判断该打上班还是下班 ──
        val settings = settingsRepository.current()
        if (!settings.enabled) {
            Timber.i("自动打卡已关闭，本次触发忽略")
            return
        }

        val kind = determineKind(settings)
        if (kind == null) {
            Timber.w("无法判断打卡类型，本次触发忽略")
            return
        }

        // ── 第 3 步：交给前台服务执行 ──
        // 用前台服务而不是直接在这里执行：
        // 1. BroadcastReceiver 的执行窗口有限，打卡可能超过
        // 2. 前台服务能显示进度通知，用户看得见
        // 3. 前台服务优先级高，不容易被系统回收
        val request = CheckInRequest(
            kind = kind,
            planId = settings.planId,
            scheduledAt = System.currentTimeMillis(),
            manual = false,
        )

        runCatching {
            CheckInService.startForCheckIn(context, request)
            Timber.i("已将打卡任务交给前台服务: %s", kind.name)
        }.onFailure { Timber.e(it, "启动打卡服务失败") }
    }

    /**
     * 判断本次触发对应上班还是下班。
     *
     * 判据是「当前时刻离哪个配置时刻更近」——
     * 比「上午就是上班、下午就是下班」更可靠，
     * 因为有些企业把上班时间设在中午、下班设在深夜（夜班）。
     *
     * 只在与两个时刻的距离**都**超过阈值时才判为无法确定，
     * 避免在边界时刻做出错误判断。
     */
    private fun determineKind(
        settings: com.feishu.checkin.core.time.AppSettings,
    ): CheckInKind? {
        val nowMillis = System.currentTimeMillis()
        val zone = settings.zone

        val candidates = CheckInKind.entries
            .filter { settings.isKindEnabled(it) }
            .map { kind ->
                val target = settings.timeOf(kind)
                val targetMillis = com.feishu.checkin.core.time.NextTriggerCalculator
                    .toEpochMillis(
                        java.time.LocalDate.now(zone).atTime(target),
                        zone,
                    )
                // 可能跨天（如 00:30 的闹钟在昨天配置），取绝对值即可
                kind to kotlin.math.abs(nowMillis - targetMillis)
            }
            .sortedBy { it.second }

        val nearest = candidates.firstOrNull() ?: return null

        // 距离最近的时刻在 30 分钟内，认为就是它。
        // 超过说明本次触发不正常（可能闹钟被系统延后了很久），
        // 此时宁可跳过也不要打错卡
        return if (nearest.second <= KIND_MATCH_WINDOW_MS) {
            nearest.first
        } else {
            Timber.w(
                "触发时刻与配置时刻相差 %d 分钟，超出匹配窗口",
                nearest.second / 60_000L,
            )
            null
        }
    }

    private companion object {
        /** 触发时刻与配置时刻的最大允许偏差 */
        const val KIND_MATCH_WINDOW_MS = 30L * 60_000L
    }
}
