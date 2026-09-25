package com.feishu.checkin.checkin.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.feishu.checkin.core.time.AppSettings
import com.feishu.checkin.checkin.model.CheckInKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.time.LocalTime
import java.time.ZoneId

/**
 * DataStore 实例。
 *
 * 用扩展属性而不是在类里 `DataStoreFactory.create`：
 * DataStore 的约定是「同名的实例在进程内必须唯一」，
 * 否则会抛 `IllegalStateException: There are multiple DataStores active`。
 * 用顶层扩展属性可以天然保证唯一。
 */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "feishu_checkin_settings",
)

/**
 * 设置读写。
 *
 * ## 为什么对外只暴露一个 StateFlow
 *
 * 如果暴露 `Flow<AppSettings>`（每次收集都重新读盘），
 * 首页、设置页、调度层各自订阅时会拿到**不同时刻的快照**，
 * 表现为「设置页刚改完，首页还显示旧值」这类不一致。
 *
 * 因此这里用 `stateIn` 把它变成**进程内唯一的热流**：
 * 第一次订阅时读一次盘，之后所有订阅者共享同一个已更新的值。
 *
 * 另外提供了一个同步的 [current] 快照入口 —— 给闹钟接收器这类
 * 没有协程环境、必须同步拿值的场景用（`goAsync` 里也能用，但要小心超时）。
 */
