package com.feishu.checkin.checkin.data

import com.feishu.checkin.checkin.model.CheckInKind
import com.feishu.checkin.checkin.model.CheckInPlan
import com.feishu.checkin.checkin.model.CheckInStep
import com.feishu.checkin.checkin.model.StepAction
import com.feishu.checkin.checkin.model.StepSelector
import com.feishu.checkin.checkin.model.SwipeDirection

/**
 * 内置打卡方案。
 *
 * ## 为什么要把点击路径写成数据
 *
 * 飞书考勤入口在不同企业、不同版本里的位置不一样：
 * 有的在工作台顶部，有的在「我的 → 考勤」，有的企业换成自建应用。
 * 把路径落成数据后，适配新布局只需加一份 JSON，不必改代码重新发版 ——
 * 这是从 MAA 的 `tasks.json` 学到的核心思想。
 *
 * ## 关于选择器的写法（重要）
 *
 * 每个 [StepSelector] 都同时给了 `text` 与 `textContains` 两条腿：
 * 飞书按钮文案常带空格或版本后缀，精确匹配很容易被一个小改动打断，
 * 而包含匹配又可能误命中。两者并存，先精确后包含，容错最好。
 *
 * `optional = true` 的步骤用于处理版本差异 ——
 * 比如旧版有「我知道了」引导弹窗、新版没有。
 * 标为可选后同一份方案能同时兼容两版，不必为每个版本单独出方案。
 */
object BuiltInPlans {

    /** 标准版：工作台 → 考勤打卡 */
    val WORKBENCH = CheckInPlan(
        id = "builtin_workbench",
        name = "标准版（工作台 → 考勤打卡）",
        steps = listOf(
            // ── 0. 可能存在的引导弹窗，关掉它 ──
            CheckInStep(
                name = "关闭引导弹窗",
                action = StepAction.CLICK,
                target = StepSelector(text = "我知道了"),
                timeoutMs = 3_000L,
                optional = true,
            ),

            // ── 1. 进入「工作台」tab ──
            CheckInStep(
                name = "进入工作台",
                action = StepAction.CLICK,
                target = StepSelector(
                    text = "工作台",
                    contentDesc = "工作台",
                    // 底部 tab 里「工作台」有时是第二个
                ),
                timeoutMs = 6_000L,
            ),

            // ── 2. 在工作台里找到「考勤打卡」入口 ──
            // 工作台应用列表可能需要下滑才能看到考勤
            CheckInStep(
                name = "下滑寻找考勤入口",
                action = StepAction.SWIPE,
                target = StepSelector(textContains = "考勤打卡"),
                timeoutMs = 6_000L,
                optional = true,
            ),
            CheckInStep(
                name = "点击考勤打卡",
                action = StepAction.CLICK,
                target = StepSelector(
                    text = "考勤打卡",
                    textContains = "考勤打卡",
                ),
                timeoutMs = 8_000L,
            ),

            // ── 3. 等待考勤页加载完成 ──
            // 「打卡」二字是考勤页最稳定的标志，用它确认页面就绪
            CheckInStep(
                name = "等待考勤页就绪",
                action = StepAction.WAIT_TEXT,
                target = StepSelector(textContains = "打卡"),
                timeoutMs = 15_000L,
            ),
        ),
        // 已经打过卡时页面会出现的提示。命中即判定 ALREADY_DONE，
        // 绝不能再点一次 —— 重复打卡在有些企业会被记为异常
        alreadyDoneKeywords = listOf(
            "已打卡",
            "已签到",
            "更新打卡",
            "打卡成功",
            "今日已打卡",
        ),
        successKeywords = listOf(
            "打卡成功",
            "打卡完成",
            "签到成功",
            "已打卡",
        ),
        builtIn = true,
    )

