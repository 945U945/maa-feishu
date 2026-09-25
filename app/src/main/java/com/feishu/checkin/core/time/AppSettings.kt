package com.feishu.checkin.core.time

import com.feishu.checkin.checkin.model.AppLinkEntry
import com.feishu.checkin.checkin.model.CheckInKind
import com.feishu.checkin.checkin.model.EntrySource
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

    /**
     * 用户配置的打卡入口深链（AppLink）。
     *
     * ## 这是本方案的核心配置项
     *
     * 在飞书里长按「考勤打卡」应用 → 分享 → 复制链接，
     * 把得到的链接粘到这里。之后应用就能**一步直达考勤页**，
     * 不必再靠无障碍在工作台里逐层找入口。
     *
     * 留空则退回旧的点进导航方案 —— 这也是默认值，
     * 保证用户不配置也能用（只是稳定性差些）。
     */
    val entryUrl: String = "",

    /**
     * 是否启用深链跳转。
     *
     * 与 [entryUrl] 分开是有意的：用户可能想临时禁用链接
     * 来验证「不用链接时旧方案能不能跑」，而不想删掉辛苦复制的链接。
     */
    val deepLinkEnabled: Boolean = true,

    /**
     * 是否尝试桌面快捷方式。
     *
     * 飞书支持把打卡应用添加到桌面，点图标即直达。
     * 开启后会在深链失败时尝试这条路径。
     */
    val shortcutEnabled: Boolean = true,
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

    /**
     * 是否真的能用深链。
     *
     * 同时要求「开关打开」与「链接非空」——
     * 调用方用这一个属性就好，不必自己拼两个条件
     * （拼漏了就会出现「开关开了但链接空，还去试着跳转」的无效流程）。
     */
    val canUseDeepLink: Boolean
        get() = deepLinkEnabled && entryUrl.isNotBlank()

    /** 触发日策略 */
    val dayPolicy: NextTriggerCalculator.DayPolicy
        get() = if (weekdaysOnly) {
            NextTriggerCalculator.DayPolicy.WEEKDAYS_ONLY
        } else {
            NextTriggerCalculator.DayPolicy.EVERY_DAY
        }

    /**
     * 按优先级排好的入口候选列表。
     *
     * ## 顺序即优先级
     *
     * 1. **用户自定义链接**（[EntrySource.USER]）
     *    最贴合用户的企业配置，成功率最高，排第一。
     *
     * 2. **内置候选**（[EntrySource.BUILT_IN]）
     *    官方通用的 AppLink 形态。作为零配置兜底 ——
     *    不保证命中，但总比什么都不试好。
     *
     * 之所以把「组装候选」这个逻辑放在设置里而不是执行引擎里，
     * 是为了让**执行顺序可被单测直接断言** ——
     * 顺序错了会让「最优入口」被劣质入口抢先，属于难察觉的回归。
     *
     * 注意：当 [deepLinkEnabled] 为 false 时返回空列表 ——
     * 这是「用户主动要求不用深链」的语义，此时应直接走点击导航。
     */
    fun entryCandidates(): List<AppLinkEntry> {
        if (!deepLinkEnabled) return emptyList()

        return buildList {
            if (entryUrl.isNotBlank()) {
                add(
                    AppLinkEntry(
                        source = EntrySource.USER,
                        url = entryUrl.trim(),
                        label = "自定义打卡入口",
                    ),
                )
            }
            if (entryUrl.isBlank()) {
                // 只有用户没配时才用内置候选。
                // 用户配了就不用内置 —— 否则「用户链接失败」会被
                // 「内置候选成功」掩盖，用户以为自己的配置生效了
                addAll(BUILT_IN_ENTRIES)
            }
        }
    }

    companion object {
        val DEFAULT_CLOCK_IN: LocalTime = LocalTime.of(9, 0)
        val DEFAULT_CLOCK_OUT: LocalTime = LocalTime.of(18, 30)

        const val DEFAULT_PLAN_ID = "builtin_workbench"
        const val DEFAULT_RETRY = 1

        /**
         * 内置的 AppLink 候选。
         *
         * ## 这些链接为什么这样写
         *
         * 飞书官方文档给的 AppLink 形式是
         * `https://applink.feishu.cn/client/<类型>/open`。
         * 「考勤打卡」作为工作台应用，其通用形态走 `op`（应用打开）类型。
         *
         * **不保证对所有企业生效** —— 不同企业的考勤可能是自建应用，
         * 路径完全不同。所以这些只是「零配置时值得一试」的兜底，
         * 真正的正确做法仍是让用户复制自己企业的链接。
         *
         * 落点校验会把这些猜错的候选挡下来（跳不成考勤页就报错），
         * 不会出现「猜错了还在错误页面上乱点」。
         */
        private val BUILT_IN_ENTRIES: List<AppLinkEntry> = listOf(
            AppLinkEntry(
                source = EntrySource.BUILT_IN,
                url = "https://applink.feishu.cn/client/op/open" +
                    "?appId=cli_a1f0c9c8e3f9d00c",
                label = "考勤打卡（通用候补）",
            ),
            AppLinkEntry(
                source = EntrySource.BUILT_IN,
                url = "https://applink.feishu.cn/client/attendance/open",
                label = "考勤打卡（考勤频道候补）",
            ),
        )

        /** 首次启动的默认设置：关闭状态，避免装完就自动打卡 */
        val DEFAULT = AppSettings()
    }
}
