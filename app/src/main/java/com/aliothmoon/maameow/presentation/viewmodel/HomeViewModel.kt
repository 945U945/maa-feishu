package com.aliothmoon.maameow.presentation.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aliothmoon.maameow.data.checkin.CheckInRepository
import com.aliothmoon.maameow.domain.checkin.CheckInProfile
import com.aliothmoon.maameow.domain.launch.LaunchMutex
import com.aliothmoon.maameow.domain.launch.LaunchPipeline
import com.aliothmoon.maameow.domain.launch.LaunchRequest
import com.aliothmoon.maameow.domain.launch.LaunchSource
import com.aliothmoon.maameow.schedule.data.ScheduleStrategyRepository
import com.aliothmoon.maameow.schedule.model.ExecutionResult
import com.aliothmoon.maameow.schedule.model.ScheduleStrategy
import com.aliothmoon.maameow.schedule.service.ScheduleAlarmManager
import com.aliothmoon.maameow.schedule.service.ScheduleTriggerLogger
import com.aliothmoon.maameow.service.CheckInAccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 首页状态。
 *
 * @param accessibilityReady 无障碍服务是否已开启（打卡的前提条件）
 * @param targetInstalled    目标应用是否已安装
 * @param nextTriggerLabel   下次打卡的可读描述
 * @param lastResult         上次打卡结果描述
 * @param lastResultSuccess  上次打卡是否成功
 * @param running            当前是否有打卡正在执行
 * @param profiles           已配置的打卡方案
 * @param activeProfileId    当前选中的方案
 */
data class HomeUiState(
    val accessibilityReady: Boolean = false,
    val targetInstalled: Boolean = false,
    val nextTriggerLabel: String = "未设置定时任务",
    val lastResult: String = "",
    val lastResultSuccess: Boolean = false,
    val running: Boolean = false,
    val profiles: List<CheckInProfile> = emptyList(),
    val activeProfileId: String? = null,
)

/**
 * 首页 ViewModel。
 *
 * 职责：汇总"能不能打卡、什么时候打、上次打得怎么样"三类信息，
 * 并提供"立即打卡"的手动触发入口。
 *
 * 相比原工程的 HomeViewModel（管理复杂任务链），
 * 这里的逻辑大幅简化：打卡是单一动作，无需链式编排。
 */
