package com.aliothmoon.maameow.remote

import android.content.Intent
import android.os.Process
import android.system.Os
import com.aliothmoon.maameow.ITouchEventCallback
import com.aliothmoon.maameow.RemoteService
import com.aliothmoon.maameow.maa.InputControlUtils
import com.aliothmoon.maameow.remote.internal.ActivityUtils
import com.aliothmoon.maameow.remote.internal.GestureRecorder
import com.aliothmoon.maameow.remote.internal.PermissionGrantHelper
import com.aliothmoon.maameow.remote.internal.RemoteUtils
import com.aliothmoon.maameow.remote.internal.WakeUnlockController
import com.aliothmoon.maameow.remote.internal.XmsfFirewall
import com.aliothmoon.maameow.third.FakeContext
import com.aliothmoon.maameow.third.Ln
import com.aliothmoon.maameow.third.Workarounds
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

/**
 * 提权进程（Shizuku / Root）侧的 RemoteService 实现。
 *
 * 飞书打卡版只服务「定时唤醒 → 自动解锁 → 拉起飞书 → 无障碍打卡」这一条链路，
 * 因此这里只保留：
 * - 解锁 / 锁屏 / 手势录制（WakeUnlockController、GestureRecorder）
 * - 触控注入（InputControlUtils，解锁手势回放用）
 * - 权限放行（PermissionGrantHelper）
 * - 拉起目标 Activity、查询包是否安装、心跳保活
 *
 * MaaCore、虚拟显示屏、帧缓冲抓取、音频静音、帧率监控等游戏侧能力已全部移除。
 */
class RemoteServiceImpl : RemoteService.Stub() {

    companion object {
        private const val TAG = "RemoteService"
        private const val HEARTBEAT_INTERVAL_MS = 5_000L
    }

    private val appPid = AtomicInteger(0)
    private val destroyed = AtomicBoolean(false)

    /** 同一进程内 setup 幂等：成功后再调直接返回 OK，失败则下次重试 */
    private var setup = false

    init {
        RemoteBootTrace.mark("CTOR_START")
        Runtime.getRuntime().addShutdownHook(
            Thread({ runCatching { performEmergencyCleanup() } }).apply {
                name = "remote-shutdown-hook"
            }
        )
        // ctor 必须轻量：重活放 setup()，attach 前零阻塞
        // Root 下放开 umask，本进程写的文件对 shell 可读写（切回 Shizuku 后还能追加）
        if (Process.myUid() != Process.SHELL_UID) runCatching { Os.umask(0) }
        Workarounds.apply()
        startHeartbeatWatchdog()
        Ln.i("$TAG: RemoteServiceImpl created (lightweight ctor)")
        RemoteBootTrace.mark("CTOR_DONE")
    }

    private fun performEmergencyCleanup() {
        Ln.i("$TAG: performEmergencyCleanup triggered")
        runCatching {
            InputControlUtils.cancel(0)
        }.onFailure {
            Ln.e("$TAG: Emergency cleanup failed: ${it.message}")
        }
    }

    override fun destroy() {
        if (!destroyed.compareAndSet(false, true)) return
        Ln.i("$TAG: destroy()")
        runCatching { InputControlUtils.setTouchCallback(null) }
        performEmergencyCleanup()
        exitProcess(0)
    }

    override fun exit() = destroy()

    override fun version(): String = """
        ==== Build Info ====
        Mode: Feishu Check-in remote
        UID: ${Process.myUid()}
        =====================
    """.trimIndent()

    override fun pid(): Int = Process.myPid()

    override fun setup(userDir: String?, isDebug: Boolean): Int {
        if (setup) return SetupResult.OK
        RemoteBootTrace.mark("SETUP_BEGIN")
        // 打卡版没有 MaaCore，不需要用户目录；保留入参只为兼容既有调用方。
        RemoteBootTrace.mark("SETUP_USER_DIR_INACCESSIBLE", "")
        PermissionGrantHelper.disablePhantomProcessKiller()
        setup = true
        RemoteBootTrace.mark("SETUP_DONE")
        return SetupResult.OK
    }

    override fun test(map: MutableMap<String, String>) {
        // 连通性探测，无需实现
    }

