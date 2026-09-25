package com.feishu.checkin

import android.app.Application
import com.feishu.checkin.checkin.alarm.AlarmScheduler
import com.feishu.checkin.checkin.data.CheckInPlanRepository
import com.feishu.checkin.checkin.data.CheckInRecordRepository
import com.feishu.checkin.checkin.data.SettingsRepository
import com.feishu.checkin.core.di.allModules
import com.feishu.checkin.core.di.startAppKoin
import com.feishu.checkin.core.log.AppPaths
import com.feishu.checkin.core.log.BootTrace
import com.feishu.checkin.core.log.CrashHandler
import com.feishu.checkin.core.log.FileLogTree
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.logger.Level
import org.koin.java.KoinJavaComponent.getKoin
import timber.log.Timber

/**
 * 应用入口。
 *
 * ## 初始化顺序是经过设计的，不要随意调整
 *
 * ```
 * 1. BootTrace.install(this)     ← 零依赖，必须第一句
 * 2. startKoin { ... }            ← 依赖注入
 * 3. CrashHandler.install(this)   ← 崩溃落盘
 * 4. Timber.plant(FileLogTree)    ← 文件日志
 * 5. 冷启动恢复调度                ← 幂等重排闹钟
 * ```
 *
 * ### 为什么 BootTrace 必须第一句（血泪教训）
 *
 * 上一版把崩溃处理器的安装排在日志系统初始化**之后**。
 * 结果依赖注入在初始化阶段抛异常时，崩溃处理器还没挂上，
 * 堆栈既不落盘也进不了 logcat（Release 下系统吞掉了），
 * 用户看到的现象就是「点图标闪一下就退，且查不到任何原因」——
 * 排查花了很久，因为**没有任何证据留下**。
 *
 * [BootTrace] 刻意做到了零依赖（只用 `android.util.Log` 和 `java.io`），
 * 因此它可以、也必须排在最前面。
 *
 * ### 为什么 Koin 要同步启动
 *
 * `BroadcastReceiver`（开机广播、闹钟广播）可能在任何时刻被系统唤起，
 * 那时 `Application.onCreate` 已经执行完毕。如果 Koin 放在协程里异步初始化，
 * 就存在「广播先到、Koin 还没起来」的竞态，表现为
 * `KoinApplicationNotStartedException`。
 *
 * Koin 的启动本身很快（只是构建依赖图，不解析实例），
 * 因此放在主线程同步执行是可以接受的。
 *
 * ### 为什么每一步都包 runCatching
 *
 * 任何一个非关键步骤失败都不应该让应用完全无法启动。
 * 用户至少应该能打开界面看到「哪里出了问题」，
 * 而不是面对一个闪一下就退的图标。
 */
class FeishuCheckInApp : Application() {

    /** 应用级协程作用域，用于冷启动恢复这类「不阻塞启动」的后台任务 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        // ── 第 1 步：零依赖的启动追踪，必须第一句 ──
        BootTrace.install(this)
        BootTrace.step("BootTrace 已安装")

        super.onCreate()

        // ── 第 2 步：依赖注入 ──
        // 用 runCatching 包住但把失败写进日志 ——
        // 如果这一步失败，后面所有 get() 都会失败，
        // 必须在日志里留下明确记录，而不是让用户看到一堆 NullPointerException
        runCatching {
            startAppKoin {
                androidLogger(if (BuildConfig.DEBUG) Level.DEBUG else Level.ERROR)
                // 显式传入 applicationContext，避免 Koin 在
                // 系统组件（Service/Receiver）中拿不到 Context
                androidContext(this@FeishuCheckInApp)
            }
        }.onSuccess {
            BootTrace.step("Koin 启动完成（${allModules.size} 个模块）")
        }.onFailure { t ->
            BootTrace.fail("Koin 启动失败", t)
            // 不抛出：让应用继续，UI 层会因依赖缺失而显示错误提示，
            // 至少比闪退给用户的诊断信息更多
            android.util.Log.e("FeishuCheckInApp", "Koin 启动失败", t)
        }

        // ── 第 3 步：崩溃落盘 ──
        runCatching {
            val paths = getKoin().get<AppPaths>()
            CrashHandler(paths).install(this)
            BootTrace.step("崩溃处理器已安装")
        }.onFailure { t ->
            BootTrace.fail("安装崩溃处理器失败", t)
        }

        // ── 第 4 步：文件日志 ──
        runCatching {
            if (BuildConfig.DEBUG) {
                Timber.plant(Timber.DebugTree())
            }
            val paths = getKoin().get<AppPaths>()
            val settingsRepository = getKoin().get<SettingsRepository>()

            // verboseProvider 是懒读的：Timber 在每条日志上都会问一次，
            // 因此不能每次去读 DataStore（会阻塞日志线程）
            //
            // 注意这里用普通 `var` 而不是 `@Volatile var`：
            // 局部变量上不允许标记 @Volatile（它作用于字段，不作用于局部量），
            // 写了编译不过。Kotlin 会把被闭包捕获的 var 编译成一个
            // Ref 包装对象，跨线程写入没有 happens-before 保证 ——
            // 但这里只是一个日志开关，读到「上一拍的值」完全无害，
            // 不值得为它引入 AtomicBoolean 的复杂度。
            var verbose = false
            appScope.launch {
                runCatching {
                    settingsRepository.settings.collect { verbose = it.verboseLog }
                }
            }

            Timber.plant(FileLogTree(paths, verboseProvider = { verbose }))
            BootTrace.step("文件日志已安装")
        }.onFailure { t ->
            BootTrace.fail("安装文件日志失败", t)
        }

        // ── 第 5 步：冷启动恢复调度 ──
        // 这是「三层闹钟恢复」里的第二层，专门应对国产 ROM
        // 拦截开机广播的情况：用户手动打开一次应用就能恢复调度。
        //
        // 放在协程里异步执行，不阻塞启动 —— 用户看到界面的速度
        // 不应该被读盘操作拖慢
        appScope.launch {
            runCatching {
                val settingsRepository = getKoin().get<SettingsRepository>()
                val alarmScheduler = getKoin().get<AlarmScheduler>()
                val recordRepository = getKoin().get<CheckInRecordRepository>()
                val planRepository = getKoin().get<CheckInPlanRepository>()

                val settings = settingsRepository.current()

                // 时区自愈：用户出差异地后系统时区变了，
                // 而闹钟还按旧时区排着，必须重排
                val currentZone = java.time.ZoneId.systemDefault().id
                if (settings.knownZoneId.isNotEmpty() && settings.knownZoneId != currentZone) {
                    Timber.i("时区已变更：%s → %s，重排闹钟", settings.knownZoneId, currentZone)
                    settingsRepository.setKnownZoneId(currentZone)
                }

                recordRepository.refresh()
                planRepository.refresh()

                if (settings.enabled) {
                    val next = alarmScheduler.rescheduleAll(settings)
                    BootTrace.step("冷启动重排闹钟: ${next ?: "未排定"}")
                } else {
                    BootTrace.step("自动打卡未启用，跳过重排")
                }
            }.onFailure { t ->
                BootTrace.fail("冷启动恢复调度失败", t)
            }
        }

        BootTrace.step("onCreate 完成")
    }
}