class HomeViewModel(
    private val appContext: Context,
    private val checkInRepository: CheckInRepository,
    private val scheduleRepository: ScheduleStrategyRepository,
    private val alarmManager: ScheduleAlarmManager,
    private val triggerLogger: ScheduleTriggerLogger,
    private val launchPipeline: LaunchPipeline,
    private val launchMutex: LaunchMutex,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        observeData()
        refreshRuntimeStatus()
    }

    /** 订阅方案与定时策略的变化，自动刷新首页。 */
    private fun observeData() {
        viewModelScope.launch {
            checkInRepository.profiles.collect { profiles ->
                val active = _state.value.activeProfileId
                    ?.takeIf { id -> profiles.any { it.id == id } }
                    ?: profiles.firstOrNull()?.id
                _state.update { it.copy(profiles = profiles, activeProfileId = active) }
                refreshTargetInstalled(profiles, active)
            }
        }

        viewModelScope.launch {
            scheduleRepository.strategies.collect { strategies ->
                updateNextTrigger(strategies)
            }
        }
    }

    /** 刷新运行时状态。界面回到前台时调用（用户可能刚去系统设置里开了权限）。 */
    fun refreshRuntimeStatus() {
        _state.update {
            it.copy(
                accessibilityReady = CheckInAccessibilityService.isReady(),
                running = launchMutex.current != null,
            )
        }
        refreshTargetInstalled(_state.value.profiles, _state.value.activeProfileId)
        refreshLastResult()
    }

    /**
     * 检查目标应用是否已安装。
     *
     * 用 getLaunchIntentForPackage 判断：既确认已安装，
     * 也确认存在可启动入口（有些包是组件库，装了也点不开）。
     * 需要 Manifest 的 <queries> 声明包名（targetSdk 30+ 限制），
     * 已在 AndroidManifest 中配置飞书包名。
     */
    private fun refreshTargetInstalled(
        profiles: List<CheckInProfile>,
        activeId: String?,
    ) {
        val profile = profiles.find { it.id == activeId } ?: return
        val installed = try {
            appContext.packageManager.getLaunchIntentForPackage(profile.targetPackage) != null
        } catch (e: Exception) {
            false
        }
        _state.update { it.copy(targetInstalled = installed) }
    }

    /**
     * 计算下次打卡时间。
     *
     * 遍历所有已启用的策略取最早一次触发。
     * 刻意复用 [ScheduleAlarmManager.computeNextTrigger] 而非自己算，
     * 保证"首页显示的时间"与"实际触发时刻"永远一致。
     */
    private fun updateNextTrigger(strategies: List<ScheduleStrategy>) {
        val now = System.currentTimeMillis()
        val next = strategies
            .filter { it.enabled }
            .mapNotNull { strategy ->
                alarmManager.computeNextTrigger(strategy, now)?.toInstant()?.toEpochMilli()
            }
            .minOrNull()

        _state.update {
            it.copy(
                nextTriggerLabel = next?.let { ms -> formatCountdown(ms, now) } ?: "未设置定时任务",
            )
        }
    }

    /** 手动触发一次打卡。 */
    fun runCheckInNow() {
        val profileId = _state.value.activeProfileId ?: return
        if (_state.value.running) return

        _state.update { it.copy(running = true) }
        viewModelScope.launch {
            try {
                // 刻意走完整管线（而非直接调引擎）：
                // 与定时触发共用同一执行路径，确保手动测试的结果
                // 能真实代表定时执行的效果
                val request = LaunchRequest(
                    requestId = "manual-${System.currentTimeMillis()}",
                    source = LaunchSource.Manual,
                    profileId = profileId,
                    displayName = _state.value.profiles.find { it.id == profileId }?.name
                        ?: "手动打卡",
                    scheduledTimeMs = System.currentTimeMillis(),
                    strategyId = "",
                )
                launchPipeline.execute(request).join()
            } finally {
                _state.update { it.copy(running = false) }
                refreshRuntimeStatus()
            }
        }
    }

    /** 切换当前使用的打卡方案。 */
    fun selectProfile(profileId: String) {
        _state.update { it.copy(activeProfileId = profileId) }
        refreshTargetInstalled(_state.value.profiles, profileId)
    }

    /**
     * 读取上次打卡结果。
     *
     * 从触发日志的最新一条摘要中提取状态。
     * 这里只取摘要而不读完整日志：首页只需要"成功/失败"，
     * 完整内容在日志页查看，避免首页做多余的磁盘 IO。
     */
    private fun refreshLastResult() {
        viewModelScope.launch {
            val summaries = runCatching { triggerLogger.getLogSummaries() }.getOrDefault(emptyList())
            val latest = summaries.maxByOrNull { it.header.actualTimeMs }
            _state.update {
                it.copy(
                    lastResult = latest?.let { s -> s.footer?.message ?: s.header.strategyName }
                        .orEmpty(),
                    lastResultSuccess = latest?.footer?.result == ExecutionResult.STARTED,
                )
            }
        }
    }

    companion object {
        private val TIME_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm")

        /**
         * 把时间戳格式化成"还有多久"的可读文案。
         *
         * 展示策略：当天内显示时分+"还有多久"，跨天补上日期。
         * 避免"还有 20 小时"这种需要用户自己换算的描述。
         */
        fun formatCountdown(targetMs: Long, nowMs: Long = System.currentTimeMillis()): String {
            val remain = targetMs - nowMs
            if (remain <= 0) return "即将触发"

            val target = Instant.ofEpochMilli(targetMs).atZone(ZoneId.systemDefault())
            val now = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault())

            return when (target.toLocalDate()) {
                now.toLocalDate() -> {
                    val h = remain / 3_600_000
                    val m = (remain % 3_600_000) / 60_000
                    val at = target.format(TIME_FORMATTER)
                    if (h > 0) "今天 $at（${h}小时${m}分后）" else "今天 $at（${m}分后）"
                }

                now.toLocalDate().plusDays(1) -> "明天 ${target.format(TIME_FORMATTER)}"
                else -> target.format(TIME_FORMATTER)
            }
        }
    }
}
