package com.feishu.checkin.core.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 触发时刻计算的单元测试。
 *
 * ## 这个类值得测的原因
 *
 * 它是整条调度链路里**唯一有真实分支逻辑**的地方，
 * 其余部分都是「把算好的时间塞给系统闹钟」。
 * 一旦算错，表现是「闹钟不响」或「响错时间」——
 * 用户很难描述清楚，开发者也很难复现（要等到特定时刻）。
 * 因此必须在纯 JVM 环境下把所有边界都覆盖掉。
 *
 * 测试全部使用**固定时间戳**而不是 `System.currentTimeMillis()`，
 * 这样结果是确定的，不会出现「白天通过、半夜失败」这种
 * 令人抓狂的随机失败。
 */
class NextTriggerCalculatorTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 构造一个该时区的毫秒时间戳 */
    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        java.time.ZonedDateTime.of(
            LocalDate.of(year, month, day),
            LocalTime.of(hour, minute),
            zone,
        ).toInstant().toEpochMilli()

    // ────────────────────── 每天策略 ──────────────────────

    @Test
    fun `当天时刻还没到时应返回当天`() {
        val now = at(2026, 9, 25, 8, 0)
        val next = NextTriggerCalculator.computeNext(
            nowEpochMillis = now,
            time = LocalTime.of(9, 0),
            zone = zone,
        )

        assertNotNull(next)
        assertEquals(at(2026, 9, 25, 9, 0), next)
    }

    @Test
    fun `当天时刻已过时应返回次日`() {
        val now = at(2026, 9, 25, 10, 0)
        val next = NextTriggerCalculator.computeNext(
            nowEpochMillis = now,
            time = LocalTime.of(9, 0),
            zone = zone,
        )

        assertNotNull(next)
        assertEquals(at(2026, 9, 26, 9, 0), next)
    }

    /**
     * 边界：当前时刻与目标时刻「同一分钟」时，必须返回次日。
     *
     * 如果实现里用 `!isBefore` 而不是 `isAfter`，这里会返回
     * 「今天 9:00」—— 一个已经过去（或正在过去）的时间点。
     * 系统收到这样的闹钟会立即触发，表现为「设置了却没生效，
     * 反而马上打了一次卡」。
     */
    @Test
    fun `时刻正好等于当前时应返回次日`() {
        val now = at(2026, 9, 25, 9, 0)
        val next = NextTriggerCalculator.computeNext(
            nowEpochMillis = now,
            time = LocalTime.of(9, 0),
            zone = zone,
        )

        assertNotNull(next)
        assertEquals("等于当前时刻必须顺延到次日", at(2026, 9, 26, 9, 0), next)
    }

    @Test
    fun `差一秒时应返回当天`() {
        // 8:59:59 → 9:00 应是当天
        val now = at(2026, 9, 25, 8, 59)
        val next = NextTriggerCalculator.computeNext(
            nowEpochMillis = now,
            time = LocalTime.of(9, 0),
            zone = zone,
        )
        assertEquals(at(2026, 9, 25, 9, 0), next)
    }

    // ────────────────────── 跨天 / 跨月 / 跨年 ──────────────────────

    @Test
    fun `跨月边界应正确进位`() {
        val now = at(2026, 9, 30, 23, 0)
        val next = NextTriggerCalculator.computeNext(
            nowEpochMillis = now,
            time = LocalTime.of(9, 0),
            zone = zone,
        )
        assertEquals(at(2026, 10, 1, 9, 0), next)
    }

    @Test
    fun `跨年边界应正确进位`() {
        val now = at(2026, 12, 31, 23, 0)
        val next = NextTriggerCalculator.computeNext(
            nowEpochMillis = now,
            time = LocalTime.of(9, 0),
            zone = zone,
        )
        assertEquals(at(2027, 1, 1, 9, 0), next)
    }

    /** 2 月末：验证不是简单地「+1 天」 */
    @Test
    fun `闰年二月末应正确进位`() {
        // 2028 是闰年，2/29 存在
        val now = at(2028, 2, 28, 23, 0)
        val next = NextTriggerCalculator.computeNext(
            nowEpochMillis = now,
            time = LocalTime.of(9, 0),
            zone = zone,
        )
        assertEquals(at(2028, 2, 29, 9, 0), next)
    }

    // ────────────────────── 工作日策略 ──────────────────────

    /**
     * 周五晚上 → 下一个工作日应该是**下周一**，而不是周六。
     *
     * 这是工作日策略最核心的一条，也是「加班打完卡周末忘了关」
     * 这种场景下用户最在意的行为。
     */
    @Test
    fun `周五晚上应跳过周末到周一`() {
        // 2026-09-25 是周五
        val friday = LocalDate.of(2026, 9, 25)
        assertEquals("测试前提：2026-09-25 应为周五", 5, friday.dayOfWeek.value)

        val now = at(2026, 9, 25, 20, 0)
        val next = NextTriggerCalculator.computeNext(
            nowEpochMillis = now,
            time = LocalTime.of(9, 0),
            zone = zone,
            policy = NextTriggerCalculator.DayPolicy.WEEKDAYS_ONLY,
        )

        assertNotNull(next)
        // 应为 9/28 周一
        assertEquals(at(2026, 9, 28, 9, 0), next)
    }

    @Test
    fun `周六应跳过到周一`() {
        val saturday = LocalDate.of(2026, 9, 26)
        assertEquals("测试前提：2026-09-26 应为周六", 6, saturday.dayOfWeek.value)

        val now = at(2026, 9, 26, 10, 0)
        val next = NextTriggerCalculator.computeNext(
            nowEpochMillis = now,
            time = LocalTime.of(9, 0),
            zone = zone,
            policy = NextTriggerCalculator.DayPolicy.WEEKDAYS_ONLY,
        )
        assertEquals(at(2026, 9, 28, 9, 0), next)
    }

    @Test
    fun `周日应返回周一`() {
        val sunday = LocalDate.of(2026, 9, 27)
        assertEquals("测试前提：2026-09-27 应为周日", 7, sunday.dayOfWeek.value)

        val now = at(2026, 9, 27, 10, 0)
        val next = NextTriggerCalculator.computeNext(
            nowEpochMillis = now,
            time = LocalTime.of(9, 0),
            zone = zone,
            policy = NextTriggerCalculator.DayPolicy.WEEKDAYS_ONLY,
        )
        assertEquals(at(2026, 9, 28, 9, 0), next)
    }

    @Test
    fun `工作日上午应返回当天`() {
        // 2026-09-25 周五 8:00
        val now = at(2026, 9, 25, 8, 0)
        val next = NextTriggerCalculator.computeNext(
            nowEpochMillis = now,
            time = LocalTime.of(9, 0),
            zone = zone,
            policy = NextTriggerCalculator.DayPolicy.WEEKDAYS_ONLY,
        )
        assertEquals(at(2026, 9, 25, 9, 0), next)
    }

    /** 每天策略下，周六也能触发 */
    @Test
    fun `每天策略下周六正常触发`() {
        val now = at(2026, 9, 26, 8, 0)
        val next = NextTriggerCalculator.computeNext(
            nowEpochMillis = now,
            time = LocalTime.of(9, 0),
            zone = zone,
            policy = NextTriggerCalculator.DayPolicy.EVERY_DAY,
        )
        assertEquals(at(2026, 9, 26, 9, 0), next)
    }

    // ────────────────────── 时区 ──────────────────────

    /**
     * 换时区后「9:00 打卡」仍然应该是当地的 9:00。
     *
     * 这验证了「时刻以字符串存储」的设计正确性：
     * 用户的意图是「当地时间 9 点」，而不是「UTC 某个固定偏移」。
     */
    @Test
    fun `不同时区下应使用当地九点`() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        val now = java.time.ZonedDateTime.of(
            LocalDate.of(2026, 9, 25),
            LocalTime.of(8, 0),
            tokyo,
        ).toInstant().toEpochMilli()

        val next = NextTriggerCalculator.computeNext(
            nowEpochMillis = now,
            time = LocalTime.of(9, 0),
            zone = tokyo,
        )

        assertNotNull(next)
        val result = NextTriggerCalculator.toLocalDateTime(next!!, tokyo)
        assertEquals("结果的本地小时应为 9", 9, result.hour)
        assertEquals("结果的本地分钟应为 0", 0, result.minute)
    }

    // ────────────────────── computeNearest ──────────────────────

    @Test
    fun `多个时刻应返回最近的一个`() {
        // 8:00 时，9:00 与 18:30 中应选 9:00
        val now = at(2026, 9, 25, 8, 0)
        val nearest = NextTriggerCalculator.computeNearest(
            nowEpochMillis = now,
            times = listOf(LocalTime.of(9, 0), LocalTime.of(18, 30)),
            zone = zone,
        )

        assertNotNull(nearest)
        assertEquals(LocalTime.of(9, 0), nearest!!.time)
        assertEquals(at(2026, 9, 25, 9, 0), nearest.atEpochMillis)
    }

    @Test
    fun `上午时刻已过时应选下午`() {
        // 10:00 时，9:00 已过，应选当天 18:30
        val now = at(2026, 9, 25, 10, 0)
        val nearest = NextTriggerCalculator.computeNearest(
            nowEpochMillis = now,
            times = listOf(LocalTime.of(9, 0), LocalTime.of(18, 30)),
            zone = zone,
        )

        assertNotNull(nearest)
        assertEquals(LocalTime.of(18, 30), nearest!!.time)
        assertEquals(at(2026, 9, 25, 18, 30), nearest.atEpochMillis)
    }

    /** 两个时刻都过了 → 都顺延到次日，取次日最早的 */
    @Test
    fun `所有时刻已过时应取次日最早`() {
        val now = at(2026, 9, 25, 20, 0)
        val nearest = NextTriggerCalculator.computeNearest(
            nowEpochMillis = now,
            times = listOf(LocalTime.of(9, 0), LocalTime.of(18, 30)),
            zone = zone,
            policy = NextTriggerCalculator.DayPolicy.EVERY_DAY,
        )

        assertNotNull(nearest)
        assertEquals(LocalTime.of(9, 0), nearest!!.time)
        assertEquals(at(2026, 9, 26, 9, 0), nearest.atEpochMillis)
    }

    @Test
    fun `空时刻列表应返回 null`() {
        val nearest = NextTriggerCalculator.computeNearest(
            nowEpochMillis = at(2026, 9, 25, 8, 0),
            times = emptyList(),
            zone = zone,
        )
        assertNull(nearest)
    }

    // ────────────────────── leadTimeFrom ──────────────────────

    @Test
    fun `提前量应为正数`() {
        val now = at(2026, 9, 25, 8, 0)
        val trigger = NextTriggerCalculator.NearestTrigger(
            atEpochMillis = at(2026, 9, 25, 9, 0),
            time = LocalTime.of(9, 0),
        )
        assertEquals(3600_000L, trigger.leadTimeFrom(now))
    }

    /** 已过期时返回 0 而不是负数 —— 负数会让 UI 显示「-3 小时后」 */
    @Test
    fun `已过期时提前量应为零`() {
        val now = at(2026, 9, 25, 10, 0)
        val trigger = NextTriggerCalculator.NearestTrigger(
            atEpochMillis = at(2026, 9, 25, 9, 0),
            time = LocalTime.of(9, 0),
        )
        assertEquals(0L, trigger.leadTimeFrom(now))
    }

    // ────────────────────── 时间转换 ──────────────────────

    @Test
    fun `毫秒与本地时间互转应可逆`() {
        val dt = java.time.LocalDateTime.of(2026, 9, 25, 14, 30, 45)
        val millis = NextTriggerCalculator.toEpochMillis(dt, zone)
        val back = NextTriggerCalculator.toLocalDateTime(millis, zone)
        assertEquals(dt, back)
    }
}
