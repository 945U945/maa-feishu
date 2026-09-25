package com.feishu.checkin.checkin.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.feishu.checkin.checkin.receiver.CheckInAlarmReceiver
import com.feishu.checkin.core.time.AppSettings
import com.feishu.checkin.core.time.NextTriggerCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.LocalTime
import java.time.ZoneId

/**
 * 闹钟调度。
 *
 * ## 为什么用 setAlarmClock 而不是 WorkManager
 *
 * WorkManager 的最小周期是 **15 分钟**，且系统允许它延迟执行 ——
 * 设计目标就是「不精确但省电」，适合同步数据这类任务。
 *
 * 打卡对时间敏感（9:00 上班，9:05 就是迟到），必须准点。
 * 而 `setAlarmClock()` 是 Android 提供的**最高优先级**定时 API：
 *
 * - 能强制脱离 Doze 模式准时投递
 * - 代价是状态栏会显示一个常驻闹钟图标（这是系统行为，无法隐藏）
 *
 * 这个图标其实是**好事**：用户一眼能看到「应用排了 9:00 的闹钟」，
 * 比「不知道到底有没有生效」要安心得多。
 *
 * 注意 `setExactAndAllowWhileIdle` 虽然也能在 Doze 下触发，
 * 但系统规定**每 9 分钟最多一次**且不保证精确 —— 不够可靠。
 *
 * ## 三层恢复机制
 *
 * 定时类应用最容易被国产 ROM 干掉的环节就是「闹钟被清掉」：
 * 关机、应用被更新、用户改时区，都会导致已排的闹钟失效。
 * 因此设计了三层恢复（详见各实现）：
 *
 * 1. [scheduleNext] 每次触发后立即续排下一次（防「这次失败导致以后都不跑了」）
 * 2. [rescheduleAll] 冷启动时全量重排（防 ROM 拦截开机广播）
 * 3. [bootReceiver][com.feishu.checkin.checkin.receiver.BootReceiver]
 *    监听开机/更新/时区变更广播
 */
