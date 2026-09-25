package com.feishu.checkin.checkin.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.feishu.checkin.MainActivity
import com.feishu.checkin.R
import com.feishu.checkin.checkin.data.CheckInRecordRepository
import com.feishu.checkin.checkin.engine.CheckInEngine
import com.feishu.checkin.checkin.model.CheckInKind
import com.feishu.checkin.checkin.model.CheckInRequest
import com.feishu.checkin.checkin.model.CheckInResult
import com.feishu.checkin.core.di.entryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 打卡执行的前台服务。
 *
 * ## 为什么必须是前台服务
 *
 * 打卡要在无人操作的情况下走完十几个步骤，中间要拉起飞书、
 * 操作界面、等待确认 —— 整个过程十几秒。后台进程在这个时间段里
 * 随时可能被系统回收，尤其在低内存设备上。
 *
 * 前台服务带一个常驻通知，把进程优先级提到「用户可见」级别，
 * 基本不会被回收。这个通知同时也是一个**有用的 UI**：
 * 用户能看到「正在打卡」，而不是手机自己莫名其妙动起来。
 *
 * ## 为什么用 startForegroundService + startService 两条路径
 *
 * Android 8.0+ 要求后台启动服务必须用 `startForegroundService`，
 * 且必须在 5 秒内调用 `startForeground()`，否则抛 ANR。
 * 但 Android 12+ 又限制了**从后台启动前台服务**的场景
 * （`ForegroundServiceStartNotAllowedException`）。
 *
 * 闹钟触发属于系统允许的例外情况之一，所以正常情况下没问题；
 * 但为保险起见，这里对两种启动失败都做了兜底 ——
 * 拿不到前台服务就退化成「直接在本进程执行」，
 * 虽然可能被回收，但总好过完全不执行。
 */
