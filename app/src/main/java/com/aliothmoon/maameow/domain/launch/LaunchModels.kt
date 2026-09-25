package com.aliothmoon.maameow.domain.launch

import com.aliothmoon.maameow.schedule.model.CountdownState
import com.aliothmoon.maameow.utils.i18n.UiText
import java.util.UUID

/**
 * 触发来源。
 *
 * - [Schedule]：定时闹钟触发（无人值守，需要唤醒解锁）
 * - [External]：外部 Intent 调用（供 MacroDroid / Tasker 等联动）
 * - [Manual]：用户在应用内手动点击"立即打卡"
 *
 * 区分来源的意义：只有 [Schedule] 才需要走唤醒解锁流程，
 * 手动触发时设备本来就是亮的，跳过可省去不必要的 PIN 输入。
 */
enum class LaunchSource {
    Schedule,
    External,
    Manual,
}

data class LaunchRequest(
    val requestId: String = UUID.randomUUID().toString(),
    val source: LaunchSource,
    val profileId: String,
    val displayName: String,
    val scheduledTimeMs: Long,
    val forceStart: Boolean = false,
    /** 运行期间屏保；仅后台且待机接管时生效 */
    val autoScreenSaver: Boolean = false,
    val autoSleepAfterTask: Boolean = false,
    /** 启动时已亮屏未锁屏则不熄屏 */
    val skipAutoSleepIfAwake: Boolean = false,
    /** 打卡结束后关闭目标应用 */
    val closeGameAfterTask: Boolean = false,
    val strategyId: String = "",
    val countdownSeconds: Int = DEFAULT_COUNTDOWN_SECONDS,
) {
    companion object {
        const val DEFAULT_COUNTDOWN_SECONDS = 30
    }
}

sealed interface LaunchSession {
    data object Idle : LaunchSession

    data class InFlight(
        val request: LaunchRequest,
        val phase: Phase,
        /**
         * 是否导航/展示倒计时 UI
         * 前台无倒计时为 false；后台 Dialog 倒计时为 true
         */
        val presentUi: Boolean = true,
    ) : LaunchSession

    sealed interface Phase {
        data object DevicePrep : Phase
        data class Counting(val remainingSeconds: Int) : Phase
        data object Preparing : Phase
        data object Starting : Phase
    }
}

sealed interface LaunchUserEvent {
    data object Cancel : LaunchUserEvent
    data object StartNow : LaunchUserEvent
}

sealed interface LaunchEffect {
    data class Feedback(val message: UiText) : LaunchEffect
}

fun LaunchSession.toCountdownState(): CountdownState {
    return when (this) {
        is LaunchSession.InFlight -> when (val phase = phase) {
            is LaunchSession.Phase.Counting -> CountdownState.Counting(
                strategyName = request.displayName,
                remainingSeconds = phase.remainingSeconds,
            )

            LaunchSession.Phase.Preparing,
            LaunchSession.Phase.Starting -> CountdownState.Executing

            else -> CountdownState.Idle
        }

        LaunchSession.Idle -> CountdownState.Idle
    }
}
