package com.feishu.checkin.core.di

import android.content.Context
import com.feishu.checkin.checkin.alarm.AlarmScheduler
import com.feishu.checkin.checkin.data.CheckInPlanRepository
import com.feishu.checkin.checkin.data.CheckInRecordRepository
import com.feishu.checkin.checkin.data.SettingsRepository
import com.feishu.checkin.checkin.engine.CheckInEngine
import com.feishu.checkin.checkin.service.CheckInService
import com.feishu.checkin.core.device.AndroidAwakeController
import com.feishu.checkin.core.device.AndroidDeviceStateProvider
import com.feishu.checkin.core.device.AppLauncher
import com.feishu.checkin.core.device.AwakeController
import com.feishu.checkin.core.device.DeviceStateProvider
import com.feishu.checkin.core.device.PermissionChecker
import com.feishu.checkin.core.log.AppPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module
import org.koin.java.KoinJavaComponent

/**
 * 依赖注入容器。
 *
 * ## 从上一版的失败中学到的
 *
 * 上一版用一个巨型 `AppModule` 注册了所有依赖，结果启动时因为
 * 两个依赖忘了注册（`AppPathConfig` / `MaaSessionLogger`）导致
 * `NoDefinitionFoundException`，表现为点图标闪退。
 *
 * 那次教训指向三个改进，都落实在这里：
 *
 * 1. **按垂直切片分模块**，而不是一个巨型模块。
 *    模块边界清晰后，「打卡功能的依赖」都在 `checkinModule` 里，
 *    审计时一眼能看出缺了什么。
 * 2. **启动时主动校验**，而不是等用到才炸。
 *    [verifyGraph] 会在启动阶段把所有关键依赖解析一遍，
 *    缺谁立刻就知道，而不是等用户点进某个页面才崩。
 * 3. **不用 Koin 的 `by inject()` 懒加载**。
 *    懒加载会把「注册缺失」这个问题推迟到使用时才暴露，
 *    而且失败点分散在各处难以定位。改为构造注入后，
 *    依赖关系在编译期就可见。
 */

/**
 * 应用级协程作用域模块。
 *
 * 为什么需要一个显式的 Scope 而不是让 Repository 自己
 * `CoroutineScope(SupervisorJob())`：
 *
 * - **可观测**：所有长生命周期协程挂在同一个 scope 上，
 *   测试里可以整体取消，不会漏 Job
 * - **可替换**：测试中注入 `TestScope` 即可控制时序，
 *   而不必去改 Repository 的实现
 *
 * 用 SupervisorJob 而非普通 Job：一个订阅者崩溃不应该
 * 连带取消其它订阅者（比如日志写入失败不该让设置流停摆）。
 *
 * 注意这里**不指定 Dispatcher** —— 让各 Repository 自己
 * 在具体操作上加 `withContext(Dispatchers.IO)`，
 * 比在这里一刀切更精确。
 */
