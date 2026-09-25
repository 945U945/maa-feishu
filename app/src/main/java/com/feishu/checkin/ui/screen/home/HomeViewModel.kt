package com.feishu.checkin.ui.screen.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.feishu.checkin.checkin.alarm.AlarmScheduler
import com.feishu.checkin.checkin.data.CheckInRecordRepository
import com.feishu.checkin.checkin.data.SettingsRepository
import com.feishu.checkin.checkin.engine.CheckInEngine
import com.feishu.checkin.checkin.model.CheckInKind
import com.feishu.checkin.checkin.model.CheckInRecord
import com.feishu.checkin.checkin.model.CheckInRequest
import com.feishu.checkin.checkin.model.CheckInResult
import com.feishu.checkin.checkin.service.CheckInService
import com.feishu.checkin.core.device.AppLauncher
import com.feishu.checkin.core.device.HealthStatus
import com.feishu.checkin.core.device.PermissionChecker
import com.feishu.checkin.core.time.AppSettings
import com.feishu.checkin.core.time.NextTriggerCalculator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.LocalTime
import java.time.ZoneId

/**
 * 首页状态。
 *
 * 整体做成一个不可变快照，UI 只需读一个对象 ——
 * 避免「多个 StateFlow 各自更新，界面出现中间态」的问题。
 */
data class HomeUiState(
    val settings: AppSettings = AppSettings.DEFAULT,
    val lastRecord: CheckInRecord? = null,
    val nextTriggerAt: Long? = null,
    val health: List<HealthStatus> = emptyList(),
    val running: Boolean = false,
    val loading: Boolean = true,
)

/**
 * 首页 ViewModel。
 *
 * ## 关于「下次执行」的计算
 *
 * 每次设置变化后重新计算 —— 用 `combine` 把设置流与记录流合并，
 * 而不是在界面里算。原因：这个计算依赖当前时间，
 * 放在 Composable 里会在每次重组时重算，既浪费又可能因为
 * 「重组时机不确定」导致显示不一致。
 */
class HomeViewModel(
    private val settingsRepository: SettingsRepository,
    private val recordRepository: CheckInRecordRepository,
    private val alarmScheduler: AlarmScheduler,
    private val permissionChecker: PermissionChecker,
    private val appLauncher: AppLauncher,
    private val engine: CheckInEngine,
    /**
     * 启动打卡服务需要 Context。
     *
     * 这里传的是 **Application Context**（由 DI 提供 `androidContext()`），
     * 不是 Activity —— ViewModel 生命周期可能长于 Activity，
     * 持 Activity 会造成内存泄漏，这是 ViewModel 里最经典的一类泄漏。
     */
    private val appContext: android.content.Context,
) : ViewModel() {

    private val _manualRunning = MutableStateFlow(false)

    /** 防止连点：连续点击「立即打卡」不应触发多次 */
    private val _manualLock = MutableStateFlow(false)

    val uiState: StateFlow<HomeUiState> = combine(
        settingsRepository.settings,
        recordRepository.recent,
        engine.running,
    ) { settings, records, engineRunning ->
        val next = if (settings.enabled) {
            NextTriggerCalculator.computeNearest(
                nowEpochMillis = System.currentTimeMillis(),
                times = settings.activeTimes,
                zone = settings.zone,
                policy = settings.dayPolicy,
            )?.atEpochMillis
        } else {
            null
        }

        HomeUiState(
            settings = settings,
            // 记录已按文件名倒序排列，第一条就是最近一次
            lastRecord = records.firstOrNull(),
            nextTriggerAt = next,
            health = permissionChecker.checkAll(),
            running = engineRunning || _manualRunning.value,
            loading = false,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = HomeUiState(),
    )

    /** 切换自动打卡总开关 */
    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setEnabled(enabled)
            if (enabled) {
                // 开启后立即排闹钟，而不是等下次冷启动 ——
                // 用户打开开关后会期望「马上生效」，等几分钟才排上会让人怀疑没生效
                val settings = settingsRepository.current()
                val next = alarmScheduler.scheduleNext(settings)
                Timber.i("开启自动打卡，已排闹钟: %s", next ?: "未排定")
            } else {
                alarmScheduler.cancel()
                Timber.i("关闭自动打卡，已取消闹钟")
            }
        }
    }

    /** 修改打卡时刻 */
    fun setTime(kind: CheckInKind, time: LocalTime) {
        viewModelScope.launch {
            settingsRepository.setTime(kind, time)
            // 时刻变了必须重排，否则闹钟还按旧时刻触发 ——
            // 这是用户最容易以为「改了没用」的场景
            refreshAlarm()
        }
    }

    /** 启用/停用某类打卡 */
    fun setKindEnabled(kind: CheckInKind, enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setKindEnabled(kind, enabled)
            refreshAlarm()
        }
    }

    /** 是否只在工作日打卡 */
    fun setWeekdaysOnly(value: Boolean) {
        viewModelScope.launch {
            settingsRepository.setWeekdaysOnly(value)
            refreshAlarm()
        }
    }

    /**
     * 立即打卡。
     *
     * 手动触发与定时触发走**同一个** [CheckInEngine]，
     * 因此共享互斥与幂等保护 —— 手动点的时候如果定时任务正在跑，
     * 会得到「已有任务在执行」而不是两次操作互相打架。
     *
     * 注意这里不直接 await 结果：打卡要十几秒，ViewModel 不应该阻塞。
     * 用前台服务执行，结果通过记录流回传，UI 自然更新。
     */
    fun runNow(kind: CheckInKind) {
        if (_manualLock.value) {
            Timber.d("手动打卡请求已在进行中，忽略重复点击")
            return
        }
        _manualLock.value = true

        viewModelScope.launch {
            try {
                val settings = settingsRepository.current()
                val request = CheckInRequest(
                    kind = kind,
                    planId = settings.planId,
                    scheduledAt = System.currentTimeMillis(),
                    manual = true,
                )
                _manualRunning.value = true
                CheckInService.startForCheckIn(appContext, request)
            } catch (t: Throwable) {
                Timber.e(t, "手动打卡启动失败")
            } finally {
                // 给服务一点启动时间，然后解锁按钮。
                // 不用等到打卡结束 —— 因为服务是独立的，
                // 界面靠 engine.running 反映真实状态即可
                kotlinx.coroutines.delay(1_500L)
                _manualRunning.value = false
                _manualLock.value = false
            }
        }
    }

    /** 重新体检 */
    fun refreshHealth() {
        // uiState 是 combine 出来的，settings 流有订阅时自然会重算。
        // 这里触发一次设置流的重新发射即可
        viewModelScope.launch {
            settingsRepository.setKnownZoneId(ZoneId.systemDefault().id)
        }
    }

    /** 跳转到系统设置页 */
    fun openSettings(action: String) {
        when (action) {
            android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS ->
                appLauncher.openAccessibilitySettings()

            android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS ->
                appLauncher.openBatteryOptimizationSettings()

            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS ->
                appLauncher.openAppDetailsSettings()

            android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM ->
                appLauncher.openExactAlarmSettings()

            else -> appLauncher.openSystemSettings(action)
        }
    }

    /** 切换详细日志 */
    fun setVerboseLog(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setVerboseLog(enabled) }
    }

    private suspend fun refreshAlarm() {
        runCatching {
            val settings = settingsRepository.current()
            if (settings.enabled) {
                alarmScheduler.scheduleNext(settings)
            }
        }.onFailure { Timber.w(it, "重排闹钟失败") }
    }
}
