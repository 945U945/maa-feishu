package com.feishu.checkin.ui.screen.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.feishu.checkin.accessibility.CheckInAccessibilityService
import com.feishu.checkin.checkin.alarm.AlarmScheduler
import com.feishu.checkin.checkin.data.CheckInPlanRepository
import com.feishu.checkin.checkin.data.SettingsRepository
import com.feishu.checkin.checkin.model.CheckInPlan
import com.feishu.checkin.core.device.HealthStatus
import com.feishu.checkin.core.device.PermissionChecker
import com.feishu.checkin.core.time.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 设置页状态。
 */
data class SettingsUiState(
    val settings: AppSettings = AppSettings.DEFAULT,
    val plans: List<CheckInPlan> = emptyList(),
    val health: List<HealthStatus> = emptyList(),
    val appVersion: String = "",
)

/**
 * 设置页 ViewModel。
 */
class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val planRepository: CheckInPlanRepository,
    private val permissionChecker: PermissionChecker,
    private val alarmScheduler: AlarmScheduler,
    private val appContext: Context,
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.settings,
        planRepository.plans,
    ) { settings, plans ->
        SettingsUiState(
            settings = settings,
            plans = plans,
            health = permissionChecker.checkAll(),
            appVersion = appVersionName(),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = SettingsUiState(),
    )

    /** 切换方案 */
    fun setPlan(planId: String) {
        viewModelScope.launch {
            settingsRepository.setPlanId(planId)
            Timber.i("已切换方案: %s", planId)
            // 方案切换不影响触发时刻，不需要重排闹钟
        }
    }

    /** 失败重试次数 */
    fun setRetryCount(count: Int) {
        viewModelScope.launch { settingsRepository.setRetryCount(count) }
    }

    /** 单步超时 */
    fun setStepTimeoutSeconds(seconds: Int) {
        viewModelScope.launch { settingsRepository.setStepTimeoutMs(seconds * 1000L) }
    }

    /** 详细日志开关 */
    fun setVerboseLog(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setVerboseLog(enabled) }
    }

    /** 重新扫描自定义方案文件 */
    fun reloadPlans() {
        viewModelScope.launch {
            runCatching { planRepository.refresh() }
                .onFailure { Timber.w(it, "重新加载方案失败") }
        }
    }

    /** 手动重排闹钟（排障用） */
    fun rescheduleAlarm() {
        viewModelScope.launch {
            runCatching {
                val settings = settingsRepository.current()
                val next = alarmScheduler.scheduleNext(settings)
                Timber.i("手动重排闹钟: %s", next ?: "未排定")
            }.onFailure { Timber.w(it, "手动重排闹钟失败") }
        }
    }

    private fun appVersionName(): String = runCatching {
        appContext.packageManager
            .getPackageInfo(appContext.packageName, 0)
            .versionName
            .orEmpty()
    }.getOrDefault("")
}

/**
 * 控件探针 ViewModel。
 *
 * 这个页面是「方案可维护性」的关键：飞书改版后用户可以用它
 * 导出新界面的控件结构，据此更新方案 JSON，**不必等应用发版**。
 */
class ProbeViewModel : ViewModel() {

    private val _tree = MutableStateFlow("")
    val tree: StateFlow<String> = _tree.asStateFlow()

    private val _capturing = MutableStateFlow(false)
    val capturing: StateFlow<Boolean> = _capturing.asStateFlow()

    private val _inFeishu = MutableStateFlow(false)
    val inFeishu: StateFlow<Boolean> = _inFeishu.asStateFlow()

    /**
     * 采集当前界面。
     *
     * 必须在 IO 线程做：控件树可能有上千个节点，
     * 在主线程遍历会掉帧甚至 ANR。
     */
    fun capture() {
        if (_capturing.value) return
        _capturing.value = true

        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val service = CheckInAccessibilityService.instance
                    if (service == null) {
                        return@withContext CaptureResult(ok = false, tree = "", inFeishu = false)
                    }
                    val pkg = service.currentPackage.value
                    val isFeishu = pkg == com.feishu.checkin.BuildConfig.FEISHU_PACKAGE ||
                        pkg == com.feishu.checkin.BuildConfig.FEISHU_PACKAGE_LITE
                    CaptureResult(
                        ok = true,
                        tree = service.dumpCurrentTree(),
                        inFeishu = isFeishu,
                    )
                }
                _inFeishu.value = result.inFeishu
                _tree.value = result.tree
            } catch (t: Throwable) {
                Timber.e(t, "采集控件树失败")
                _tree.value = ""
            } finally {
                _capturing.value = false
            }
        }
    }

    fun clear() {
        _tree.value = ""
        _inFeishu.value = false
    }

    private data class CaptureResult(val ok: Boolean, val tree: String, val inFeishu: Boolean)
}