    override fun grantPermissions(request: PermissionGrantRequest): PermissionStateInfo {
        val packageName = request.packageName
        val uid = if (request.uid > 0) request.uid
        else RemoteUtils.getAppUid(packageName).takeIf { it > 0 } ?: request.uid
        val p = request.permissions

        with(PermissionGrantHelper) {
            // 结果不进 PermissionStateInfo 免改 AIDL；放开失败由 App 侧预检兜底
            if (p and PermissionGrantRequest.PERM_FGS_SPECIAL_USE != 0) {
                grantForegroundServiceSpecialUse(packageName)
            }
            return PermissionStateInfo(
                accessibilityPermission = if (p and PermissionGrantRequest.PERM_ACCESSIBILITY != 0) {
                    grantAccessibilityService(request.accessibilityServiceId)
                } else false,
                floatingWindowPermission = if (p and PermissionGrantRequest.PERM_FLOATING_WINDOW != 0) {
                    grantFloatingWindowPermission(packageName, uid)
                } else false,
                notificationPermission = if (p and PermissionGrantRequest.PERM_NOTIFICATION != 0) {
                    grantNotificationPermission(packageName, uid)
                } else false,
                batteryOptimizationExempt = if (p and PermissionGrantRequest.PERM_BATTERY != 0) {
                    grantBatteryOptimizationExemption(packageName)
                } else false,
                storagePermission = if (p and PermissionGrantRequest.PERM_STORAGE != 0) {
                    grantStoragePermission(packageName, uid)
                } else false,
                backgroundUnrestricted = if (p and PermissionGrantRequest.PERM_BACKGROUND != 0) {
                    grantBackgroundUnrestricted(packageName, uid)
                } else false,
            )
        }
    }

    // ---- 触控注入（解锁手势回放） ----

    override fun setTouchCallback(callback: ITouchEventCallback?) {
        InputControlUtils.setTouchCallback(callback)
    }

    override fun touchDown(x: Int, y: Int, contact: Int) {
        InputControlUtils.down(x, y, contact, 0)
    }

    override fun touchMove(x: Int, y: Int, contact: Int) {
        InputControlUtils.move(x, y, contact, 0)
    }

    override fun touchUp(x: Int, y: Int, contact: Int) {
        InputControlUtils.up(x, y, contact, 0)
    }

    override fun touchCancel() {
        InputControlUtils.cancel(0)
    }

    // ---- 唤醒 / 解锁 / 锁屏 ----

    /** @return [com.aliothmoon.maameow.constant.WakeUnlockResult] */
    override fun unlock(credential: String?): Int =
        WakeUnlockController.unlock(credential.orEmpty())

    /** @return [com.aliothmoon.maameow.constant.WakeUnlockResult] */
    override fun lockAndSleep(): Int = WakeUnlockController.lockAndSleep()

    /** @return [com.aliothmoon.maameow.constant.WakeUnlockResult] */
    override fun testUnlock(credential: String?): Int =
        WakeUnlockController.testUnlock(credential.orEmpty())

    /** @return [com.aliothmoon.maameow.constant.WakeUnlockResult] */
    override fun unlockWithGesture(gestureJson: String?): Int =
        WakeUnlockController.unlockWithGesture(gestureJson.orEmpty())

    /** @return [com.aliothmoon.maameow.constant.WakeUnlockResult] */
    override fun testUnlockGesture(gestureJson: String?): Int =
        WakeUnlockController.testUnlockGesture(gestureJson.orEmpty())

    override fun startGestureRecord(timeoutMs: Int) = GestureRecorder.start(timeoutMs)

    /** @return [com.aliothmoon.maameow.domain.models.GestureRecordResult] 的 JSON */
    override fun pollGestureRecord(): String = GestureRecorder.poll()

    override fun cancelGestureRecord() = GestureRecorder.cancel()

    // ---- 应用与进程 ----

    override fun isPackageInstalled(packageName: String): Boolean = try {
        FakeContext.get().packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: Exception) {
        Ln.w("$TAG: isPackageInstalled: $packageName not found", e)
        false
    }

    override fun startActivity(intent: Intent): Boolean = ActivityUtils.startActivity(intent)

    override fun isAppAlive(packageName: String): Int = try {        val process = Runtime.getRuntime().exec(arrayOf("pidof", packageName))
        val exitCode = process.waitFor()
        val output = process.inputStream.bufferedReader().readText().trim()
        val errorOutput = process.errorStream.bufferedReader().readText().trim()
        when {
            exitCode == 0 && output.isNotEmpty() -> AppAliveStatus.ALIVE
            exitCode == 1 && output.isEmpty() && errorOutput.isEmpty() -> AppAliveStatus.DEAD
            else -> {
                Ln.w("$TAG: isAppAlive unexpected for $packageName: exit=$exitCode stdout=$output stderr=$errorOutput")
                AppAliveStatus.UNKNOWN
            }
        }
    } catch (e: Exception) {
        Ln.w("isAppAlive check failed for $packageName", e)
        AppAliveStatus.UNKNOWN
    }

    override fun heartbeat(pid: Int) {
        appPid.set(pid)
    }

    override fun setPackageNetworkingEnabled(packageName: String?, enabled: Boolean): Boolean {
        if (packageName.isNullOrBlank()) return false
        return XmsfFirewall.setNetworkingEnabled(packageName, enabled)
    }

    private fun startHeartbeatWatchdog() {
        Thread {
            while (!destroyed.get()) {
                try {
                    Thread.sleep(HEARTBEAT_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                val pid = appPid.get()
                if (pid <= 0) continue
                if (!File("/proc/$pid").exists()) {
                    Ln.w("$TAG: app process (pid=$pid) gone, destroying remote service")
                    destroy()
                    return@Thread
                }
            }
        }.apply {
            name = "remote-heartbeat-watchdog"
            isDaemon = true
        }.start()
    }
}
