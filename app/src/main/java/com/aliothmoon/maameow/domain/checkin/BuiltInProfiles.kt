package com.aliothmoon.maameow.domain.checkin

/**
 * 内置打卡方案库。
 *
 * 这里预置常见 App 的打卡规则，用户开箱即用。
 * 规则基于各 App 的公开界面结构编写，实际文案可能随版本变化 ——
 * 若命中失败，可用 App 内的「控件探针」导出真实控件树后微调。
 *
 * 关于飞书的说明：
 * 飞书打卡页启用了防截屏，图像方案不可用，因此全部规则都走无障碍定位。
 * 选择器同时给出多种候选文字（"打卡"、"上班打卡"、"立即打卡"），
 * 用 CONTAINS 匹配以提高不同版本/端的兼容性。
 */
object BuiltInProfiles {

    /** 飞书考勤打卡方案。 */
    val FEISHU = CheckInProfile(
        id = "builtin_feishu",
        name = "飞书考勤打卡",
        targetPackage = "com.ss.android.lark",
        engine = CheckInEngine.ACCESSIBILITY,
        // 打开飞书后需要较长加载时间：启动页 + 首页渲染
        launchDelayMs = 3_000L,
        rules = listOf(
            // 步骤 1：确认已进入飞书，等待首页出现
            CheckInRule(
                name = "等待飞书首页加载",
                action = CheckInAction.WAIT,
                selector = NodeSelector(
                    text = "飞书",
                    clickableOnly = false,
                ),
                timeoutMs = 8_000L,
                optional = true,
            ),

            // 步骤 2：进入考勤打卡入口。
            // 飞书首页的考勤入口文字可能是"考勤"、"打卡"、"考勤打卡"，
            // 这里用 CONTAINS 匹配最宽泛的"打卡"兜住大部分版本。
            CheckInRule(
                name = "进入考勤打卡页",
                action = CheckInAction.CLICK,
                selector = NodeSelector(
                    text = "考勤打卡",
                    clickableOnly = true,
                ),
                timeoutMs = 5_000L,
                optional = true,
            ),

            // 步骤 2 兜底：若上面没命中，尝试更短的"考勤"
            CheckInRule(
                name = "进入考勤页（备用匹配）",
                action = CheckInAction.CLICK,
                selector = NodeSelector(
                    text = "考勤",
                    clickableOnly = true,
                ),
                timeoutMs = 3_000L,
                optional = true,
            ),

            // 步骤 2 兜底 2：直接找"打卡"按钮（部分版本首页直达）
            CheckInRule(
                name = "点击打卡入口（备用匹配）",
                action = CheckInAction.CLICK,
                selector = NodeSelector(
                    text = "打卡",
                    clickableOnly = true,
                ),
                timeoutMs = 3_000L,
                optional = true,
            ),

            // 步骤 3：等待打卡页加载完成
            CheckInRule(
                name = "等待打卡页就绪",
                action = CheckInAction.WAIT,
                selector = NodeSelector(
                    text = "考勤",
                    clickableOnly = false,
                ),
                timeoutMs = 6_000L,
                optional = true,
            ),

            // 步骤 4：核心动作 —— 点击打卡按钮。
            // 飞书打卡按钮在不同场景下文案不同：
            //   未打卡时 → "打卡" / "立即打卡"
            //   已有外勤 → "外勤打卡"
            // 用 CONTAINS 匹配"打卡"可覆盖绝大多数情况。
            CheckInRule(
                name = "点击打卡按钮",
                action = CheckInAction.CLICK,
                selector = NodeSelector(
                    text = "打卡",
                    clickableOnly = true,
                ),
                timeoutMs = 6_000L,
                optional = false,
            ),
        ),

        // 结果校验：读取页面上出现的结果文案
        successRules = listOf(
            CheckInRule(
                name = "读取打卡结果",
                action = CheckInAction.READ_TEXT,
                selector = NodeSelector(
                    text = "打卡",
                    clickableOnly = false,
                ),
            ),
        ),
        successKeywords = listOf(
            "已打卡",
            "打卡成功",
            "已签到",
            "打卡时间",
            "打卡完成",
        ),
    )

    /** 全部内置方案，供 UI 列出让用户选择。 */
    val all: List<CheckInProfile> = listOf(FEISHU)
}
