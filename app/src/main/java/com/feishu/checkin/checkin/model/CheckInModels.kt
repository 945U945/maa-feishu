package com.feishu.checkin.checkin.model

import kotlinx.serialization.Serializable

/**
 * 打卡执行结果。
 *
 * 设计要点：把「本来就不需要打」和「打失败了」严格分开。
 *
 * 这是从 MAA 那里学到的最重要的一条经验 —— 它把 SKIPPED 与 FAILED
 * 分成两类终态。对打卡场景尤其关键：
 *
 * - [ALREADY_DONE]（今天已打卡）是最常见的**正常**情况，
 *   如果记成 FAILED，用户会收到一堆无意义的失败告警，
 *   真正的故障反而被淹没。
 * - [SKIPPED_BUSY] 是并发保护的结果，属于预期行为，不是错误。
 *
 * 因此 [isFailure] 只对真正需要用户关注的几种情况返回 true。
 */
enum class CheckInResult {
    /** 打卡成功 */
    SUCCESS,

    /** 今日已打卡，无需重复 */
    ALREADY_DONE,

    /** 未在飞书中找到打卡入口（通常是方案配置过时） */
    ENTRY_NOT_FOUND,

    /** 网络异常 */
    NETWORK_ERROR,

    /** 设备处于锁屏且无法自动解锁 */
    DEVICE_LOCKED,

    /** 缺少必要权限（无障碍未开等） */
    PERMISSION_MISSING,

    /** 已有打卡任务在执行，本次跳过 */
    SKIPPED_BUSY,

    /** 其它未归类的失败 */
    FAILURE;

    /**
     * 是否属于「需要用户关注的失败」。
     *
     * 注意 ALREADY_DONE 与 SKIPPED_BUSY 都不算失败。
     */
    val isFailure: Boolean
        get() = this == ENTRY_NOT_FOUND ||
            this == NETWORK_ERROR ||
            this == DEVICE_LOCKED ||
            this == PERMISSION_MISSING ||
            this == FAILURE

    /** 是否属于「正常结束」 */
    val isSuccessLike: Boolean
        get() = this == SUCCESS || this == ALREADY_DONE

    /**
     * 是否值得重试。
     *
     * 判据是「这个失败是否与环境的瞬时状态相关」：
     *
     * - **值得重试**：[FAILURE]（结果确认超时，可能只是网络慢）、
     *   [NETWORK_ERROR]（网络抖动，等一下可能就好了）
     * - **不值得重试**：[ENTRY_NOT_FOUND]（方案失效，重试还是找不到）、
     *   [DEVICE_LOCKED]（解锁不了，重试还是解锁不了）、
     *   [PERMISSION_MISSING]（要用户去开权限，重试毫无意义）、
     *   [ALREADY_DONE] 与 [SKIPPED_BUSY]（正常终态）
     *
     * 这条规则的价值在于：对确定性失败重试只会让用户多等几十秒，
     * 并把日志弄脏，反而掩盖真实问题。
     */
    val isRetryable: Boolean
        get() = this == FAILURE || this == NETWORK_ERROR
}

/**
 * 打卡类型。
 *
 * 飞书考勤区分上班/下班两次打卡，两者的时间与语义都不同，
 * 因此在模型层就区分开，避免用「一个任务两个时间」的含糊表达。
 */
enum class CheckInKind {
    /** 上班打卡 */
    CLOCK_IN,

    /** 下班打卡 */
    CLOCK_OUT,
}

/**
 * 执行阶段。
 *
 * 用于把「正在打卡」这个模糊状态拆成可展示、可定位问题的具体步骤。
 * UI 直接展示当前阶段，用户能一眼看出卡在哪一步。
 */
enum class CheckInPhase {
    /** 准备：唤醒屏幕、拉起飞书 */
    PREPARING,

    /** 等待飞书界面就绪 */
    LAUNCHING,

    /** 导航到考勤页 */
    NAVIGATING,

    /** 点击打卡按钮 */
    CHECKING_IN,

    /** 确认打卡结果 */
    VERIFYING,

    /** 已结束 */
    DONE,
}

/**
 * 单次打卡的请求描述。
 *
 * [requestId] 采用「日期 + 类型」而非随机 UUID —— 这是打卡场景的关键差异。
 * 用随机 ID 只能防「同一次请求被提交两次」；
 * 用日期做 ID 才能防「同一天被不同触发源各打一次」，
 * 这正好覆盖了「闹钟触发 + 用户手动点击」同时发生的情况。
 */
data class CheckInRequest(
    val kind: CheckInKind,
    val planId: String,
    /** 计划触发时间（毫秒时间戳） */
    val scheduledAt: Long,
    /** 实际开始执行时间 */
    val startedAt: Long = System.currentTimeMillis(),
    /** 是否由用户手动触发（手动触发时不写「已排下一次」等调度状态） */
    val manual: Boolean = false,
) {
    /** 幂等键：同一天同一类型只允许成功执行一次 */
    val dedupeKey: String get() = "${planId}_${kind.name}_${dayStamp()}"

    private fun dayStamp(): String {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = startedAt
        return "%04d%02d%02d".format(
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
        )
    }
}

/**
 * 一条执行记录（持久化到 checkin_records/）。
 *
 * 结构刻意拆成 header / timeline / footer 三块，
 * 这样列表页只读首尾即可渲染摘要，不必解析整条记录 —— 借鉴 MAA 的
 * ScheduleTriggerLogger 设计。
 */
@Serializable
data class CheckInRecord(
    /** 幂等键，与 CheckInRequest.dedupeKey 对应 */
    val id: String,
    val kind: CheckInKind,
    val planId: String,
    val scheduledAt: Long,
    val startedAt: Long,
    val finishedAt: Long = 0L,
    val result: CheckInResult? = null,
    /** 面向用户的简短说明 */
    val message: String = "",
    /** 执行时间线：每个阶段的开始时间与备注 */
    val timeline: List<TimelineEntry> = emptyList(),
) {
    val durationMs: Long get() = if (finishedAt > 0) finishedAt - startedAt else 0L
}

/** 时间线中的一个节点 */
@Serializable
data class TimelineEntry(
    val phase: CheckInPhase,
    val at: Long,
    val note: String = "",
)
