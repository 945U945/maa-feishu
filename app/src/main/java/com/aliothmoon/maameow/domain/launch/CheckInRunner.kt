package com.aliothmoon.maameow.domain.launch

import android.content.Context
import com.aliothmoon.maameow.app.MainActivity
import com.aliothmoon.maameow.data.checkin.CheckInRepository
import com.aliothmoon.maameow.domain.checkin.CheckInEngine
import com.aliothmoon.maameow.schedule.model.ExecutionResult
import com.aliothmoon.maameow.utils.i18n.UiText
import com.aliothmoon.maameow.utils.i18n.uiTextOf
import com.aliothmoon.maameow.R
import timber.log.Timber

/**
 * 打卡任务执行器。
 *
 * 这是原 MAA-Meow 工程中 `StartTaskChainUseCase` 的替代品：
 * 原实现负责"准备 MAA 任务 → 静音游戏 → 启动 MAA 核心"， 
 * 现在替换为"取出打卡方案 → 打开目标 App → 驱动打卡引擎"。
 *
 * 之所以单独成类而不是塞进 [LaunchPipeline]：
 * 管线负责"什么时候执行、设备准备好了没"（定时、唤醒、解锁、倒计时），
 * 执行器只负责"具体怎么打卡"这一件事 —— 职责分离，
 * 将来要支持新的打卡目标只需新增执行器，管线完全不用动。
 */
class CheckInRunner(
    private val appContext: Context,
    private val repository: CheckInRepository,
    private val engine: CheckInEngine,
    private val appLauncher: suspend (String) -> Boolean,
) {

    /** 执行结果。 */
    sealed interface Result {
        data class Success(val message: String) : Result
        data class Failed(val message: String, val executionResult: ExecutionResult) : Result
    }

    /**
     * 执行一次打卡。
     *
     * @param profileId 打卡方案 ID
     * @param onLog     日志回调，逐条写入触发日志
     */
    suspend operator fun invoke(
        profileId: String,
        onLog: suspend (UiText) -> Unit,
    ): Result {
        val profile = repository.getById(profileId)
            ?: return Result.Failed(
                message = "打卡方案不存在（$profileId），请到设置里重新选择",
                executionResult = ExecutionResult.FAILED_VALIDATION,
            )

        onLog(uiTextOf(R.string.checkin_log_profile_loaded, profile.name))

        // ── 1. 拉起目标 App ──
        onLog(uiTextOf(R.string.checkin_log_launching, profile.name))
        if (!appLauncher(profile.targetPackage)) {
            return Result.Failed(
                message = "无法打开目标应用（${profile.targetPackage}），请确认已安装",
                executionResult = ExecutionResult.FAILED_UI_LAUNCH,
            )
        }

        // ── 2. 驱动打卡引擎 ──
        val result = engine.execute(profile) { detail ->
            onLog(uiTextOf(R.string.checkin_log_step, detail))
        }

        return when (result) {
            is CheckInEngine.Result.Success -> {
                Timber.i("打卡成功：%s", result.detail)
                Result.Success(result.detail)
            }

            is CheckInEngine.Result.Failed -> {
                Timber.w("打卡失败：%s", result.detail)
                Result.Failed(
                    message = result.detail,
                    executionResult = ExecutionResult.FAILED_START,
                )
            }
        }
    }

    /** 当前时间是否落在打卡窗口内（防止误触发）。 */
    fun isWithinWindow(profileId: String, now: java.time.LocalTime): Boolean {
        val profile = repository.getById(profileId) ?: return true
        return now in (profile.windowStart ?: java.time.LocalTime.MIN)..
                (profile.windowEnd ?: java.time.LocalTime.MAX)
    }
}
