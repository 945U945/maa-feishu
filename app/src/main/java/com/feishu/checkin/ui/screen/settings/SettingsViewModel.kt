package com.feishu.checkin.ui.screen.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.feishu.checkin.accessibility.CheckInAccessibilityService
import com.feishu.checkin.checkin.alarm.AlarmScheduler
import com.feishu.checkin.checkin.data.CheckInPlanRepository
import com.feishu.checkin.checkin.data.SettingsRepository
import com.feishu.checkin.checkin.model.CheckInPlan
import com.feishu.checkin.checkin.model.EntryFormats
import com.feishu.checkin.checkin.model.EntryStrategy
import com.feishu.checkin.core.device.HealthStatus
import com.feishu.checkin.core.device.PermissionChecker
import com.feishu.checkin.core.shizuku.ShizukuSupport
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
    /**
     * Shizuku 运行时描述。
     *
     * 空串表示不可用。放在 state 里而不是让 UI 自己去查，
     * 是因为查询要走 binder（有开销），且结果在同一屏里不需要变化 ——
     * 归入 state 后 UI 层完全不必知道 Shizuku 的存在。
     */
    val shizukuStatus: String = "",
    /**
     * 当前方案的入口策略。
     *
     * 从 `plans` 里当前选中的那份取出来，而不是单独存一个设置项 ——
     * 策略本就属于方案（见 [CheckInViewModel.setEntryStrategy] 的注释）。
     */
    val entryStrategy: EntryStrategy = EntryStrategy.AUTO,
)

/**
 * 入口链接的校验结果。
 *
 * 单独做成类型而不是返回 Boolean，因为「填错了」需要**告诉用户错在哪** ——
 * 链接这东西最容易犯的错是「复制成了飞书分享链接」或
 * 「复制成了企业自定义域名的网页链接」，两者的提示完全不同。
 */
sealed interface EntryUrlCheck {
    /** 空 —— 未配置，合法状态（会退回点击导航） */
    data object Empty : EntryUrlCheck

    /** 格式合法 */
    data object Valid : EntryUrlCheck