class AlarmScheduler(
    private val context: Context,
) {

    private val alarmManager: AlarmManager?
        get() = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    /**
     * 排下一次闹钟。
     *
     * ## 关键设计：一次只排一个
     *
     * 系统闹钟一次只能设一个同 requestCode 的意图，所以这里不排「所有时刻」，
     * 而是**只排最近的那一个**；触发后再排下一个，形成链式推进。
     *
     * 这样做还有一个隐含好处：链式推进天然具备「自我修复」能力 ——
     * 只要有一次成功触发，后续就会继续排下去，不会因为初始状态缺失而永久失效。
     *
     * @param settings 当前设置
     * @return 排定的触发时刻；null 表示未排（已关闭或无有效时刻）
     */
    suspend fun scheduleNext(settings: AppSettings): Long? = withContext(Dispatchers.IO) {
        // 先清掉旧的，避免残留闹钟与新闹钟并存导致多触发一次
        cancel()

        if (!settings.enabled) {
            Timber.i("自动打卡已关闭，不排闹钟")
            return@withContext null
        }

        val times = settings.activeTimes
        if (times.isEmpty()) {
            Timber.w("上下班打卡都未启用，不排闹钟")
            return@withContext null
        }

        val zone = settings.zone
        val now = System.currentTimeMillis()

        val next = NextTriggerCalculator.computeNearest(
            nowEpochMillis = now,
            times = times,
            zone = zone,
            policy = settings.dayPolicy,
        )
        if (next == null) {
            Timber.w("无法计算下次触发时刻")
            return@withContext null
        }

        val ok = setAlarmClock(next.atEpochMillis, next.time, zone)
        if (!ok) {
            // 精确闹钟权限被拒时退回非精确闹钟 —— 虽然时间不准，
            // 但总好过完全不打。用户会在体检卡片里看到这一项的警告。
            Timber.w("精确闹钟不可用，退回 setAndAllowWhileIdle")
            setInexact(next.atEpochMillis)
        }

        Timber.i(
            "已排下次打卡: %s（%d 毫秒后）",
            formatAt(next.atEpochMillis, zone),
            next.atEpochMillis - now,
        )
        return@withContext next.atEpochMillis
    }

    /**
     * 用 setAlarmClock 排精确闹钟。
     *
     * @return true 表示排成功；false 表示权限不足
     */
    private fun setAlarmClock(atEpochMillis: Long, time: LocalTime, zone: ZoneId): Boolean {
        val am = alarmManager ?: return false

        // Android 12+ 需要显式检查精确闹钟权限。
        // 不检查直接调会抛 SecurityException 导致崩溃 ——
        // 这是很容易漏掉的一处，因为低版本上完全没问题
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            Timber.w("缺少 SCHEDULE_EXACT_ALARM 权限")
            return false
        }

        return runCatching {
            am.setAlarmClock(
                AlarmManager.AlarmClockInfo(atEpochMillis, buildShowIntent(time, zone)),
                buildOperationIntent(),
            )
            true
        }.onFailure { Timber.e(it, "setAlarmClock 失败") }
            .getOrDefault(false)
    }

    /**
     * 降级方案：非精确闹钟。
     *
     * `setAndAllowWhileIdle` 能在 Doze 下唤醒，但允许系统推迟，
     * 因此时间可能晚几分钟到几十分钟。作为兜底使用。
     */
    private fun setInexact(atEpochMillis: Long) {
        val am = alarmManager ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atEpochMillis, buildOperationIntent())
            } else {
                am.set(AlarmManager.RTC_WAKEUP, atEpochMillis, buildOperationIntent())
            }
        }.onFailure { Timber.e(it, "设置非精确闹钟失败") }
    }

    /** 取消已排的闹钟 */
    fun cancel() {
        val am = alarmManager ?: return
        runCatching { am.cancel(buildOperationIntent()) }
            .onFailure { Timber.w(it, "取消闹钟失败") }
    }

    /**
     * 全量重排 —— 冷启动与开机后调用。
     *
     * 这是三层恢复里的第二层，专门应对**国产 ROM 拦截开机广播**的情况。
     * 用户手动打开一次应用就能恢复调度，是最后的保障。
     *
     * 幂等：重复调用不会有副作用（内部先 cancel 再 set）。
     */
    suspend fun rescheduleAll(settings: AppSettings): Long? {
        Timber.i("全量重排闹钟")
        return scheduleNext(settings)
    }

    /**
     * 检查闹钟是否已排。
     *
     * 注意 `PendingIntent.getBroadcast(..., FLAG_NO_CREATE)` 返回 null
     * 只能说明「**本进程创建的**意图不存在」，不能证明系统层面没有闹钟 ——
     * 进程重启后这个查询会返回 null，但闹钟其实还在。
     * 因此这个方法**只用于诊断展示**，不能用来决定是否需要重排。
     * 重排逻辑一律走 [rescheduleAll]（幂等，无脑重排即可）。
     */
    fun hasPendingAlarm(): Boolean = runCatching {
        val intent = Intent(context, CheckInAlarmReceiver::class.java)
            .setAction(ACTION_CHECK_IN)
        val pi = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        pi != null
    }.getOrDefault(false)

    /** 系统闹钟列表里显示的信息（用户点状态栏闹钟图标时看到的内容） */
    private fun buildShowIntent(time: LocalTime, zone: ZoneId): PendingIntent {
        val intent = Intent(context, com.feishu.checkin.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context,
            REQUEST_CODE_SHOW,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 真正触发打卡的意图 */
    private fun buildOperationIntent(): PendingIntent {
        val intent = Intent(context, CheckInAlarmReceiver::class.java)
            .setAction(ACTION_CHECK_IN)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            // FLAG_UPDATE_CURRENT 保证重复排时复用同一个意图，
            // 这样 cancel 能准确取消掉它；用 FLAG_CANCEL_CURRENT 会产生
            // 新旧两个意图，cancel 只取消掉新的，旧的仍会触发 —— 常见坑
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun formatAt(epochMillis: Long, zone: ZoneId): String =
        NextTriggerCalculator.toLocalDateTime(epochMillis, zone)
            .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))

    companion object {
        /** 与 Receiver 共用的 action 标识 */
        const val ACTION_CHECK_IN = "com.feishu.checkin.action.ALARM_CHECK_IN"

        /** 操作意图的 requestCode */
        private const val REQUEST_CODE = 1001

        /** 展示意图的 requestCode（必须与操作意图不同） */
        private const val REQUEST_CODE_SHOW = 1002
    }
}
