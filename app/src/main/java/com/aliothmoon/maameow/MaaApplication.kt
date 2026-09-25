package com.aliothmoon.maameow

import android.app.Application
import com.aliothmoon.maameow.data.checkin.CheckInRepository
import com.aliothmoon.maameow.data.preferences.AppSettingsManager
import com.aliothmoon.maameow.domain.service.TaskEndRegistry
import com.aliothmoon.maameow.koin.appModule
import com.aliothmoon.maameow.koin.useCaseModule
import com.aliothmoon.maameow.koin.viewModelModule
import com.aliothmoon.maameow.overlay.OverlayController
import com.aliothmoon.maameow.schedule.data.ScheduleStrategyRepository
import com.aliothmoon.maameow.schedule.service.ScheduleAlarmManager
import com.aliothmoon.maameow.utils.CrashHandler
import com.aliothmoon.maameow.utils.i18n.LocaleBootstrap
import com.aliothmoon.maameow.utils.log.LogTreeHolder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import timber.log.Timber

/**
 * 应用入口。
 *
 * 启动流程分两段：
 * 1. 同步段（onCreate）：装配依赖、初始化日志与崩溃捕获 —— 必须足够快，
 *    否则冷启动时被 system 拉起的定时广播会因进程未就绪而丢失。
 * 2. 异步段（postCreateApplication）：等设置读盘完成后，启动各类后台服务
 *    并恢复定时闹钟。
 *
 * 相比原 MAA-Meow 工程，移除了 MAA 核心、游戏资源、干员箱等初始化，
 * 新增打卡方案仓库的初始化。
 */
class MaaApplication : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initialization = CompletableDeferred<Unit>()
    private val appSettingsManager: AppSettingsManager by inject()
    private val crashHandler: CrashHandler by inject()
    private val taskEndRegistry: TaskEndRegistry by inject()
    private val overlayController: OverlayController by inject()
    private val treeHolder: LogTreeHolder by inject()
    private val scheduleRepository: ScheduleStrategyRepository by inject()
    private val scheduleAlarmManager: ScheduleAlarmManager by inject()
    private val checkInRepository: CheckInRepository by inject()

    suspend fun awaitReady() = initialization.await()

    override fun onCreate() {
        super.onCreate()
        val app = this
        startKoin {
            androidLogger(if (BuildConfig.DEBUG) Level.DEBUG else Level.NONE)
            androidContext(app)
            modules(appModule, useCaseModule, viewModelModule)
        }
        // 不等设置读盘，冷启动 receiver / FGS 的日志与崩溃才接得住
        treeHolder.setup()
        crashHandler.init(this)

        applicationScope.launch(Dispatchers.Main) {
            appSettingsManager.awaitLoaded()
            LocaleBootstrap.applyPersisted(appSettingsManager)
            postCreateApplication()
            initialization.complete(Unit)
        }.invokeOnCompletion { cause ->
            if (cause != null) initialization.completeExceptionally(cause)
        }
    }

    private fun postCreateApplication() {
        overlayController.setup()
        taskEndRegistry.start()
        applicationScope.launch { crashHandler.cleanOldCrashLogs() }
        // 打卡方案需在首次使用前就绪，供定时任务查询
        applicationScope.launch { checkInRepository.initialize() }
        doSyncScheduleAlarms()
    }

    /**
     * 恢复定时闹钟。
     *
     * BootReceiver 依赖 ACTION_BOOT_COMPLETED / MY_PACKAGE_REPLACED 恢复闹钟，
     * 但国产 ROM 在自启动未开启时会拦截这些广播，导致闹钟丢失后无法恢复。
     * 因此每次应用启动时再执行一次幂等同步，作为兜底保障。
     */
    private fun doSyncScheduleAlarms() {
        applicationScope.launch {
            scheduleRepository.isLoaded.filter { it }.first()
            scheduleAlarmManager.rescheduleAll(scheduleRepository.strategies.value)
            Timber.i("启动同步：已恢复 %d 个定时任务", scheduleRepository.strategies.value.size)
        }
    }
}
