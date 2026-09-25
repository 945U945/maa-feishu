package com.feishu.checkin.checkin.engine

import com.feishu.checkin.accessibility.CheckInAccessibilityService
import com.feishu.checkin.checkin.data.BuiltInPlans
import com.feishu.checkin.checkin.model.CheckInStep
import com.feishu.checkin.checkin.model.StepAction
import com.feishu.checkin.checkin.model.SwipeDirection
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * 单步执行结果。
 *
 * 区分 [SKIPPED]（可选步骤没找到）与 [FAILED] 很重要 ——
 * 前者是「版本差异，正常」，后者是「真出问题了」。
 * 混在一起会让日志失去诊断价值。
 */
sealed interface StepOutcome {
    /** 成功执行 */
    data object SUCCESS : StepOutcome

    /** 可选步骤未命中，跳过（不算失败） */
    data object SKIPPED : StepOutcome

    /** 执行失败 */
    data class FAILED(val reason: String) : StepOutcome
}

/**
 * 步骤执行器。
 *
 * 把「执行一个步骤」的全部复杂度收在这里：等待控件出现、
 * 超时控制、可选项跳过、滑动重试。上层 [CheckInEngine] 只管按顺序调用。
 *
 * ## 为什么不用「固定 sleep + 点击」
 *
 * 这是自动化脚本最常见的写法，也是最脆的：sleep 短了控件还没出来就点空，
 * sleep 长了整体变慢且仍然不可靠（低端机加载更慢）。
 *
 * 正确做法是**等待条件成立**：轮询 + 内容变化事件双管齐下，
 * 一旦控件出现立即动作。这样既快又稳，与设备性能解耦。
 */
class StepExecutor {

    /** 轮询间隔。100ms 是在响应速度与耗电之间的折中 */
    private val pollIntervalMs = 100L

    /**
     * 执行一个步骤。
     *
     * @param service 无障碍服务实例（调用方保证非空）
     * @param step 待执行的步骤
     * @param timeoutMs 覆盖步骤自带的超时（用于全局设置）
     */
    suspend fun execute(
        service: CheckInAccessibilityService,
        step: CheckInStep,
        timeoutMs: Long = step.timeoutMs,
    ): StepOutcome {
        Timber.d("执行步骤: %s [%s] %s", step.name, step.action, step.target.describe())

        val outcome = when (step.action) {
            StepAction.CLICK -> executeClick(service, step, timeoutMs)
            StepAction.WAIT_TEXT -> executeWaitText(service, step, timeoutMs)
            StepAction.SWIPE -> executeSwipe(service, step, timeoutMs)
            StepAction.BACK -> executeBack(service)
        }

        return when {
            outcome is StepOutcome.FAILED && step.optional -> {
                Timber.i("可选步骤未命中，跳过: %s", step.name)
                StepOutcome.SKIPPED
            }

            else -> {
                if (outcome is StepOutcome.FAILED) {
                    Timber.w("步骤失败: %s —— %s", step.name, outcome.reason)
                }
                outcome
            }
        }
    }

    /**
     * 点击步骤。
     *
     * 实现要点：**先等控件出现再点**，而不是「立刻点，失败就放弃」。
     * 飞书页面有转场动画，点击时控件往往还没渲染出来。
     */
    private suspend fun executeClick(
        service: CheckInAccessibilityService,
        step: CheckInStep,
        timeoutMs: Long,
    ): StepOutcome {
        val appeared = awaitNode(service, step, timeoutMs)
        if (!appeared) {
            return StepOutcome.FAILED("等待控件超时: ${step.target.describe()}")
        }

        // 控件出现后再给一帧的稳定时间。部分自绘控件刚出现时
        // 还没绑定点击监听，立刻点会「点了个空」
        delay(CLICK_SETTLE_MS)

        val ok = service.click(step.target)
        if (!ok) return StepOutcome.FAILED("点击未生效: ${step.target.describe()}")

        // 点击后等待界面响应，避免下一步在旧界面上找控件
        delay(AFTER_CLICK_MS)
        return StepOutcome.SUCCESS
    }

