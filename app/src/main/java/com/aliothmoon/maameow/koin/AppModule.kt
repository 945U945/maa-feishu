package com.aliothmoon.maameow.koin

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import com.aliothmoon.maameow.data.api.ETagCacheManager
import com.aliothmoon.maameow.data.api.HttpClientHelper
import com.aliothmoon.maameow.data.checkin.BackgroundImageStore
import com.aliothmoon.maameow.data.checkin.CheckInRepository
import com.aliothmoon.maameow.data.config.AppPathConfig
import com.aliothmoon.maameow.data.log.ApplicationLogWriter
import com.aliothmoon.maameow.data.notification.NotificationSettingsManager
import com.aliothmoon.maameow.data.notification.live.AospPromotedDetector
import com.aliothmoon.maameow.data.notification.live.FocusSequenceStore
import com.aliothmoon.maameow.data.notification.live.HyperOsFocusDetector
import com.aliothmoon.maameow.data.notification.live.LiveNotificationFactory
import com.aliothmoon.maameow.data.notification.live.LivePublisherRouter
import com.aliothmoon.maameow.data.notification.live.XmsfNetworkGate
import com.aliothmoon.maameow.data.notification.provider.BarkProvider
import com.aliothmoon.maameow.data.notification.provider.CustomWebhookProvider
import com.aliothmoon.maameow.data.notification.provider.DingTalkProvider
import com.aliothmoon.maameow.data.notification.provider.DiscordProvider
import com.aliothmoon.maameow.data.notification.provider.DiscordWebhookProvider
import com.aliothmoon.maameow.data.notification.provider.GotifyProvider
import com.aliothmoon.maameow.data.notification.provider.KookProvider
import com.aliothmoon.maameow.data.notification.provider.NotificationProvider
import com.aliothmoon.maameow.data.notification.provider.QmsgProvider
import com.aliothmoon.maameow.data.notification.provider.ServerChanProvider
import com.aliothmoon.maameow.data.notification.provider.SmtpProvider
import com.aliothmoon.maameow.data.notification.provider.TelegramProvider
import com.aliothmoon.maameow.data.preferences.AppSettingsManager
import com.aliothmoon.maameow.data.preferences.ConfigBackupManager
import com.aliothmoon.maameow.data.preferences.UnlockGestureStore
import com.aliothmoon.maameow.domain.checkin.CheckInEngine
import com.aliothmoon.maameow.domain.launch.CheckInRunner
import com.aliothmoon.maameow.domain.launch.CountdownUI
import com.aliothmoon.maameow.domain.launch.LaunchMutex
import com.aliothmoon.maameow.domain.launch.LaunchPipeline
import com.aliothmoon.maameow.domain.launch.LaunchRequest
import com.aliothmoon.maameow.domain.notification.LiveSessionCoordinator
import com.aliothmoon.maameow.domain.notification.LiveUpdatePublisher
import com.aliothmoon.maameow.domain.service.ExternalNotificationService
import com.aliothmoon.maameow.domain.service.LogExportService
import com.aliothmoon.maameow.domain.service.MaaSessionLogger
import com.aliothmoon.maameow.domain.service.ScreenSaverController
import com.aliothmoon.maameow.domain.service.TaskEndRegistry
import com.aliothmoon.maameow.domain.service.UnlockGestureReader
import com.aliothmoon.maameow.domain.service.WakeUnlockEngine
import com.aliothmoon.maameow.manager.PermissionManager
import com.aliothmoon.maameow.manager.RemoteServiceManager
import com.aliothmoon.maameow.overlay.OverlayController
import com.aliothmoon.maameow.overlay.OverlayViewModelOwner
import com.aliothmoon.maameow.overlay.border.BorderOverlayManager
import com.aliothmoon.maameow.overlay.screensaver.ScreenSaverOverlayManager
import com.aliothmoon.maameow.presentation.navigation.MainTabNavigator
import com.aliothmoon.maameow.schedule.LaunchIntentMapper
import com.aliothmoon.maameow.schedule.data.ScheduleStrategyRepository
import com.aliothmoon.maameow.schedule.service.CountdownUIImpl
import com.aliothmoon.maameow.schedule.service.ScheduleAlarmManager
import com.aliothmoon.maameow.schedule.service.ScheduleFailureReporter
import com.aliothmoon.maameow.schedule.service.ScheduleTriggerHandler
import com.aliothmoon.maameow.schedule.service.ScheduleTriggerLogger
import com.aliothmoon.maameow.utils.CrashHandler
import com.aliothmoon.maameow.utils.log.LogTreeHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * 全局依赖装配。
 *
 * 相比原 MAA-Meow 工程，本模块移除了全部 MAA 核心、游戏资源、
 * 干员识别相关装配，保留并新增：
 * - 打卡方案仓库与打卡引擎（新增）
 * - 定时调度链（原样保留，与原工程完全一致）
 * - 唤醒解锁、悬浮窗、通知、日志（原样保留）
 */