    /** 格式不合法，附原因 */
    data class Invalid(val reason: String) : EntryUrlCheck
}

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
            shizukuStatus = shizukuStatus(),
            entryStrategy = plans.firstOrNull { it.id == settings.planId }?.entryStrategy
                ?: EntryStrategy.AUTO,
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

    // ────────────────────── 打卡入口链接 ──────────────────────

    /**
     * 保存用户配置的打卡入口链接。
     *
     * ## 为什么先校验再保存
     *
     * 把明显不合法的链接存进去，用户会在**打卡失败之后**才发现 ——
     * 而那时错误发生在自动化流程里，用户看到的只是
     * 「入口链接打不开」，根本不知道自己什么时候填错了。
     *
     * 在这里拦下来，用户立刻知道「这条链接不对」。
     * 注意校验是**宽松**的（只认协议头与宿主，不校验路径），
     * 因为不同企业的 AppLink 路径差异很大，严校验会把合法链接挡在门外。
     *
     * @return 校验结果，供 UI 提示
     */
    fun setEntryUrl(url: String): EntryUrlCheck {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            // 空是合法状态：表示「不用深链，退回点击导航」
            viewModelScope.launch { settingsRepository.setEntryUrl("") }
            return EntryUrlCheck.Empty
        }

        if (!EntryFormats.looksLikeFeishuLink(trimmed)) {
            return EntryUrlCheck.Invalid(
                "这看起来不是飞书应用链接。请确认复制的是「考勤打卡」应用的链接" +
                    "（长按应用 → 分享 → 复制链接）",
            )
        }

        return EntryUrlCheck.Valid.also {
            viewModelScope.launch {
                settingsRepository.setEntryUrl(trimmed)
                Timber.i("已保存打卡入口链接（长度 %d）", trimmed.length)
            }
        }
    }

    /**
     * 从剪贴板读链接。
     *
     * 这是**主要输入方式** —— 用户实际是「在飞书复制 → 切回本应用」，
     * 手动长按粘贴比点一下「读取剪贴板」麻烦得多。
     *
     * 注意 Android 10+ 对后台应用读剪贴板有限制，但本方法
     * 由前台界面按钮触发，此时应用在前台，读取是允许的。
     */
    fun pasteEntryUrlFromClipboard(): String? = runCatching {
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE)
            as? android.content.ClipboardManager ?: return null
        clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(appContext)
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }.onFailure { Timber.w(it, "读取剪贴板失败") }.getOrNull()

    /** 深链总开关 */
    fun setDeepLinkEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setDeepLinkEnabled(enabled) }
    }

    /** 桌面快捷方式开关 */
    fun setShortcutEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setShortcutEnabled(enabled) }
    }

    /**
     * 入口策略。
     *
     * 写入当前选中的方案。
     *
     * ## 为什么是「改方案」而不是「改全局设置」
     *
     * [CheckInPlan.entryStrategy] 属于方案（不同方案可以配不同策略，
     * 比如「搜索版」方案天然就应该是点击导航）。做成全局设置的话，
     * 切换方案时策略会跟着走 —— 那是错的。
     *
     * 而 UI 上它出现在设置页的「打卡入口」区（而不是方案编辑器里），
     * 是因为绝大多数用户只用内置方案，不必知道"方案"这个抽象。
     * 这层映射（UI 的入口区 → 当前方案的字段）就落在这里。
     */
    fun setEntryStrategy(strategy: EntryStrategy) {
        viewModelScope.launch {
            runCatching { planRepository.updateStrategy(settingsRepository.current().planId, strategy) }
                .onFailure { Timber.w(it, "写入入口策略失败") }
        }
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

    /**
     * Shizuku 运行时描述，不可用时返回空串。
     *
     * 刻意**不区分**「没装 Shizuku」与「装了没启动」的文案差异 ——
     * 对用户来说这两种情况的行动是一样的（都用不了），
     * 分两句话只会增加认知负担。真正需要细节时看日志。
     */
    private fun shizukuStatus(): String = runCatching {
        ShizukuSupport.describeRuntime(appContext.packageManager)
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

    // ────────────────────── Shizuku 结构探测 ──────────────────────

    /**
     * Shizuku 结构探测结果。
     *
     * 与上面的「控件树采集」是**互补**的两件事：
     * - 控件树（无障碍）看的是「界面长什么样」，用来写方案选择器
     * - 结构探测（Shizuku）看的是「飞书有哪些 Activity、注册了哪些
     *   scheme」，用来判断深链该怎么配
     *
     * 用户当初选的「先探测飞书 Activity 结构」指的就是这一项。
     */
    private val _probeReport = MutableStateFlow("")
    val probeReport: StateFlow<String> = _probeReport.asStateFlow()

    private val _probing = MutableStateFlow(false)
    val probing: StateFlow<Boolean> = _probing.asStateFlow()

    /** Shizuku 不可用时的原因说明，供 UI 提示 */
    private val _probeUnavailable = MutableStateFlow<String?>(null)
    val probeUnavailable: StateFlow<String?> = _probeUnavailable.asStateFlow()

    /**
     * 用 Shizuku 探测飞书结构。
     *
     * 输出的是「按报告组织的可读文本」而不是原始 dumpsys ——
     * 原始输出动辄几 MB，用户根本读不了。
     * 需要原始数据时可以导出日志文件（那里保留了全文）。
     */
    fun probeFeishuStructure(context: android.content.Context, packageName: String) {
        if (_probing.value) return
        _probing.value = true
        _probeUnavailable.value = null

        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val pm = context.packageManager
                    if (!ShizukuSupport.isAvailable(pm)) {
                        return@withContext ProbeOutcome(
                            unavailable = if (ShizukuSupport.isInstalled(pm)) {
                                "Shizuku 已安装但未运行，请先在 Shizuku 应用里启动服务"
                            } else {
                                "未检测到 Shizuku。这一项探测是可选的 —— 不装也能用打卡功能"
                            },
                            text = "",
                        )
                    }

                    val probe = runCatching {
                        com.feishu.checkin.core.shizuku.FeishuEntryProbe(
                            com.feishu.checkin.core.shizuku.ShizukuShell(),
                        ).probe(packageName)
                    }.getOrElse { t ->
                        Timber.w(t, "结构探测失败")
                        return@withContext ProbeOutcome(
                            unavailable = "探测失败：${t.message ?: t.javaClass.simpleName}",
                            text = "",
                        )
                    }

                    ProbeOutcome(unavailable = null, text = probe.toReadableText())
                }
                _probeUnavailable.value = result.unavailable
                _probeReport.value = result.text
            } catch (t: Throwable) {
                Timber.e(t, "结构探测异常")
                _probeUnavailable.value = "探测异常：${t.message ?: t.javaClass.simpleName}"
            } finally {
                _probing.value = false
            }
        }
    }

    private data class ProbeOutcome(val unavailable: String?, val text: String)

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

    fun clearProbe() {
        _probeReport.value = ""
        _probeUnavailable.value = null
    }

    private data class CaptureResult(val ok: Boolean, val tree: String, val inFeishu: Boolean)
}