class CheckInService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 持有一个 wake lock，保证打卡过程中 CPU 不休眠 */
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Timber.i("打卡服务已创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action != ACTION_EXECUTE) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val request = intent.toRequest()
        if (request == null) {
            Timber.w("启动参数缺失，服务退出")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // 先提升为前台服务 —— 必须在 5 秒内完成，放在所有耗时操作之前
        startForegroundCompat(request)

        acquireWakeLock()

        scope.launch {
            try {
                execute(request)
            } catch (t: Throwable) {
                Timber.e(t, "打卡服务执行异常")
            } finally {
                releaseWakeLock()
                // Android 12+ 前台服务不能无限期运行，
                // 任务完成后主动停止，避免被系统判定为滥用
                stopForegroundCompat()
                stopSelf(startId)
            }
        }

        // START_NOT_STICKY：进程意外被杀后不要自动重建 ——
        // 因为重建时 intent 会是 null，无法知道该打什么卡，
        // 强行重建只会产生一次无意义的服务启动
        return START_NOT_STICKY
    }

    private suspend fun execute(request: CheckInRequest) {
        val deps = applicationContext.entryPoint()
        val engine = deps.checkInEngine()
        val recordRepository = deps.recordRepository()

        val result = engine.execute(request)
        Timber.i("打卡完成: %s → %s", request.dedupeKey, result.name)

        notifyResult(result, request.kind)
        runCatching { recordRepository.refresh() }
    }

    // ────────────────────── 通知 ──────────────────────

    private fun startForegroundCompat(request: CheckInRequest) {
        val notification = buildNotification(
            title = getString(R.string.notif_running_title),
            text = kindLabel(request.kind),
            ongoing = true,
        )

        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID_RUNNING,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID_RUNNING, notification)
            }
        }.onFailure {
            // Android 12+ 从后台启动前台服务受限时会抛这个异常。
            // 记录但不崩溃 —— 后面的打卡逻辑继续执行，
            // 只是失去了「不会被回收」的保护
            Timber.w(it, "启动前台服务失败，降级为后台执行")
        }.isSuccess

        if (ok) Timber.i("已进入前台服务状态")
    }

    private fun stopForegroundCompat() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
    }

    /** 打卡结束后发一条结果通知 */
    private fun notifyResult(result: CheckInResult, kind: CheckInKind) {
        val (title, text) = when {
            result == CheckInResult.SUCCESS ->
                getString(R.string.notif_success_title) to kindLabel(kind)

            result == CheckInResult.ALREADY_DONE ->
                getString(R.string.notif_success_title) to getString(R.string.result_already_done)

            result == CheckInResult.SKIPPED_BUSY ->
                getString(R.string.notif_running_title) to getString(R.string.result_skipped_busy)

            // 只有真正需要用户关注的失败才用「失败」标题。
            // ALREADY_DONE 与 SKIPPED_BUSY 走上面两个分支，
            // 不会打扰用户 —— 这是「结果语义分离」在 UI 层的体现
            else ->
                getString(R.string.notif_failed_title) to resultLabel(result)
        }

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching {
            nm.notify(NOTIFICATION_ID_RESULT, buildNotification(title, text, ongoing = false))
        }.onFailure { Timber.w(it, "发送结果通知失败") }
    }

    private fun buildNotification(title: String, text: String, ongoing: Boolean): Notification {
        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(tapIntent)
            .setOngoing(ongoing)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_checkin),
            // LOW 优先级：不响不震。打卡是后台自动行为，
            // 用高优先级会打扰用户（尤其在早上 9 点）
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_checkin_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    // ────────────────────── WakeLock ──────────────────────

    /**
     * 持有 wake lock 保证打卡期间 CPU 不休眠。
     *
     * 必须设超时：曾出现过异常路径下锁泄漏，
     * 表现为「打完卡后手机一直发热」——用户完全无法理解。
     * 这里的超时比打卡最长耗时略长即可。
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        runCatching {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
            Timber.d("已获取 wake lock")
        }.onFailure { Timber.w(it, "获取 wake lock 失败") }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.takeIf { it.isHeld }?.release()
            wakeLock = null
        }.onFailure { Timber.w(it, "释放 wake lock 失败") }
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        Timber.i("打卡服务已销毁")
        super.onDestroy()
    }

    // ────────────────────── 辅助 ──────────────────────

    private fun kindLabel(kind: CheckInKind): String = when (kind) {
        CheckInKind.CLOCK_IN -> getString(R.string.home_kind_clock_in)
        CheckInKind.CLOCK_OUT -> getString(R.string.home_kind_clock_out)
    }

    private fun resultLabel(result: CheckInResult): String = when (result) {
        CheckInResult.SUCCESS -> getString(R.string.result_success)
        CheckInResult.ALREADY_DONE -> getString(R.string.result_already_done)
        CheckInResult.ENTRY_NOT_FOUND -> getString(R.string.result_failed_not_found)
        CheckInResult.NETWORK_ERROR -> getString(R.string.result_failed_network)
        CheckInResult.DEVICE_LOCKED -> getString(R.string.result_failed_locked)
        CheckInResult.PERMISSION_MISSING -> getString(R.string.result_failed_permission)
        CheckInResult.DEEPLINK_LANDING_MISMATCH -> getString(R.string.result_failed_deeplink_landing)
        CheckInResult.DEEPLINK_UNRESOLVED -> getString(R.string.result_failed_deeplink_unresolved)
        CheckInResult.SKIPPED_BUSY -> getString(R.string.result_skipped_busy)
        CheckInResult.FAILURE -> getString(R.string.result_failed_unknown)
    }

    private fun Intent.toRequest(): CheckInRequest? = runCatching {
        val kindName = getStringExtra(EXTRA_KIND) ?: return null
        val kind = CheckInKind.valueOf(kindName)
        CheckInRequest(
            kind = kind,
            planId = getStringExtra(EXTRA_PLAN_ID) ?: com.feishu.checkin.core.time.AppSettings.DEFAULT_PLAN_ID,
            scheduledAt = getLongExtra(EXTRA_SCHEDULED_AT, System.currentTimeMillis()),
            manual = getBooleanExtra(EXTRA_MANUAL, false),
        )
    }.onFailure { Timber.w(it, "解析启动参数失败") }
        .getOrNull()

    companion object {
        private const val CHANNEL_ID = "checkin_status"
        private const val NOTIFICATION_ID_RUNNING = 2001
        private const val NOTIFICATION_ID_RESULT = 2002
        private const val WAKE_LOCK_TAG = "FeishuCheckIn:service"
        private const val WAKE_LOCK_TIMEOUT_MS = 120_000L

        private const val ACTION_EXECUTE = "com.feishu.checkin.action.EXECUTE"

        private const val EXTRA_KIND = "kind"
        private const val EXTRA_PLAN_ID = "plan_id"
        private const val EXTRA_SCHEDULED_AT = "scheduled_at"
        private const val EXTRA_MANUAL = "manual"

        /**
         * 启动服务并传入打卡请求。
         *
         * 用 `startForegroundService` 还是 `startService`：
         * - API 26+ 优先 `startForegroundService`，并捕获
         *   `ForegroundServiceStartNotAllowedException` 后降级
         * - 降级路径用 `startService`，虽然可能在后台被限制启动，
         *   但这是唯一还能尝试的方式
         */
        fun startForCheckIn(context: Context, request: CheckInRequest) {
            val intent = Intent(context, CheckInService::class.java).apply {
                action = ACTION_EXECUTE
                putExtra(EXTRA_KIND, request.kind.name)
                putExtra(EXTRA_PLAN_ID, request.planId)
                putExtra(EXTRA_SCHEDULED_AT, request.scheduledAt)
                putExtra(EXTRA_MANUAL, request.manual)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching { context.startForegroundService(intent) }
                    .onFailure {
                        Timber.w(it, "startForegroundService 被拒，尝试 startService")
                        runCatching { context.startService(intent) }
                            .onFailure { e -> Timber.e(e, "启动打卡服务失败") }
                    }
            } else {
                runCatching { context.startService(intent) }
                    .onFailure { Timber.e(it, "启动打卡服务失败") }
            }
        }
    }
}