    /**
     * 等待文字出现。
     *
     * 用于确认「页面已就绪」。找到即成功，不产生任何操作。
     */
    private suspend fun executeWaitText(
        service: CheckInAccessibilityService,
        step: CheckInStep,
        timeoutMs: Long,
    ): StepOutcome {
        val appeared = awaitNode(service, step, timeoutMs)
        return if (appeared) {
            StepOutcome.SUCCESS
        } else {
            StepOutcome.FAILED("等待文字超时: ${step.target.describe()}")
        }
    }

    /**
     * 滑动步骤。
     *
     * 语义是「边滑边找」：目标可能已经在屏幕上（很多机型工作台一屏就能看到考勤），
     * 那就直接成功不滑；找不到才滑，最多滑 [MAX_SWIPES] 次 ——
     * 无限滑下去会把列表滑到不存在的位置，反而更找不到。
     */
    private suspend fun executeSwipe(
        service: CheckInAccessibilityService,
        step: CheckInStep,
        timeoutMs: Long,
    ): StepOutcome {
        // 先看目标是否已经可见，是则无需滑动
        if (service.findNode(step.target) != null) {
            Timber.d("目标已可见，跳过滑动: %s", step.name)
            return StepOutcome.SUCCESS
        }

        val direction = parseDirection(step)
        val deadline = System.currentTimeMillis() + timeoutMs

        repeat(MAX_SWIPES) { attempt ->
            if (System.currentTimeMillis() > deadline) {
                return StepOutcome.FAILED("滑动查找超时: ${step.target.describe()}")
            }
            service.swipe(direction)
            delay(SWIPE_SETTLE_MS)

            if (service.findNode(step.target) != null) {
                Timber.d("第 %d 次滑动后找到目标", attempt + 1)
                return StepOutcome.SUCCESS
            }
        }
        return StepOutcome.FAILED("滑动 $MAX_SWIPES 次仍未找到: ${step.target.describe()}")
    }

    /**
     * 返回上一步。
     *
     * 目前没用上，但方案格式支持 —— 部分企业考勤从子页面进入，
     * 返回时需要一个明确的返回动作。
     */
    private suspend fun executeBack(service: CheckInAccessibilityService): StepOutcome {
        val ok = service.pressBack()
        delay(AFTER_CLICK_MS)
        return if (ok) StepOutcome.SUCCESS else StepOutcome.FAILED("返回操作失败")
    }

    /**
     * 等待控件出现。
     *
     * 两条腿走路：
     * 1. **轮询** 控件树 —— 保证一定能发现（内容变化事件可能丢失）
     * 2. **等待内容变化事件** —— 保证响应及时（不必死等到超时）
     *
     * 用 `withTimeoutOrNull` 包住整个等待，无论哪条腿成功都立即返回。
     *
     * @return true 表示在超时前找到了目标
     */
    private suspend fun awaitNode(
        service: CheckInAccessibilityService,
        step: CheckInStep,
        timeoutMs: Long,
    ): Boolean {
        // 先立即检查一次：多数情况下控件已经在屏幕上了，
        // 这时不必浪费一个轮询周期
        if (service.findNode(step.target) != null) return true

        val found = withTimeoutOrNull(timeoutMs) {
            while (true) {
                // 等待「内容变化」或「轮询间隔」二者中先到的那个。
                // 这样页面一变就能立刻发现（而不是等满 100ms），
                // 页面不变时也不会空转。
                withTimeoutOrNull(pollIntervalMs) {
                    service.awaitContentChange().await()
                }
                if (service.findNode(step.target) != null) return@withTimeoutOrNull true
            }
            @Suppress("UNREACHABLE_CODE")
            false
        }
        return found == true
    }

    private fun parseDirection(step: CheckInStep): SwipeDirection {
        // 滑动方向从选择器的 textContains 里读（形如 "UP"/"DOWN"），
        // 这样不必给 CheckInStep 再加一个字段 —— 保持模型精简
        val raw = step.target.textContains.uppercase()
        return runCatching { SwipeDirection.valueOf(raw) }
            .getOrDefault(BuiltInPlans.DEFAULT_SWIPE)
    }

    private companion object {
        /** 控件出现后、点击前的稳定等待 */
        const val CLICK_SETTLE_MS = 250L

        /** 点击后等待界面响应 */
        const val AFTER_CLICK_MS = 500L

        /** 滑动后等待惯性滚动停下 */
        const val SWIPE_SETTLE_MS = 600L

        /** 最大滑动次数 —— 超过说明目标不在这个列表里 */
        const val MAX_SWIPES = 4
    }
}
