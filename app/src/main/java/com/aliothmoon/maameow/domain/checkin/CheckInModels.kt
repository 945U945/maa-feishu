package com.aliothmoon.maameow.domain.checkin

/**
 * 打卡识别引擎类型。
 *
 * 之所以做成双引擎，是因为不同 App 的可访问性差异很大：
 * - [ACCESSIBILITY]：通过无障碍服务读取控件树，按文字/ID/坐标定位。
 *   **飞书等开启了防截屏（FLAG_SECURE）的 App 只能走这条路**，
 *   因为系统截图会得到全黑图片，图像匹配无从谈起。
 * - [IMAGE_TEMPLATE]：截图 + 模板匹配。适用于没有防截屏的应用，
 *   作为无障碍方案失效时的兜底手段。
 */
enum class CheckInEngine {
    /** 无障碍控件定位（首选，稳定、不受分辨率与改版影响） */
    ACCESSIBILITY,

    /** 图像模板匹配（兜底，受防截屏限制） */
    IMAGE_TEMPLATE,
}

/**
 * 单条打卡规则：告诉引擎"在哪找、找什么、找到后怎么点"。
 *
 * 这是整个打卡引擎的配置核心。用户不需要改代码，
 * 通过 [CheckInProfile] 里的规则列表即可适配不同 App。
 *
 * @param name        规则名，用于日志展示（如"打开飞书"、"点击打卡按钮"）
 * @param action      这一步要执行的动作
 * @param selector    无障碍模式下如何定位目标控件
 * @param template    图像模式下如何匹配目标区域
 * @param timeoutMs   等待该步骤出现的最长时间，超时判定失败
 * @param optional    为 true 时该步骤失败不中断整体流程（如"关闭弹窗"）
 */
data class CheckInRule(
    val name: String,
    val action: CheckInAction,
    val selector: NodeSelector? = null,
    val template: ImageTemplate? = null,
    val timeoutMs: Long = 5_000L,
    val optional: Boolean = false,
)

/** 单步动作类型。 */
enum class CheckInAction {
    /** 点击命中的控件 */
    CLICK,

    /** 等待控件出现，不点击（用于确认页面已加载） */
    WAIT,

    /** 读取控件文字，用于判定打卡结果（如识别"已打卡"） */
    READ_TEXT,

    /** 点击按钮中心坐标，不依赖控件命中 */
    CLICK_COORDINATE,
}

/**
 * 无障碍控件选择器。
 *
 * 命中策略为"或"关系：任意一条匹配即算命中，
 * 越靠前的条件优先级越高（用于日志提示命中了哪条）。
 *
 * @param text           控件文字，支持 [MatchMode]
 * @param viewId         控件资源 ID（如 "com.ss.android.lark:id/checkin_btn"）
 * @param contentDesc    无障碍描述
 * @param clickableOnly  是否只匹配可点击控件（避免点到文字标签上）
 * @param index          多条命中时取第几条，默认取第一条
 */
data class NodeSelector(
    val text: String? = null,
    val viewId: String? = null,
    val contentDesc: String? = null,
    val matchMode: MatchMode = MatchMode.CONTAINS,
    val clickableOnly: Boolean = true,
    val index: Int = 0,
)

/** 文本匹配方式。 */
enum class MatchMode {
    /** 完全相等 */
    EXACT,

    /** 包含子串（最常用，抗轻微文案变化） */
    CONTAINS,

    /** 正则匹配 */
    REGEX,
}

/**
 * 图像模板定义。
 *
 * 注意：模板图必须与设备分辨率匹配，不同机型需分别录制。
 * 对于飞书这类防截屏应用，此字段应留空，引擎会自动跳过图像步骤。
 *
 * @param assetPath  模板图在 assets 中的相对路径
 * @param threshold  相似度阈值，0~1，建议 0.85~0.95
 * @param roi        限定搜索区域（相对屏幕的百分比），缩小范围可提速并降低误匹配
 */
data class ImageTemplate(
    val assetPath: String,
    val threshold: Double = 0.90,
    val roi: Roi? = null,
)

/** 相对屏幕的搜索区域，取值 0.0~1.0。 */
data class Roi(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f) {
            "ROI 取值必须在 0.0~1.0 之间：当前 ($left, $top, $right, $bottom)"
        }
        require(left < right && top < bottom) {
            "ROI 必须满足 left < right 且 top < bottom：当前 ($left, $top, $right, $bottom)"
        }
    }
}

/**
 * 一个完整的打卡方案，对应一个"要打卡的 App"。
 *
 * @param id            方案唯一标识
 * @param name          方案名，展示给用户（如"飞书上班打卡"）
 * @param targetPackage 目标 App 包名
 * @param engine        识别引擎
 * @param rules         按顺序执行的规则列表
 * @param successRules  判定打卡成功的规则（对这些规则执行 READ_TEXT 并匹配 [successKeywords]）
 * @param successKeywords 成功判定关键词
 * @param launchDelayMs 打开 App 后等待界面稳定的时间
 * @param windowStart  有效打卡时间窗起点。触发时刻早于该点视为误触发，跳过。
 *                     用途：防止系统闹钟提前/补偿投递导致非工作时间打卡。
 * @param windowEnd    有效打卡时间窗终点，语义同上。
 * @param enabled      方案是否启用
 * @param builtIn      是否为内置方案（内置方案不可删除，只能改参数）
 */
data class CheckInProfile(
    val id: String,
    val name: String,
    val targetPackage: String,
    val engine: CheckInEngine = CheckInEngine.ACCESSIBILITY,
    val rules: List<CheckInRule> = emptyList(),
    val successRules: List<CheckInRule> = emptyList(),
    val successKeywords: List<String> = listOf("已打卡", "打卡成功", "已签到"),
    val launchDelayMs: Long = 2_500L,
    /** 有效时间窗起点，null 表示不限制 */
    val windowStart: java.time.LocalTime? = null,
    /** 有效时间窗终点，null 表示不限制 */
    val windowEnd: java.time.LocalTime? = null,
    val enabled: Boolean = true,
    val builtIn: Boolean = false,
)