val appModule = module {

    // ── 路径与日志基础（其余一切日志/崩溃/导出都建立在它之上） ──
    //
    // 注意：AppPathConfig 与 MaaSessionLogger 必须注册，且在启动同步段
    // （MaaApplication.onCreate 里的 treeHolder.setup() / crashHandler.init()）
    // 就会被解析。曾因二者缺失导致应用启动即 NoDefinitionFoundException 闪退。
    single { AppPathConfig(androidContext()) }
    single { MaaSessionLogger(get()) }

    singleOf(::CrashHandler)
    single {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    singleOf(::HttpClientHelper)
    singleOf(::ETagCacheManager)
    singleOf(::PermissionManager)
    singleOf(::MainTabNavigator)

    // 用单参次构造函数：primary 需要 CoroutineScope，而工程内只注册了
    // named("launchPipeline") 的限定版本，无限定符的 CoroutineScope 解析不到。
    single { AppSettingsManager(androidContext()) }
    // 显式声明接口类型，不用 `bind` 扩展 —— 后者对 lambda 返回值的类型推断
    // 在 Kotlin 2.x + Koin 4.2 组合下不稳定，会报 receiver type mismatch。
    single<UnlockGestureReader> { UnlockGestureStore(get()) }

    // ── 打卡核心（新增） ──
    single { CheckInRepository(androidContext()) }
    single { CheckInEngine(androidContext()) }
    single { BackgroundImageStore(androidContext(), get()) }
    single {
        CheckInRunner(
            appContext = androidContext(),
            repository = get(),
            engine = get(),
            appLauncher = { pkg -> launchTargetApp(androidContext(), pkg) },
        )
    }

    // ── 定时调度（原样保留） ──
    single { ScheduleStrategyRepository(androidContext()) }
    singleOf(::ScheduleTriggerLogger)
    singleOf(::ScheduleFailureReporter)
    singleOf(::ScheduleAlarmManager)
    single { ScheduleTriggerHandler(get(), get(), get(), get(), get()) }
    singleOf(::LaunchMutex)

    single(named("launchPipeline")) {
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
    single<CountdownUI> {
        CountdownUIImpl(
            overlayController = get(),
            onUserEvent = { event -> get<LaunchPipeline>().submit(event) },
        )
    }
    single {
        val appContext = get<Context>()
        LaunchPipeline(
            scope = get(named("launchPipeline")),
            mutex = get(),
            appSettingsManager = get(),
            wakeUnlockEngine = get(),
            unlockGestures = get(),
            checkInRunner = get(),
            triggerLogger = get(),
            scheduleRepository = get(),
            countdownUI = get(),
            screenSaver = get(),
            taskEndRegistry = get(),
            keyguardLocked = {
                val km = appContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                km.isKeyguardLocked
            },
            deviceLocked = {
                val km = appContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                km.isDeviceLocked
            },
            screenInteractive = {
                val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                pm.isInteractive
            },
            activityLauncher = { request: LaunchRequest ->
                withTimeoutOrNull(10.seconds) {
                    runCatching {
                        RemoteServiceManager.useRemoteService(timeoutMs = 8_000L) {
                            it.startActivity(LaunchIntentMapper.toShowIntent(appContext, request))
                        }
                    }.getOrDefault(false)
                } ?: false
            },
        )
    }

    singleOf(::ConfigBackupManager)

    // ── 外部通知（保留，可用于推送打卡结果；CustomWebhook 同时支持飞书机器人） ──
    singleOf(::NotificationSettingsManager)
    single<NotificationProvider> { ServerChanProvider(get(), get()) }
    single<NotificationProvider> { TelegramProvider(get(), get()) }
    single<NotificationProvider> { DiscordProvider(get(), get()) }
    single<NotificationProvider> { DingTalkProvider(get(), get()) }
    single<NotificationProvider> { KookProvider(get(), get()) }
    single<NotificationProvider> { DiscordWebhookProvider(get(), get()) }
    single<NotificationProvider> { SmtpProvider(get()) }
    single<NotificationProvider> { BarkProvider(get(), get()) }
    single<NotificationProvider> { QmsgProvider(get(), get()) }
    single<NotificationProvider> { GotifyProvider(get(), get()) }
    single<NotificationProvider> { CustomWebhookProvider(get(), get()) }
    single { ExternalNotificationService(get(), get(), getAll()) }

    // 通知 / 实况
    singleOf(::LiveNotificationFactory)
    singleOf(::AospPromotedDetector)
    singleOf(::HyperOsFocusDetector)
    singleOf(::FocusSequenceStore)
    singleOf(::XmsfNetworkGate)
    singleOf(::LivePublisherRouter)
    single<LiveUpdatePublisher> { get<LivePublisherRouter>() }
    singleOf(::LiveSessionCoordinator)

    // 定时唤醒 + 解锁
    singleOf(::WakeUnlockEngine)

    single { TaskEndRegistry() }
    singleOf(::LogExportService)

    // 悬浮窗
    singleOf(::BorderOverlayManager)
    single<ScreenSaverController> { ScreenSaverOverlayManager(androidContext(), get()) }
    singleOf(::OverlayViewModelOwner)
    singleOf(::OverlayController)

    singleOf(::ApplicationLogWriter)
    singleOf(::LogTreeHolder)
}

/**
 * 通过包名拉起目标应用。
 *
 * 优先使用 launchIntent（标准入口，行为与用户手动点击图标一致）；
 * 拉不起来时返回 false，由上层给出明确提示。
 */
private fun launchTargetApp(context: Context, packageName: String): Boolean = try {
    val intent = context.packageManager.getLaunchIntentForPackage(packageName)
    if (intent == null) {
        false
    } else {
        intent.addFlags(
            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        )
        context.startActivity(intent)
        true
    }
} catch (e: Exception) {
    android.util.Log.w("AppModule", "拉起目标应用失败：$packageName", e)
    false
}