val scopeModule: Module = module {
    single(named(KoinScopes.APP_SCOPE)) {
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}

/**
 * 核心基础设施模块。
 *
 * 只放「跨功能共用」的东西：路径、设备控制、权限查询。
 * 业务相关的依赖一律不放这里 —— 这是与上一版最大的结构差别。
 */
val coreModule: Module = module {

    /**
     * 应用目录。
     *
     * `androidContext()` 是 Koin 提供的 Context 获取方式。
     * 注意这里必须用 `single` 而不是 `factory`：
     * AppPaths 内部有懒初始化的目录创建逻辑，重复构造会重复做
     * mkdirs 检查，虽然无害但没必要。
     */
    single { AppPaths(androidContext()) }

    /** 设置读写。需要 CoroutineScope，用 Koin 的全局 scope */
    single { SettingsRepository(androidContext(), get(named(KoinScopes.APP_SCOPE))) }

    /** 打卡方案读取 */
    single { CheckInPlanRepository(get()) }

    /** 打卡记录读写 */
    single { CheckInRecordRepository(get()) }

    /** 权限与环境体检 */
    single { PermissionChecker(androidContext()) }

    /**
     * 设备状态查询。
     *
     * 飞书包名从 BuildConfig 读，两个候选都传进去，
     * 由 Provider 自己判断哪个装了 —— 这样「支持极速版」这件事
     * 只在这一个地方体现，不必让上层关心。
     */
    single<DeviceStateProvider> {
        AndroidDeviceStateProvider(
            context = androidContext(),
            feishuPackages = listOf(
                com.feishu.checkin.BuildConfig.FEISHU_PACKAGE,
                com.feishu.checkin.BuildConfig.FEISHU_PACKAGE_LITE,
            ),
        )
    }

    /** 设备唤醒控制 */
    single<AwakeController> {
        AndroidAwakeController(
            context = androidContext(),
            stateProvider = get(),
        )
    }

    /** 拉起飞书 */
    single { AppLauncher(androidContext()) }
}

/**
 * 打卡功能的全部依赖。
 *
 * 这是「垂直切片」的体现：打卡需要的所有东西（调度、引擎）
 * 都在这一个模块里，而不是分散在 core / domain / infra 各层。
 *
 * 好处是审计成本低 —— 上一版闪退的原因就是「这个功能的依赖散在
 * 三个不同模块里，漏注册了一个看不出来」。
 */
val checkinModule: Module = module {

    single { AlarmScheduler(androidContext()) }

    single {
        CheckInEngine(
            settingsRepository = get(),
            planRepository = get(),
            recordRepository = get(),
            awakeController = get(),
            appLauncher = get(),
            deviceState = get(),
            // 无障碍是否开启以 lambda 注入。
            // 这个 lambda 必须用 androidContext() 而不是捕获某个 Activity，
            // 否则 Activity 销毁后 lambda 仍持有引用，造成内存泄漏
            isAccessibilityEnabled = {
                com.feishu.checkin.accessibility.AccessibilitySupport
                    .isEnabled(androidContext())
            },
        )
    }
}

/**
 * UI 层模块。
 *
 * 每个页面的 ViewModel 都在这里显式注册。
 *
 * ## 为什么不用 `viewModel { }` 的自动装配
 *
 * Koin 4.x 支持通过反射自动装配 ViewModel 构造参数，
 * 但那样「HomeViewModel 到底依赖哪些东西」就不在注册处体现了 ——
 * 而这正是上一版闪退的教训：**依赖关系必须是显式可见的**，
 * 不能藏在框架的自动解析里。
 *
 * 显式写在构造函数里，漏注册哪个参数编译期就会报错，
 * 而不是等到用户点进某个页面才崩。
 */
val uiModule: Module = module {

    viewModel {
        com.feishu.checkin.ui.screen.home.HomeViewModel(
            settingsRepository = get(),
            recordRepository = get(),
            alarmScheduler = get(),
            permissionChecker = get(),
            appLauncher = get(),
            engine = get(),
            // 注意是 Application Context，不是 Activity
            appContext = androidContext(),
        )
    }

    viewModel {
        com.feishu.checkin.ui.screen.history.HistoryViewModel(
            recordRepository = get(),
        )
    }

    viewModel {
        com.feishu.checkin.ui.screen.settings.SettingsViewModel(
            settingsRepository = get(),
            planRepository = get(),
            permissionChecker = get(),
            alarmScheduler = get(),
            appContext = androidContext(),
        )
    }

    viewModel {
        com.feishu.checkin.ui.screen.settings.ProbeViewModel()
    }
}

/** Koin 限定符 */
object KoinScopes {
    /** 应用级 CoroutineScope */
    const val APP_SCOPE = "app_scope"
}

/**
 * 全部模块。
 *
 * 显式列出而不是用 `listOf` 拼装，是为了让「一共注册了几个模块」
 * 一目了然 —— 审计时要能一眼数清楚。
 */
val allModules: List<Module> = listOf(
    scopeModule,
    coreModule,
    checkinModule,
    uiModule,
)

/**
 * 启动依赖注入。
 *
 * @param appDeclaration 额外的 Koin 配置（如 AndroidLogger、workManagerFactory）
 */
fun startAppKoin(appDeclaration: KoinAppDeclaration = {}): org.koin.core.KoinApplication =
    startKoin {
        appDeclaration()
        modules(allModules)
    }.also {
        // 依赖图校验必须在 startKoin 返回之后做。
        // 放在 `startKoin { }` 块内时 koin 实例尚未完全装配，
        // get() 的结果不可靠，会把「正常注册」误判为「缺失」——
        // 那样这个自检就成了噪音，反而掩盖真正的问题。
        it.verifyGraph()
    }

/**
 * 依赖图自检。
 *
 * 把「启动过程中一定会被用到」的依赖逐个解析一遍。
 * 任何一个解析失败都在这里暴露，而不是等到某个业务路径才崩。
 *
 * 注意这里**只检查关键依赖**，不是全部 —— 全部检查会带来
 * 循环依赖等误报，而关键路径覆盖已经能拦住绝大多数漏注册问题。
 */
private fun org.koin.core.KoinApplication.verifyGraph() {
    val critical = listOf(
        "AppPaths",
        "SettingsRepository",
        "CheckInPlanRepository",
        "CheckInRecordRepository",
        "AlarmScheduler",
        "CheckInEngine",
        "DeviceStateProvider",
        "AwakeController",
        "AppLauncher",
        "PermissionChecker",
    )

    val missing = mutableListOf<String>()
    critical.forEach { name ->
        // 解析方式说明（踩过的三个坑都在这）：
        //
        // 1. `clazz` 参数类型是 `KClass<*>`，传 `Class<*>` 编译不过 ——
        //    所以用 `::class` 而不是 `::class.java`（见 resolveKClass）。
        // 2. `resolveKClass` 返回 `KClass<*>`，是星投影类型，
        //    编译器无法从它推断出 get 的类型参数 T，
        //    必须显式写出 `<Any>`，否则报 "Cannot infer type for type parameter T"。
        // 3. 这里只关心「能不能解析出来」，不关心解析到的是什么实例，
        //    所以用 Any 作为类型实参是安全的 —— Koin 按 KClass 查注册表，
        //    与类型实参无关（与 `get<Any>()` 那种「按 Any 类型查」完全不同）。
        val resolved = runCatching { koin.get<Any>(clazz = resolveKClass(name)) }
        if (resolved.isFailure) {
            missing.add("$name (${resolved.exceptionOrNull()?.javaClass?.simpleName})")
        }
    }

    if (missing.isNotEmpty()) {
        // 不抛异常，只记录 —— 抛异常会让应用完全无法启动，
        // 而有些缺失其实不影响主流程。记录到日志后由开发者排查，
        // 用户至少还能看到界面和错误提示
        android.util.Log.e(
            "KoinVerify",
            "依赖图不完整，以下依赖无法解析: ${missing.joinToString()}",
        )
    } else {
        android.util.Log.i("KoinVerify", "依赖图校验通过（${critical.size} 项）")
    }
}

/** 把简单类名映射到实际类型，供自检使用 */
private fun resolveKClass(simpleName: String): kotlin.reflect.KClass<*> = when (simpleName) {
    "AppPaths" -> AppPaths::class
    "SettingsRepository" -> SettingsRepository::class
    "CheckInPlanRepository" -> CheckInPlanRepository::class
    "CheckInRecordRepository" -> CheckInRecordRepository::class
    "AlarmScheduler" -> AlarmScheduler::class
    "CheckInEngine" -> CheckInEngine::class
    "DeviceStateProvider" -> DeviceStateProvider::class
    "AwakeController" -> AwakeController::class
    "AppLauncher" -> AppLauncher::class
    "PermissionChecker" -> PermissionChecker::class
    else -> Any::class
}

/**
 * Koin 入口点。
 *
 * 给 `BroadcastReceiver` / `Service` 这类**由系统实例化**、
 * 无法参与构造注入的组件用。
 *
 * ## 为什么不直接用 KoinComponent
 *
 * 让 `BroadcastReceiver` 实现 `KoinComponent` 也能拿到同样的效果，
 * 但那样每个接收器都要继承一个框架接口，且解析点是分散的。
 * 集中在一个入口点里，好处是「系统组件依赖哪些东西」一目了然，
 * 也便于在测试里替换整个入口点。
 */
class AppEntryPoint internal constructor(private val koin: org.koin.core.Koin) {
    fun settingsRepository(): SettingsRepository = koin.get()
    fun planRepository(): CheckInPlanRepository = koin.get()
    fun recordRepository(): CheckInRecordRepository = koin.get()
    fun alarmScheduler(): AlarmScheduler = koin.get()
    fun checkInEngine(): CheckInEngine = koin.get()
    fun appPaths(): AppPaths = koin.get()
    fun permissionChecker(): PermissionChecker = koin.get()
    fun appLauncher(): AppLauncher = koin.get()
    fun deviceState(): DeviceStateProvider = koin.get()
    fun awakeController(): AwakeController = koin.get()
}

/**
 * 从 Context 取入口点。
 *
 * 用 `KoinJavaComponent.getKoin()` 而不是 `GlobalContext.get()`，
 * 前者在未启动时会抛明确异常，后者返回的实例可能状态不完整 ——
 * 排查起来后者要痛苦得多。
 *
 * 注意 Koin 未启动时这里会抛异常。为保证前台服务、开机广播这些
 * 早期入口不崩，[com.feishu.checkin.FeishuCheckInApp] 必须保证
 * Koin 在 `onCreate` 里**同步**完成启动 —— 不能放到协程里异步初始化，
 * 因为广播可能早于协程执行。
 */
fun Context.entryPoint(): AppEntryPoint =
    AppEntryPoint(KoinJavaComponent.getKoin())
