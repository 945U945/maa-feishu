package com.aliothmoon.maameow.domain.checkin

import android.content.Context
import com.aliothmoon.maameow.service.CheckInAccessibilityService
import kotlinx.coroutines.delay
import timber.log.Timber

/**
 * 打卡执行引擎。
 *
 * 负责按 [CheckInProfile] 里的规则序列逐步操作目标 App，并判定结果。
 * 两条识别路径在此统一编排：
 *
 * ```
 *                    ┌──────────────────────┐
 *   规则里配了 selector ├─► 无障碍控件定位      │  ← 飞书走这条
 *                    │   成功则不再尝试图像   │
 *                    └──────────────────────┘
 *                              │ 命中失败
 *                              ▼
 *                    ┌──────────────────────┐
 *   规则里配了 template ├─► 图像模板匹配        │  ← 其他 App 兜底
 *                    └──────────────────────┘
 * ```
 *
 * 设计上刻意不把两条路写成互斥的两个引擎：现实里同一个 App
 * 可能某些步骤控件好认、某些只能靠图，所以按"每步独立降级"处理。
 */
class CheckInEngine(
    private val context: Context,
) {

    /** 单步执行结果。 */
    sealed interface StepResult {
        /** 该步成功完成 */
        data class Ok(val detail: String) : StepResult

        /** 该步失败，但规则标了 optional，整体流程可继续 */
        data class Skipped(val detail: String) : StepResult

        /** 该步失败且不可忽略，整体流程应中止 */
        data class Failed(val detail: String) : StepResult

        /** 目标 App 不在前台，无法继续 */
        data object AppNotForeground : StepResult
    }

    /** 整体打卡结果。 */
    sealed interface Result {
        data class Success(val detail: String) : Result
        data class Failed(val detail: String) : Result
    }

    /**
     * 执行一次完整打卡。
     *
     * @param profile      打卡方案
     * @param onStep       每步开始/结束的回调，用于写日志与刷新 UI
     */
    suspend fun execute(
        profile: CheckInProfile,
        onStep: suspend (String) -> Unit = {},
    ): Result {
        val service = CheckInAccessibilityService.instance
            ?: return Result.Failed("无障碍服务未开启，无法读取界面。请到系统设置里开启本应用的「无障碍」权限")

        // ── 前置：确认目标 App 已在后台可拉起，等待界面稳定 ──
        onStep("等待 ${profile.name} 界面就绪…")
        delay(profile.launchDelayMs)

        if (!isForegroundReady(service, profile.targetPackage)) {
            return Result.Failed(
                "目标应用（${profile.targetPackage}）未在前台。请确保 App 已打开且未退到桌面"
            )
        }

        // ── 按规则序列逐步执行 ──
        for (rule in profile.rules) {
            onStep("执行：${rule.name}")
            when (val result = executeRule(service, profile, rule)) {
                is StepResult.Ok -> onStep("  ✓ ${rule.name}：${result.detail}")
                is StepResult.Skipped -> onStep("  ⚠ ${rule.name} 跳过：${result.detail}")
                is StepResult.Failed -> return Result.Failed("步骤「${rule.name}」失败：${result.detail}")
                StepResult.AppNotForeground -> return Result.Failed("执行「${rule.name}」时目标应用已不在前台")
            }
            // 步骤间隔，给界面响应与动画留时间，避免连点被判定为异常操作
            delay(STEP_GAP_MS)
        }

        // ── 判定打卡结果 ──
        onStep("判定打卡结果…")
        return verifySuccess(service, profile, onStep)
    }

    /** 执行单条规则：优先无障碍，失败再尝试图像。 */
    private suspend fun executeRule(
        service: CheckInAccessibilityService,
        profile: CheckInProfile,
        rule: CheckInRule,
    ): StepResult {
        // 路径 1：无障碍控件定位
        rule.selector?.let { selector ->
            val node = service.awaitNode(
                selector = selector,
                packageName = profile.targetPackage,
                timeoutMs = rule.timeoutMs,
            )
            if (node != null) {
                return when (rule.action) {
                    CheckInAction.WAIT -> {
                        val text = service.readNodeText(node)
                        StepResult.Ok(if (text.isBlank()) "控件已出现" else "已出现，文字「$text」")
                    }

                    CheckInAction.READ_TEXT -> {
                        val text = service.readNodeText(node)
                        StepResult.Ok("读到文字「$text」")
                    }

                    CheckInAction.CLICK -> {
                        if (service.clickNode(node)) {
                            StepResult.Ok("已点击")
                        } else {
                            StepResult.Failed("控件已找到但点击未生效")
                        }
                    }

                    CheckInAction.CLICK_COORDINATE -> {
                        val rect = android.graphics.Rect().also { node.getBoundsInScreen(it) }
                        if (service.clickCoordinate(rect.exactCenterX(), rect.exactCenterY())) {
                            StepResult.Ok("已按坐标点击")
                        } else {
                            StepResult.Failed("坐标点击失败")
                        }
                    }
                }
            }
            Timber.d("无障碍未命中规则 [%s]，尝试图像兜底", rule.name)
        }

        // 路径 2：图像模板匹配（飞书等防截屏应用会在此自然跳过）
        rule.template?.let { template ->
            return when (val r = ImageTemplateMatcher.match(context, template, rule.timeoutMs)) {
                is ImageTemplateMatcher.MatchResult.Found -> {
                    val rect = r.rect
                    if (service.clickCoordinate(rect.exactCenterX(), rect.exactCenterY())) {
                        StepResult.Ok("图像匹配命中（相似度 ${"%.2f".format(r.score)}）并已点击")
                    } else {
                        StepResult.Failed("图像命中但点击失败")
                    }
                }

                is ImageTemplateMatcher.MatchResult.ScreenSecured ->
                    if (rule.optional) StepResult.Skipped("目标应用禁止截屏，图像方案不可用")
                    else StepResult.Failed("目标应用禁止截屏，图像识别无法进行（请改用无障碍定位）")

                is ImageTemplateMatcher.MatchResult.NotFound ->
                    if (rule.optional) StepResult.Skipped("图像未匹配到（最高相似度 ${"%.2f".format(r.bestScore)}）")
                    else StepResult.Failed("图像未匹配到目标按钮")
            }
        }

        // 两条路径都没配置或都未命中
        return if (rule.optional) {
            StepResult.Skipped("未配置可用定位条件")
        } else {
            StepResult.Failed("控件与图像均未命中，请检查规则配置")
        }
    }

    /**
     * 判定打卡是否成功。
     *
     * 通过 [CheckInProfile.successRules] 读取页面文字，
     * 命中 [CheckInProfile.successKeywords] 任一关键词即视为成功。
     * 若未配置 successRules，则视为"流程走完即成功"。
     */
    private suspend fun verifySuccess(
        service: CheckInAccessibilityService,
        profile: CheckInProfile,
        onStep: suspend (String) -> Unit,
    ): Result {
        if (profile.successRules.isEmpty()) {
            return Result.Success("打卡流程已执行完毕（未配置结果校验规则）")
        }

        // 打卡后面的页面可能有刷新与动画，给一段时间轮询
        repeat(SUCCESS_POLL_TIMES) { attempt ->
            for (rule in profile.successRules) {
                val sel = rule.selector ?: continue
                val node = service.findNode(sel, profile.targetPackage)
                if (node != null) {
                    val text = service.readNodeText(node)
                    if (profile.successKeywords.any { text.contains(it) }) {
                        onStep("  ✓ 识别到成功标识：$text")
                        return Result.Success("打卡成功：$text")
                    }
                }
            }
            if (attempt < SUCCESS_POLL_TIMES - 1) delay(SUCCESS_POLL_INTERVAL_MS)
        }

        return Result.Failed(
            "已执行点击，但未识别到打卡成功标识（关键词：${profile.successKeywords.joinToString("、")}）"
        )
    }

    /**
     * 检查目标 App 是否已在前台。
     *
     * 用无障碍服务记录的最近事件包名判断，比查询运行中进程更轻量，
     * 也不需要额外的权限。
     */
    private fun isForegroundReady(
        service: CheckInAccessibilityService,
        targetPackage: String,
    ): Boolean {
        val current = service.currentPackage ?: return true // 还没收到事件时宽松放行，后续步骤会自然失败
        return current == targetPackage || current == "com.android.systemui"
    }

    companion object {
        private const val STEP_GAP_MS = 600L
        private const val SUCCESS_POLL_TIMES = 8
        private const val SUCCESS_POLL_INTERVAL_MS = 500L
    }
}