    /**
     * 「我的」页版：我的 → 考勤打卡
     *
     * 部分企业把考勤入口放在个人页而不是工作台，作为备选方案。
     */
    val PROFILE = CheckInPlan(
        id = "builtin_profile",
        name = "个人页（我的 → 考勤打卡）",
        steps = listOf(
            CheckInStep(
                name = "关闭引导弹窗",
                action = StepAction.CLICK,
                target = StepSelector(text = "我知道了"),
                timeoutMs = 3_000L,
                optional = true,
            ),
            CheckInStep(
                name = "进入我的",
                action = StepAction.CLICK,
                target = StepSelector(text = "我的", contentDesc = "我的"),
                timeoutMs = 6_000L,
            ),
            CheckInStep(
                name = "点击考勤打卡",
                action = StepAction.CLICK,
                target = StepSelector(text = "考勤打卡", textContains = "考勤"),
                timeoutMs = 8_000L,
            ),
            CheckInStep(
                name = "等待考勤页就绪",
                action = StepAction.WAIT_TEXT,
                target = StepSelector(textContains = "打卡"),
                timeoutMs = 15_000L,
            ),
        ),
        alreadyDoneKeywords = listOf("已打卡", "已签到", "更新打卡", "打卡成功", "今日已打卡"),
        successKeywords = listOf("打卡成功", "打卡完成", "签到成功", "已打卡"),
        builtIn = true,
    )

    /**
     * 搜索版：全局搜索「考勤打卡」
     *
     * 这是兜底方案 —— 当前两个都失效时（比如企业把入口藏得很深），
     * 用飞书的全局搜索直接跳到应用。稳定性最好但速度最慢。
     */
    val SEARCH = CheckInPlan(
        id = "builtin_search",
        name = "搜索（全局搜索考勤打卡）",
        steps = listOf(
            CheckInStep(
                name = "打开搜索",
                action = StepAction.CLICK,
                target = StepSelector(
                    text = "搜索",
                    textContains = "搜索",
                    contentDesc = "搜索",
                ),
                timeoutMs = 6_000L,
            ),
            CheckInStep(
                name = "输入关键词",
                action = StepAction.WAIT_TEXT,
                target = StepSelector(className = "android.widget.EditText"),
                timeoutMs = 6_000L,
            ),
            CheckInStep(
                name = "进入考勤打卡",
                action = StepAction.CLICK,
                target = StepSelector(text = "考勤打卡", textContains = "考勤打卡"),
                timeoutMs = 8_000L,
            ),
            CheckInStep(
                name = "等待考勤页就绪",
                action = StepAction.WAIT_TEXT,
                target = StepSelector(textContains = "打卡"),
                timeoutMs = 15_000L,
            ),
        ),
        alreadyDoneKeywords = listOf("已打卡", "已签到", "更新打卡", "打卡成功", "今日已打卡"),
        successKeywords = listOf("打卡成功", "打卡完成", "签到成功", "已打卡"),
        builtIn = true,
    )

    /** 全部内置方案 */
    val ALL: List<CheckInPlan> = listOf(WORKBENCH, PROFILE, SEARCH)

    /** 按 ID 查内置方案 */
    fun byId(id: String): CheckInPlan? = ALL.firstOrNull { it.id == id }

    /**
     * 打卡按钮的定位器。
     *
     * 单独抽出来而不是写进步骤里，是因为「点打卡按钮」这一步
     * 需要根据当前是上班还是下班、是否已打过卡做不同处理，
     * 由执行引擎动态决定，不适合固化成静态步骤。
     *
     * 注意 selector 的 index = 0：考勤页上「上班打卡」和「下班打卡」
     * 是两个按钮，引擎会根据 [CheckInKind] 选正确的那个（见引擎实现）。
     */
    fun checkInButtonSelector(): StepSelector = StepSelector(
        text = "打卡",
        textContains = "打卡",
        index = 0,
    )

    /** 上班打卡按钮的候选文案 */
    fun clockInKeywords(): List<String> = listOf("上班打卡", "签到", "上班签到")

    /** 下班打卡按钮的候选文案 */
    fun clockOutKeywords(): List<String> = listOf("下班打卡", "签退", "下班签退")

    /** 某类打卡对应的按钮文案 */
    fun buttonKeywords(kind: CheckInKind): List<String> = when (kind) {
        CheckInKind.CLOCK_IN -> clockInKeywords()
        CheckInKind.CLOCK_OUT -> clockOutKeywords()
    }

    /** 滑动动作的默认方向（工作台列表向下翻找入口） */
    val DEFAULT_SWIPE: SwipeDirection = SwipeDirection.UP
}
