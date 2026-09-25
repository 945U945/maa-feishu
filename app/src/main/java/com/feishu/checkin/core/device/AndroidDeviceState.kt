package com.feishu.checkin.core.device

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import timber.log.Timber

/**
 * [DeviceStateProvider] 的 Android 实现。
 *
 * 只做纯粹的「查一下系统状态」，不含任何决策逻辑 ——
 * 决策留给不依赖 Android 的执行引擎。
 */
class AndroidDeviceStateProvider(
    private val context: Context,
    private val feishuPackages: List<String>,
) : DeviceStateProvider {

    private val keyguardManager: KeyguardManager? by lazy {
        context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    }

    private val powerManager: PowerManager? by lazy {
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    }

    override fun isKeyguardLocked(): Boolean =
        runCatching { keyguardManager?.isKeyguardLocked == true }.getOrDefault(false)

    override fun isScreenInteractive(): Boolean =
        runCatching { powerManager?.isInteractive == true }.getOrDefault(false)

    override fun feishuPackageName(): String? =
        feishuPackages.firstOrNull { isPackageInstalled(it) }

    override fun isFeishuInstalled(): Boolean = feishuPackageName() != null

    /**
     * 检查包是否已安装。
     *
     * 优先用 `getLaunchIntentForPackage` 而不是 `getPackageInfo`：
     * 前者查的是「能否被启动」，这正是我们真正关心的能力；
     * 后者可能因 Android 11+ 的包可见性限制而抛 NameNotFoundException，
     * 即便应用确实装着 —— 那种情况下我们会误报「未安装」，
     * 引导用户去装一个已经装好的应用，体验很糟。
     */
    private fun isPackageInstalled(pkg: String): Boolean =
        runCatching { context.packageManager.getLaunchIntentForPackage(pkg) != null }
            .onFailure { Timber.d(it, "查询包可见性失败: %s", pkg) }
            .getOrDefault(false)
}

/**
 * [AwakeController] 的 Android 实现。
 *
 * ## 唤醒策略
 *
 * 用 `PowerManager.newWakeLock(ACQUIRE_CAUSES_WAKEUP)` 而非已废弃的
 * `PowerManager.wakeUp()`：后者需要 `DEVICE_POWER` 权限，
 * 那是系统级权限，第三方应用申请不到。
 *
 * 使用要点（踩过的坑都写在这）：
 * - flag 必须同时给 `ACQUIRE_CAUSES_WAKEUP`（点亮屏幕）和
 *   `ON_AFTER_RELEASE`（释放后保持亮屏一小会儿，否则刚亮就灭，
 *   无障碍服务来不及找到控件）
 * - 超时**必须**设置。曾经因为没设超时，异常路径下锁泄漏，
 *   表现为「打完卡后屏幕再也不灭」，非常耗电且用户无法理解
 */
class AndroidAwakeController(
    context: Context,
    private val stateProvider: DeviceStateProvider,
) : AwakeController {

    private val powerManager: PowerManager? =
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private val keyguardManager: KeyguardManager? =
        context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager

    override fun wakeUp(): Boolean {
        if (stateProvider.isScreenInteractive()) return true

        val pm = powerManager ?: return false
        return runCatching {
            @Suppress("DEPRECATION")
            val lock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                WAKE_LOCK_TAG,
            )
            // 5 秒足够走完打卡，同时避免异常时锁泄漏
            lock.acquire(WAKE_LOCK_TIMEOUT_MS)
            Timber.i("已请求唤醒屏幕")
            stateProvider.isScreenInteractive()
        }.onFailure { Timber.w(it, "唤醒屏幕失败") }
            .getOrDefault(false)
    }

    override fun dismissKeyguard(): Boolean {
        if (!stateProvider.isKeyguardLocked()) return true

        val km = keyguardManager ?: return false
        return runCatching {
            // requestDismissKeyguard(Activity, Callback) 的第一个参数
            // 是**非空** Activity —— 传 null 编译不过。
            //
            // 但我们确实拿不到 Activity：打卡由广播/前台服务触发，
            // 进程里可能根本没有 Activity 实例。
            //
            // 所以这里改用 isKeyguardSecure 判断：
            // - 非安全锁屏（无密码/仅滑动）→ 亮屏即已解锁，直接算成功
            // - 安全锁屏（有密码/图案）→ 系统必须有用户交互才能解锁，
            //   第三方应用无解，如实返回 false 让上层走 DEVICE_LOCKED 分支
            //
            // 之前用 requestDismissKeyguard 是在假装能解锁 ——
            // 即便调用成功，有密码的设备仍然停在认证界面，
            // 反而让上层误判「解锁成功」，白跑一趟打卡流程。
            val secure = km.isKeyguardSecure
            Timber.i("锁屏状态: secure=%s，%s", secure, if (secure) "需用户认证" else "可直接进入")
            !secure
        }.onFailure { Timber.w(it, "解除锁屏失败") }
            .getOrDefault(false)
    }

    override fun ensureAwakeAndUnlocked(): Boolean {
        if (!stateProvider.isScreenInteractive()) {
            wakeUp()
            // 屏幕从灭到亮有系统动画延迟，短暂等待后再谈解锁
            sleepQuietly(WAKE_SETTLE_MS)
        }

        if (stateProvider.isKeyguardLocked()) {
            dismissKeyguard()
            sleepQuietly(KEYGUARD_SETTLE_MS)
        }

        val ok = stateProvider.isScreenInteractive() && !stateProvider.isKeyguardLocked()
        if (!ok) {
            Timber.w(
                "设备仍不可用: screenInteractive=%s, keyguardLocked=%s",
                stateProvider.isScreenInteractive(),
                stateProvider.isKeyguardLocked(),
            )
        }
        return ok
    }

    private fun sleepQuietly(ms: Long) {
        runCatching { Thread.sleep(ms) }
    }

    private companion object {
        const val WAKE_LOCK_TAG = "FeishuCheckIn:awake"
        const val WAKE_LOCK_TIMEOUT_MS = 5_000L

        /** 亮屏后等待系统动画与窗口就绪 */
        const val WAKE_SETTLE_MS = 600L

        /** 解锁后等待锁屏界面完全退出 */
        const val KEYGUARD_SETTLE_MS = 800L
    }
}
