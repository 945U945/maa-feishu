package com.feishu.checkin.core.time

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 「下一次该在什么时候打卡」的计算。
 *
 * ## 为什么做成纯函数
 *
 * 这是整个调度链路里唯一有真实逻辑的分支（其余都是「把算好的时间塞给系统闹钟」），
 * 所以刻意做成**无状态、无副作用、不持 Context** 的纯函数：
 *
 * - 可以直接写单元测试覆盖跨天、跨周、夏令时、时区变更等边界
 * - 不会在初始化阶段因依赖缺失而抛异常（上一版闪退的教训之一）
 *
 * ## 与 MAA 的差异
 *
 * MAA 的 `computeNextTrigger` 要支持「每周几 + 时刻」的复杂组合（如关卡限时开放），
 * 打卡场景只需要「每天固定时刻」和「工作日固定时刻」两种，
 * 因此这里刻意收窄，不做 cron 表达式解析 —— 少一份复杂度就少一类 bug。
 */
object NextTriggerCalculator {

    /** 触发日类型 */
    enum class DayPolicy {
        /** 每天 */
        EVERY_DAY,

        /** 仅工作日（周一至周五） */
        WEEKDAYS_ONLY,
    }

    /**
     * 计算下一次触发时刻。
     *
     * @param nowEpochMillis 当前时间（毫秒时间戳），由调用方传入而非内部取 ── 便于测试
     * @param time 每日触发时刻，如 09:00
     * @param zone 计算所用的时区（必须是当前系统时区，跨时区用户靠它修正）
     * @param policy 触发日策略
     * @return 下一次触发的毫秒时间戳；若策略导致永远不触发（理论上不会）则返回 null
     */
    fun computeNext(
        nowEpochMillis: Long,
        time: LocalTime,
        zone: ZoneId,
        policy: DayPolicy = DayPolicy.EVERY_DAY,
    ): Long? {
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowEpochMillis), zone)

        // 从今天开始逐天试探，最多看 8 天（工作日策略下必然能在 7 天内命中）
        for (offset in 0..7) {
            val day = now.toLocalDate().plusDays(offset.toLong())
            if (!dayMatches(day, policy)) continue

            val candidate = ZonedDateTime.of(day, time, zone)

            // 必须严格晚于当前时刻。
            // 用 isAfter 而非 !isBefore，是为了避免「正好等于当前秒」时
            // 算出一个已经过去的时间点，导致闹钟被系统立即触发。
            if (candidate.toInstant().toEpochMilli() > nowEpochMillis) {
                return candidate.toInstant().toEpochMilli()
            }
        }
        return null
    }

    /**
     * 计算下一次触发时刻 —— 在多个时刻里取最近的一个。
     *
     * 打卡场景需要同时排「上班」和「下班」两个时刻，
     * 但系统闹钟一次只能设一个，所以要取最近的那个；
     * 触发后再排下一个，形成链式推进。
     *
     * @param times 所有待排的时刻
     * @return 最近的一次触发时间及其对应的时刻描述
     */
    fun computeNearest(
        nowEpochMillis: Long,
        times: List<LocalTime>,
        zone: ZoneId,
        policy: DayPolicy = DayPolicy.EVERY_DAY,
    ): NearestTrigger? {
        if (times.isEmpty()) return null
        return times
            .mapNotNull { t ->
                computeNext(nowEpochMillis, t, zone, policy)?.let { at ->
                    NearestTrigger(atEpochMillis = at, time = t)
                }
            }
            .minByOrNull { it.atEpochMillis }
    }

    /** 最近一次触发 */
    data class NearestTrigger(
        val atEpochMillis: Long,
        val time: LocalTime,
    ) {
        /** 距离现在还有多久（毫秒），已过期则返回 0 */
        fun leadTimeFrom(nowEpochMillis: Long): Long =
            (atEpochMillis - nowEpochMillis).coerceAtLeast(0L)
    }

    private fun dayMatches(day: LocalDate, policy: DayPolicy): Boolean = when (policy) {
        DayPolicy.EVERY_DAY -> true
        DayPolicy.WEEKDAYS_ONLY -> {
            val dow = day.dayOfWeek.value // 1=周一 … 7=周日
            dow in 1..5
        }
    }

    /** 把 LocalDateTime 转成毫秒时间戳，供 UI 展示与写记录使用 */
    fun toEpochMillis(dt: LocalDateTime, zone: ZoneId): Long =
        dt.atZone(zone).toInstant().toEpochMilli()

    /** 毫秒时间戳转 LocalDateTime */
    fun toLocalDateTime(epochMillis: Long, zone: ZoneId): LocalDateTime =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone).toLocalDateTime()
}
