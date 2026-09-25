package com.feishu.checkin.checkin.model

import kotlinx.serialization.Serializable

/**
 * 打卡方案：描述「如何从飞书桌面走到打卡成功」的一条路径。
 *
 * 为什么需要方案抽象而不是写死一套点击逻辑：
 *
 * 飞书考勤入口在不同企业、不同版本里位置不同 ——
 * 有的在工作台，有的在「我的」页，有的企业换成自建应用。
 * 把路径抽成数据后，适配新布局只需加一份 JSON，不必改代码重新发版。
 *
 * 一个方案 = 若干有序的 [CheckInStep]。
 */
@Serializable
data class CheckInPlan(
    val id: String,
    val name: String,
    /** 方案适用的飞书包名，留空表示两个都试 */
    val targetPackage: String = "",
    val steps: List<CheckInStep> = emptyList(),
    /** 判定「已经打过卡」的文案关键字（出现即认为无需再打） */
    val alreadyDoneKeywords: List<String> = emptyList(),
    /** 判定「打卡成功」的文案关键字 */
    val successKeywords: List<String> = emptyList(),
    /** 内置方案不允许删除 */
    val builtIn: Boolean = false,

    /**
     * 入口定位策略。
     *
     * 决定按什么顺序尝试「如何进入考勤页」。
     * 默认 [EntryStrategy.AUTO] —— 深链优先，失败退回点击导航。
     */
    val entryStrategy: EntryStrategy = EntryStrategy.AUTO,

    /**
     * 落点校验关键字：跳转后界面上**应当**出现这些文案，
     * 才认定「确实落在考勤页」。
     *
     * 这是深链方案的安全阀。只发一条链接就宣布成功是不负责任的 ——
     * 链接可能过期、可能被企业策略拦截、可能落到飞书首页。
     * 用界面文案反证落点，才能把「跳过去了」和「跳对地方了」分开。
     *
     * 注意与 [alreadyDoneKeywords] 的区别：那些是"已完成"的证据，
     * 这些是"到对地方了"的证据，两者都要看。
     */
    val landingKeywords: List<String> = emptyList(),
)

/**
 * 一个操作步骤。
 *
 * 只保留打卡场景真正需要的三类动作：
 * - [StepAction.CLICK]      点击某个控件
 * - [StepAction.SWIPE]      滑动（用于翻页/拉起更多入口）
 * - [StepAction.WAIT_TEXT]  等待某段文字出现（确认页面已就绪）
 *
 * 相比 MAA 动辄十几种动作的重型 DSL，这里刻意收窄 ——
 * 每多一种动作，就要多一份维护与测试成本，而打卡用不到。
 */
@Serializable
data class CheckInStep(
    val name: String,
    val action: StepAction,
    val target: StepSelector,
    /** 该步骤的超时时间（毫秒），超过则判定失败 */
    val timeoutMs: Long = DEFAULT_STEP_TIMEOUT_MS,
    /**
     * 是否可选。
     *
     * true 表示「找不到就跳过，不算失败」——
     * 用于处理版本差异：旧版有引导页、新版没有，把引导页步骤标为可选
     * 就能同时兼容两版，不必为每个版本单独出一份方案。
     */
    val optional: Boolean = false,
) {
    companion object {
        const val DEFAULT_STEP_TIMEOUT_MS = 8_000L
    }
}

/** 步骤动作类型 */
@Serializable
enum class StepAction {
    /** 点击控件 */
    CLICK,

    /** 滑动 */
    SWIPE,

    /** 等待文字出现 */
    WAIT_TEXT,

    /** 返回上一级 */
    BACK,
}

/**
 * 控件定位器。
 *
 * 采用「多条件与」而非单一条件，原因是飞书的控件属性在不同版本间会漂移：
 * 只按 text 找，文案改一个标点就失效；只按 viewId 找，飞书内部 ID 不稳定。
 * 同时给出 text + viewId + className，只要有一组能命中即可，
 * 显著提高方案的生命周期。
 *
 * 各字段之间的语义是「或」：任一字段匹配即视为命中候选，
 * 再由 [preferredOrder] 决定取舍。
 */
@Serializable
data class StepSelector(
    /** 精确文本 */
    val text: String = "",
    /** 文本包含（用于文案带动态内容的场景） */
    val textContains: String = "",
    /** 资源 ID，形如 com.ss.android.lark:id/xxx */
    val viewId: String = "",
    /** 控件类名 */
    val className: String = "",
    /** 无障碍描述（contentDescription） */
    val contentDesc: String = "",
    /**
     * 当有多个候选时取第几个（从 0 开始）。
     *
     * 飞书列表里常有多个同文案控件（如多个「打卡」按钮），
     * 用序号消歧比堆更多条件更实用。
     */
    val index: Int = 0,
) {
    /** 是否至少给出了一个有效条件 */
    val isValid: Boolean
        get() = text.isNotEmpty() || textContains.isNotEmpty() ||
            viewId.isNotEmpty() || className.isNotEmpty() || contentDesc.isNotEmpty()

    /** 用于日志展示的可读描述 */
    fun describe(): String = buildList {
        if (text.isNotEmpty()) add("text=$text")
        if (textContains.isNotEmpty()) add("contains=$textContains")
        if (viewId.isNotEmpty()) add("id=${viewId.substringAfterLast('/')}")
        if (className.isNotEmpty()) add("class=${className.substringAfterLast('.')}")
        if (contentDesc.isNotEmpty()) add("desc=$contentDesc")
        if (index > 0) add("index=$index")
    }.joinToString(", ").ifEmpty { "(空选择器)" }
}

/** 滑动方向 */
@Serializable
enum class SwipeDirection {
    UP,
    DOWN,
    LEFT,
    RIGHT,
}
