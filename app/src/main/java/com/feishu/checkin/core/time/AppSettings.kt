package com.feishu.checkin.core.time

import com.feishu.checkin.checkin.model.CheckInKind
import java.time.LocalTime
import java.time.ZoneId

/**
 * 应用的全部可调设置。
 *
 * 做成一个不可变 data class 而不是「散落在各处的 StateFlow」，
 * 有两个明确好处：
 *
 * 1. **单一事实来源**：调度、UI、执行引擎读的是同一份快照，
 *    不会出现「UI 显示 9:00、闹钟定在 8:00」这类不一致。
 * 2. **易于测试**：单元测试直接构造一个 Settings 对象即可，
 *    不必搭 DataStore 与协程环境。
 */
data class AppSettings(
    /** 总开关 */
    val enabled: Boolean = false,

    /** 上班打卡时刻 */
    val clockInTime: LocalTime = DEFAULT_CLOCK_IN,

    /** 下班打卡时刻 */
    val clockOutTime: LocalTime = DEFAULT_CLOCK_OUT,

    /** 上班打卡是否启用（有些人不需要） */
    val clockInEnabled: Boolean = true,

    /** 下班打卡是否启用 */
    val clockOutEnabled: Boolean = true,

    /** 触发日策略：是否只在工作日打卡 */
    val weekdaysOnly: Boolean = true,

    /** 当前选中的方案 ID */
    val planId: String = DEFAULT_PLAN_ID,

    /** 失败重试次数（0 表示不重试） */
    val retryCount: Int = DEFAULT_RETRY,

    /** 单步超时（毫秒） */
    val stepTimeoutMs: Long = 8_000L,

    /** 详细日志开关 */
    val verboseLog: Boolean = false,

    /**
     * 上次已知的系统时区 ID。
     *
     * 用于检测时区变更：若当前时区与这个值不同，
     * 说明用户出差异地或改过时区，需要重排闹钟。
     * 注意存的是 ID 字符串而非 ZoneId，避免序列化兼容问题。
     */
    val knownZoneId: String = "",
) {
    /** 取某个打卡类型对应的时刻 */
    fun timeOf(kind: CheckInKind): LocalTime = when (kind) {
        CheckInKind.CLOCK_IN -> clockInTime
        CheckInKind.CLOCK_OUT -> clockOutTime
    }

    /** 某个打卡类型是否启用 */
    fun isKindEnabled(kind: CheckInKind): Boolean = when (kind) {
        CheckInKind.CLOCK_IN -> clockInEnabled
        CheckInKind.CLOCK_OUT -> clockOutEnabled
    }

    /** 当前启用的全部打卡时刻 */
    val activeTimes: List<LocalTime>
        get() = buildList {
            if (clockInEnabled) add(clockInTime)
            if (clockOutEnabled) add(clockOutTime)
        }

    /** 生效的时区 */
    val zone: ZoneId
        get() = runCatching { ZoneId.of(knownZoneId) }.getOrDefault(ZoneId.systemDefault())

    /** 触发日策略 */
    val dayPolicy: NextTriggerCalculator.DayPolicy
        get() = if (weekdaysOnly) {
            NextTriggerCalculator.DayPolicy.WEEKDAYS_ONLY
        } else {
            NextTriggerCalculator.DayPolicy.EVERY_DAY
        }

    companion object {
        val DEFAULT_CLOCK_IN: LocalTime = LocalTime.of(9, 0)
        val DEFAULT_CLOCK_OUT: LocalTime = LocalTime.of(18, 30)

        const val DEFAULT_PLAN_ID = "builtin_workbench"
        const val DEFAULT_RETRY = 1

        /** 首次启动的默认设置：关闭状态，避免装完就自动打卡 */
        val DEFAULT = AppSettings()
    }
}