class SettingsRepository(
    private val context: Context,
    scope: CoroutineScope,
) {

    /** 进程内共享的设置流，带默认值兜底 */
    val settings: Flow<AppSettings> = context.settingsDataStore.data
        .map { prefs -> prefs.toSettings() }
        .stateIn(
            scope = scope,
            // 5 秒的停止超时：进程内多处订阅，短暂无订阅者时不必重读盘
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = AppSettings.DEFAULT,
        )

    /**
     * 同步读取当前设置快照。
     *
     * 仅供闹钟接收器等无法挂起的环境使用。
     * 注意首次调用会读盘，**不要在 UI 线程调用**。
     */
    suspend fun current(): AppSettings = settings.first()

    /** 更新总开关 */
    suspend fun setEnabled(enabled: Boolean) = edit { it[Keys.ENABLED] = enabled }

    /** 更新某类打卡的时刻 */
    suspend fun setTime(kind: CheckInKind, time: LocalTime) = edit {
        val key = when (kind) {
            CheckInKind.CLOCK_IN -> Keys.CLOCK_IN
            CheckInKind.CLOCK_OUT -> Keys.CLOCK_OUT
        }
        it[key] = time.formatAsMinuteOfDay()
    }

    /** 启用/停用某类打卡 */
    suspend fun setKindEnabled(kind: CheckInKind, enabled: Boolean) = edit {
        val key = when (kind) {
            CheckInKind.CLOCK_IN -> Keys.CLOCK_IN_ENABLED
            CheckInKind.CLOCK_OUT -> Keys.CLOCK_OUT_ENABLED
        }
        it[key] = enabled
    }

    /** 是否只在工作日打卡 */
    suspend fun setWeekdaysOnly(value: Boolean) = edit { it[Keys.WEEKDAYS_ONLY] = value }

    /** 切换方案 */
    suspend fun setPlanId(planId: String) = edit { it[Keys.PLAN_ID] = planId }

    /** 失败重试次数 */
    suspend fun setRetryCount(count: Int) = edit {
        it[Keys.RETRY_COUNT] = count.coerceIn(0, 5)
    }

    /** 单步超时 */
    suspend fun setStepTimeoutMs(ms: Long) = edit {
        it[Keys.STEP_TIMEOUT_MS] = ms.coerceIn(2_000L, 60_000L)
    }

    /** 详细日志开关 */
    suspend fun setVerboseLog(enabled: Boolean) = edit { it[Keys.VERBOSE_LOG] = enabled }

    /**
     * 记录当前时区。
     *
     * 由调度层在每次重排闹钟后写入。下次启动时若发现系统时区与记录不同，
     * 说明用户出差异地或改过时区，需要重排 —— 否则闹钟会在错误的时刻触发。
     */
    suspend fun setKnownZoneId(zoneId: String) = edit { it[Keys.KNOWN_ZONE] = zoneId }

    /**
     * 保存打卡入口深链。
     *
     * 这里**不做格式校验就写入** —— 校验放在 UI 层做并给出提示。
     * 原因：用户可能粘贴到的是自己企业的定制域名，格式超出我们的
     * 白名单，但实际可用。在这里硬拦会让合法链接存不进去。
     * UI 提示"这个链接看起来不太对"是建议，写入是用户的决定。
     */
    suspend fun setEntryUrl(url: String) = edit { it[Keys.ENTRY_URL] = url.trim() }

    /** 深链跳转开关 */
    suspend fun setDeepLinkEnabled(enabled: Boolean) = edit {
        it[Keys.DEEP_LINK_ENABLED] = enabled
    }

    /** 桌面快捷方式尝试开关 */
    suspend fun setShortcutEnabled(enabled: Boolean) = edit {
        it[Keys.SHORTCUT_ENABLED] = enabled
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        runCatching { context.settingsDataStore.edit(block) }
            .onFailure { Timber.e(it, "写入设置失败") }
    }

    private object Keys {
        val ENABLED = booleanPreferencesKey("enabled")
        val CLOCK_IN = stringPreferencesKey("clock_in_time")
        val CLOCK_OUT = stringPreferencesKey("clock_out_time")
        val CLOCK_IN_ENABLED = booleanPreferencesKey("clock_in_enabled")
        val CLOCK_OUT_ENABLED = booleanPreferencesKey("clock_out_enabled")
        val WEEKDAYS_ONLY = booleanPreferencesKey("weekdays_only")
        val PLAN_ID = stringPreferencesKey("plan_id")
        val RETRY_COUNT = intPreferencesKey("retry_count")
        val STEP_TIMEOUT_MS = longPreferencesKey("step_timeout_ms")
        val VERBOSE_LOG = booleanPreferencesKey("verbose_log")
        val KNOWN_ZONE = stringPreferencesKey("known_zone")
        val ENTRY_URL = stringPreferencesKey("entry_url")
        val DEEP_LINK_ENABLED = booleanPreferencesKey("deep_link_enabled")
        val SHORTCUT_ENABLED = booleanPreferencesKey("shortcut_enabled")
    }

    private companion object {
        /**
         * 时刻的存取格式：`HH:mm`。
         *
         * 不用 epoch 或分钟数，是因为用户改时区时字符串形式不需要做任何换算 ——
         * 「9:00 上班」在任何时区都应该是 9:00，这正是用户的心理模型。
         */
        fun LocalTime.formatAsMinuteOfDay(): String = "%02d:%02d".format(hour, minute)

        fun parseTime(raw: String?, fallback: LocalTime): LocalTime {
            if (raw.isNullOrBlank()) return fallback
            return runCatching {
                val parts = raw.split(':')
                LocalTime.of(parts[0].toInt(), parts[1].toInt())
            }.getOrDefault(fallback)
        }

        fun Preferences.toSettings(): AppSettings = AppSettings(
            enabled = this[Keys.ENABLED] ?: AppSettings.DEFAULT.enabled,
            clockInTime = parseTime(this[Keys.CLOCK_IN], AppSettings.DEFAULT_CLOCK_IN),
            clockOutTime = parseTime(this[Keys.CLOCK_OUT], AppSettings.DEFAULT_CLOCK_OUT),
            clockInEnabled = this[Keys.CLOCK_IN_ENABLED] ?: AppSettings.DEFAULT.clockInEnabled,
            clockOutEnabled = this[Keys.CLOCK_OUT_ENABLED] ?: AppSettings.DEFAULT.clockOutEnabled,
            weekdaysOnly = this[Keys.WEEKDAYS_ONLY] ?: AppSettings.DEFAULT.weekdaysOnly,
            planId = this[Keys.PLAN_ID] ?: AppSettings.DEFAULT_PLAN_ID,
            retryCount = this[Keys.RETRY_COUNT] ?: AppSettings.DEFAULT_RETRY,
            stepTimeoutMs = this[Keys.STEP_TIMEOUT_MS] ?: AppSettings.DEFAULT.stepTimeoutMs,
            verboseLog = this[Keys.VERBOSE_LOG] ?: AppSettings.DEFAULT.verboseLog,
            knownZoneId = this[Keys.KNOWN_ZONE] ?: ZoneId.systemDefault().id,
            entryUrl = this[Keys.ENTRY_URL] ?: AppSettings.DEFAULT.entryUrl,
            deepLinkEnabled = this[Keys.DEEP_LINK_ENABLED]
                ?: AppSettings.DEFAULT.deepLinkEnabled,
            shortcutEnabled = this[Keys.SHORTCUT_ENABLED]
                ?: AppSettings.DEFAULT.shortcutEnabled,
        )
    }
}
