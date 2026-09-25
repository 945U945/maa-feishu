package com.feishu.checkin.checkin.engine

import com.feishu.checkin.accessibility.CheckInAccessibilityService
import com.feishu.checkin.checkin.data.BuiltInPlans
import com.feishu.checkin.checkin.data.CheckInPlanRepository
import com.feishu.checkin.checkin.data.CheckInRecordRepository
import com.feishu.checkin.checkin.data.SettingsRepository
import com.feishu.checkin.checkin.model.CheckInKind
import com.feishu.checkin.checkin.model.CheckInPhase
import com.feishu.checkin.checkin.model.CheckInPlan
import com.feishu.checkin.checkin.model.CheckInRecord
import com.feishu.checkin.checkin.model.CheckInRequest
import com.feishu.checkin.checkin.model.CheckInResult
import com.feishu.checkin.checkin.model.StepSelector
import com.feishu.checkin.checkin.model.TimelineEntry
import com.feishu.checkin.core.device.AwakeController
import com.feishu.checkin.core.device.AppLauncher
import com.feishu.checkin.core.device.DeviceStateProvider
import com.feishu.checkin.core.device.EntryOpener
import com.feishu.checkin.core.device.EntryOpening
import com.feishu.checkin.core.device.DeepLinkResult
import com.feishu.checkin.core.device.DeepLinkSender
import com.feishu.checkin.core.device.LandingVerifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import timber.log.Timber

/**
 * 打卡执行引擎。
 *
 * ## 三条硬约束
 *
 * ### 1. 互斥
 *
 * 用 [Mutex] 保证同一时刻只有一个打卡流程在跑。这是必需的，
 * 因为触发源天然是并发的：闹钟可能恰好在用户点了「立即打卡」的瞬间到达。
 * 没有互斥的话两个流程会同时操作界面，互相抢焦点，
 * 结果是两个都失败 —— 而且失败得莫名其妙，用户完全无法理解。
 *
 * ### 2. 幂等
 *
 * 用「日期 + 类型」作为幂等键（见 [CheckInRequest.dedupeKey]）。
 * 执行前先查有没有当天同类型的成功记录，有就返回 [CheckInResult.ALREADY_DONE]。
 *
 * 注意这不只是省事 —— 重复打卡在有些企业会被记为考勤异常，
 * 所以「不重复打」是正确性要求，不是优化。
 *
 * ### 3. 结果语义严格分离
 *
 * [CheckInResult.ALREADY_DONE] 与 [CheckInResult.SKIPPED_BUSY] 是**正常终态**，
 * 绝不能记成失败。这条规则的价值在于：让真正的故障在记录列表里是显眼的。
 * 如果今天「已打卡」也被标红，那用户会习惯性忽略所有红色提示，
 * 于是真正的「未找到打卡入口」被淹没 —— 这是从 MAA 的
 * SKIPPED / FAILED 分离设计里学到的。
 *
 * ## 关于重试
 *
 * 只对**可能自愈**的失败重试（网络抖动、界面还没加载完），
 * 不对**确定性**失败重试（找不到打卡入口、设备锁屏）——
 * 后者重试只会浪费时间并让用户多等。
 */
class CheckInEngine(
    private val settingsRepository: SettingsRepository,
    private val planRepository: CheckInPlanRepository,
    private val recordRepository: CheckInRecordRepository,
    private val awakeController: AwakeController,
    private val appLauncher: AppLauncher,
    private val deviceState: DeviceStateProvider,
    /**
     * 无障碍是否已开启。
     *
     * 以 lambda 形式注入而不是直接持 Context（同 `core/device` 的做法），
     * 好处是引擎本体不依赖 Android 框架类，能在纯 JVM 测试里跑 ——
     * 也避免了「从别的对象反射掏 Context」这种脆弱写法
     * （反射在 R8 混淆后必然失效，那正是上一版的闪退根因）。
     */
    private val isAccessibilityEnabled: () -> Boolean,
    /**
     * 深链发送器。
     *
     * 允许为 null —— 这是「Shizuku 可选 + 深链可选」的体现：
     * 传 null 时引擎完全退回传统点击导航，
     * 与升级前的行为一致，不会因为新功能引入而让老配置失效。
     */
    private val deepLinkLauncher: DeepLinkSender? = null,
    /**
     * 落点校验器。
     *
     * 同样允许为 null —— 没有它时跳转后无法校验落点，
     * 引擎会按「乐观继续」处理（跳转成功即认为到达）。
     * 这比「因为没有校验器就完全禁用深链」更合理：
     * 深链本身仍然解决了「找不到入口」这个核心问题。
     */
    private val landingVerifier: LandingVerifier? = null,
    private val stepExecutor: StepExecutor = StepExecutor(),
) {

    /**
     * 入口打开器。
     *
     * 在这里**内部构造**而不是从外部注入，原因是它的
     * [EntryOpener.clickNavigation] 参数需要访问本引擎的
     * [runClickNavigation]（那要用到无障碍服务实例，
     * 而服务实例只有在执行期才拿得到）。
     *
     * 若改为外部注入，就会形成「引擎依赖打开器、打开器依赖引擎」
     * 的循环，Koin 解析时直接死循环。内部构造是最干净的解法。
     *
     * `deepLinkLauncher` 或 `landingVerifier` 为 null 时传一个
     * 「什么都不做」的降级实现 —— 见 [NoopDeepLinkLauncher]。
     */
    private val entryOpener: EntryOpener = EntryOpener(
        launcher = deepLinkLauncher ?: NoopDeepLinkLauncher,
        verifier = landingVerifier ?: LandingVerifier(),
        clickNavigation = { currentClickNavigation() },
    )
    /**
     * 供 [EntryOpener] 回调的点击导航入口。
     *
     * 需要一个「当时的」无障碍服务实例与方案 —— 两者都在
     * [runPipeline] 的作用域里。这里用一个可变字段把执行期的
     * 上下文交给 lambda：执行是串行的（由 `executionLock` 保证），
     * 所以不存在并发写入问题。
     */
    @Volatile
    private var navigationContext: Pair<CheckInAccessibilityService, CheckInPlan>? = null

    private suspend fun currentClickNavigation(): Boolean {
        val (service, plan) = navigationContext ?: run {
            Timber.w("点击导航上下文缺失")
            return false
        }
        return runClickNavigation(service, plan)
    }

    /**
     * 执行互斥锁。
     *
     * `tryLock` 而不是 `lock` —— 拿不到锁说明已有任务在跑，
     * 此时应该立即返回 SKIPPED_BUSY 而不是排队等待。
     * 排队会让「点两下立即打卡」变成执行两次，正是我们要避免的。
     */
    private val executionLock = Mutex()

    /** 当前是否正在执行（UI 用来显示进度并禁用按钮） */
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** 当前执行阶段（UI 用来展示「卡在哪一步」） */
    private val _phase = MutableStateFlow<CheckInPhase?>(null)
    val phase: StateFlow<CheckInPhase?> = _phase.asStateFlow()

    /** 执行时间线，随执行推进而累积 */
    private val timeline = mutableListOf<TimelineEntry>()

    /**
     * 执行一次打卡（含失败重试）。
     *
     * @param request 打卡请求
     * @return 执行结果
     */
    suspend fun execute(request: CheckInRequest): CheckInResult {
        // ── 互斥：拿不到锁说明已有任务在跑 ──
        if (!executionLock.tryLock()) {
            Timber.w("已有打卡任务在执行，本次跳过: %s", request.dedupeKey)
            return CheckInResult.SKIPPED_BUSY
        }

        try {
            _running.value = true
            timeline.clear()
            markPhase(CheckInPhase.PREPARING)

            val settings = settingsRepository.current()
            val result = executeWithRetry(request, settings.retryCount)

            markPhase(CheckInPhase.DONE)
            persist(request, result)
            return result
        } finally {
            _phase.value = null
            _running.value = false
            executionLock.unlock()
        }
    }

    /**
     * 带重试的执行。
     *
     * ## 只对「可能自愈」的失败重试
     *
     * - **重试**：[CheckInResult.FAILURE]（结果确认超时 —— 可能是网络慢）、
     *   [CheckInResult.NETWORK_ERROR]（抖动）
     * - **不重试**：[CheckInResult.ENTRY_NOT_FOUND]（方案失效，重试还是找不到）、
     *   [CheckInResult.DEVICE_LOCKED]（解锁不了，重试还是解锁不了）、
     *   [CheckInResult.PERMISSION_MISSING]（权限要用户去开，重试无意义）
     *
     * 判断依据是「这个失败是否与环境瞬时状态相关」。对确定性失败重试
     * 只会让用户多等几十秒，且把日志弄脏。
     */
    private suspend fun executeWithRetry(
        request: CheckInRequest,
        retryCount: Int,
    ): CheckInResult {
        var last = runGuarded(request)
        var attempt = 0

        while (attempt < retryCount && last.isRetryable) {
            attempt++
            Timber.i("第 %d 次重试（上次结果 %s）", attempt, last.name)
            markPhase(CheckInPhase.PREPARING, note = "重试 $attempt")

            delay(RETRY_INTERVAL_MS)

            // 重试前重新拉起飞书：上次失败可能因为界面已经跑偏了
            deviceState.feishuPackageName()?.let { appLauncher.launch(it) }
            delay(LAUNCH_SETTLE_MS)

            last = runGuarded(request)
        }

        if (attempt > 0) {
            Timber.i("重试结束，最终结果 %s", last.name)
        }
        return last
    }

    /** 带异常兜底的主体流程 —— 任何异常都转成 FAILURE 而不是向上抛 */
    private suspend fun runGuarded(request: CheckInRequest): CheckInResult {
        return try {
            runPipeline(request)
        } catch (ce: CancellationException) {
            // 协程取消必须原样抛出，否则会破坏结构化并发
            throw ce
        } catch (t: Throwable) {
            Timber.e(t, "打卡执行异常")
            CheckInResult.FAILURE
        }
    }

    private suspend fun runPipeline(request: CheckInRequest): CheckInResult {
        val settings = settingsRepository.current()

        // ── 0. 幂等：今天这个类型已经成功过就不重复打 ──
        val existing = recordRepository.findExisting(request.dedupeKey)
        if (existing?.result?.isSuccessLike == true) {
            Timber.i("今日已打卡，跳过: %s", request.dedupeKey)
            return CheckInResult.ALREADY_DONE
        }

        // ── 1. 权限前置校验 ──
        // 放在最前面是因为这一项失败时后续所有操作都是徒劳的，
        // 早点返回能让用户更快看到「去开启无障碍」的提示
        if (!isAccessibilityEnabled()) {
            Timber.w("无障碍服务未开启")
            return CheckInResult.PERMISSION_MISSING
        }

        // ── 2. 设备可用性 ──
        if (!awakeController.ensureAwakeAndUnlocked()) {
            Timber.w("设备无法唤醒或仍处于锁屏")
            return CheckInResult.DEVICE_LOCKED
        }

        // ── 3. 定位飞书并拉起到前台 ──
        markPhase(CheckInPhase.LAUNCHING)
        val pkg = deviceState.feishuPackageName()
        if (pkg == null) {
            Timber.w("未检测到飞书")
            return CheckInResult.PERMISSION_MISSING
        }
        if (!appLauncher.launch(pkg)) {
            Timber.w("拉起飞书失败")
            return CheckInResult.ENTRY_NOT_FOUND
        }
        delay(LAUNCH_SETTLE_MS)

        // ── 4. 等待无障碍服务真正连上 ──
        // 拉起飞书后，无障碍服务可能还在处理窗口切换事件。
        // 这里等服务实例可用 —— 仅仅「设置里开着」不等于「进程已连上」
        val service = awaitAccessibilityService()
        if (service == null) {
            Timber.w("无障碍服务未连接")
            return CheckInResult.PERMISSION_MISSING
        }

        val plan = planRepository.byId(settings.planId)

        // 把执行期上下文交给点击导航回调（见 navigationContext 注释）
        navigationContext = service to plan

        // ── 5. 打开打卡入口（新：深链优先，点击导航兜底） ──
        // 这一段取代了原来直接跑 plan.steps 的做法。
        // 顺序见 EntryOpener：自定义深链 → 内置候选 → 传统点击导航
        markPhase(CheckInPhase.OPENING_ENTRY)
        val landingKeywords = plan.landingKeywords.ifEmpty {
            BuiltInPlans.ATTENDANCE_LANDING_KEYWORDS
        }

        val candidates = settings.entryCandidates()
        Timber.i("入口候选 %d 条，策略 %s", candidates.size, plan.entryStrategy)

        val opening = entryOpener.open(
            strategy = plan.entryStrategy,
            entries = candidates,
            planKeywords = landingKeywords,
            feishuPackage = pkg,
            readPageText = { service.dumpPageText() },
        )

        when (opening) {
            is EntryOpening.Misplaced -> {
                // 用户明确选择「直接报失败并提示我」：不自动回退到点击导航。
                // 理由见 EntryOpener 的类注释 —— 链接配错是确定性问题，
                // 掩盖它只会让后续排查更混乱
                Timber.w("深链落点不符，中止: %s", opening.diagnostic)
                return CheckInResult.DEEPLINK_LANDING_MISMATCH
            }

            is EntryOpening.Unresolved -> {
                // 深链发不出去（格式错/无接收方）。这时**允许**退回点击导航，
                // 因为这是「这条链接在当前设备不可用」而非「配错了」——
                // 用户没做错什么，用旧方案跑通比直接报失败更有价值
                Timber.w("深链不可用，退回点击导航: %s", opening.reason)
                markPhase(CheckInPhase.NAVIGATING, note = "退回点击导航")
                val ok = runClickNavigation(service, plan)
                if (!ok) return CheckInResult.ENTRY_NOT_FOUND
            }

            is EntryOpening.NoEntry -> {
                Timber.w("无可用入口: %s", opening.reason)
                return CheckInResult.ENTRY_NOT_FOUND
            }

            is EntryOpening.Opened -> {
                Timber.i("入口已打开 [%s]: %s", opening.via, opening.detail)
                // 用深链打开时无需再跑 plan.steps（那些步骤就是"手动找入口"的过程），
                // 直接进入打卡环节
                markPhase(CheckInPhase.VERIFYING_LANDING, note = opening.detail.take(80))
            }
        }

        // ── 6. 先判断是不是已经打过了 ──
        // 必须在点击前判断，否则会对已完成的考勤页再点一次。
        // 放在入口打开之后是因为「已打卡」的文案只可能出现在考勤页上
        val alreadyDone = service.containsAnyText(plan.alreadyDoneKeywords)
        if (alreadyDone != null) {
            Timber.i("界面提示已打卡: %s", alreadyDone)
            return CheckInResult.ALREADY_DONE
        }

        // ── 7. 点击打卡按钮 ──
        markPhase(CheckInPhase.CHECKING_IN)
        val clicked = clickCheckInButton(service, request.kind)
        if (!clicked) {
            val hint = service.containsAnyText(plan.alreadyDoneKeywords)
            return if (hint != null) CheckInResult.ALREADY_DONE else CheckInResult.ENTRY_NOT_FOUND
        }

        // ── 8. 确认结果 ──
        markPhase(CheckInPhase.VERIFYING)
        return verifyResult(service, plan.successKeywords, plan.alreadyDoneKeywords)
    }

    /**
     * 传统点击导航：按方案的步骤列表逐条执行。
     *
     * 抽成独立方法是因为它现在有**两个调用点**：
     * 策略明确指定 [EntryStrategy.CLICK_ONLY] 时由 [EntryOpener] 调用，
     * 以及深链不可用时由本引擎直接调用。
     *
     * @return 是否顺利走完所有步骤
     */
    private suspend fun runClickNavigation(
        service: CheckInAccessibilityService,
        plan: CheckInPlan,
    ): Boolean {
        for (step in plan.steps) {
            when (val outcome = stepExecutor.execute(service, step, STEP_TIMEOUT_MS)) {
                is StepOutcome.SUCCESS, StepOutcome.SKIPPED -> Unit
                is StepOutcome.FAILED -> {
                    // 单步失败不立即放弃：先看看是不是「其实已经打过了」
                    // （比如点击失败但页面已经跳过去了）
                    val hint = service.containsAnyText(plan.alreadyDoneKeywords)
                    if (hint != null) {
                        Timber.i("步骤失败但界面已打卡: %s", hint)
                        // 交给调用方的 alreadyDone 判断统一处理
                        return true
                    }
                    Timber.w("方案步骤失败，终止: %s", outcome.reason)
                    return false
                }
            }
        }
        return true
    }

    /**
     * 点击打卡按钮。
     *
     * 按 [kind] 选正确的按钮：考勤页上「上班打卡」与「下班打卡」
     * 是两个并列的按钮，点错等于打错类型 —— 有些企业会直接记异常。
     *
     * 策略：先按类型专属文案找（「上班打卡」），找不到再用通用「打卡」，
     * 并用 index 区分。这样既精确又有兜底。
     */
    private suspend fun clickCheckInButton(
        service: CheckInAccessibilityService,
        kind: CheckInKind,
    ): Boolean {
        // 第一优先：类型专属文案
        for (keyword in BuiltInPlans.buttonKeywords(kind)) {
            val selector = StepSelector(text = keyword, textContains = keyword)
            if (service.findNode(selector) != null && service.click(selector)) {
                Timber.i("已点击 %s 按钮（精确文案: %s）", kind.name, keyword)
                return true
            }
        }

        // 第二优先：通用「打卡」按钮。
        // 上下班按钮通常上下排列，上班在前（index 0）、下班在后（index 1），
        // 但这个顺序不保证，所以放在第二优先而不是唯一方案
        val generic = StepSelector(
            text = "打卡",
            textContains = "打卡",
            index = if (kind == CheckInKind.CLOCK_IN) 0 else 1,
        )
        if (service.findNode(generic) != null && service.click(generic)) {
            Timber.i("已点击打卡按钮（通用文案, index=%d）", generic.index)
            return true
        }

        Timber.w("未找到可点击的打卡按钮: %s", kind.name)
        return false
    }

    /**
     * 确认打卡结果。
     *
     * 轮询等待成功文案出现，而不是点完就宣布成功 ——
     * 按钮点了不等于服务端接受了（可能网络超时、可能需要二次确认）。
     * 这一步是整个流程里唯一有可信度的「成功」依据。
     */
    private suspend fun verifyResult(
        service: CheckInAccessibilityService,
        successKeywords: List<String>,
        alreadyDoneKeywords: List<String>,
    ): CheckInResult {
        val deadline = System.currentTimeMillis() + VERIFY_TIMEOUT_MS

        while (System.currentTimeMillis() < deadline) {
            service.containsAnyText(successKeywords)?.let { hit ->
                Timber.i("确认打卡成功: %s", hit)
                return CheckInResult.SUCCESS
            }
            service.containsAnyText(alreadyDoneKeywords)?.let { hit ->
                Timber.i("确认已是打卡状态: %s", hit)
                return CheckInResult.SUCCESS
            }
            delay(VERIFY_POLL_MS)
        }

        // 超时后仍无法确认。这里返回 FAILURE 而不是 SUCCESS ——
        // 宁可多报一次待确认，也不能谎报成功（谎报的代价是用户真的漏打卡）
        Timber.w("打卡结果确认超时")
        return CheckInResult.FAILURE
    }

    /**
     * 等待无障碍服务实例可用。
     *
     * 「设置里已开启」与「服务实例已连上」是两件事：
     * 前者是静态配置，后者要求服务进程已经收到 onServiceConnected。
     * 刚拉起飞书时窗口在切换，服务可能还没就绪。
     */
    private suspend fun awaitAccessibilityService(): CheckInAccessibilityService? {
        val deadline = System.currentTimeMillis() + SERVICE_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            CheckInAccessibilityService.instance?.let { return it }
            delay(200L)
        }
        return CheckInAccessibilityService.instance
    }

    /** 记录一个阶段到时间线 */
    private fun markPhase(phase: CheckInPhase, note: String = "") {
        _phase.value = phase
        timeline.add(TimelineEntry(phase = phase, at = System.currentTimeMillis(), note = note))
        Timber.d("阶段: %s %s", phase, note)
    }

    /** 落盘执行记录 */
    private suspend fun persist(request: CheckInRequest, result: CheckInResult) {
        val record = CheckInRecord(
            id = request.dedupeKey,
            kind = request.kind,
            planId = request.planId,
            scheduledAt = request.scheduledAt,
            startedAt = request.startedAt,
            finishedAt = System.currentTimeMillis(),
            result = result,
            message = describeResult(result),
            timeline = timeline.toList(),
        )
        recordRepository.save(record)
        Timber.i("打卡结束: %s / %s", request.dedupeKey, result.name)
    }

    private fun describeResult(result: CheckInResult): String = when (result) {
        CheckInResult.SUCCESS -> "打卡成功"
        CheckInResult.ALREADY_DONE -> "今日已打卡，无需重复"
        CheckInResult.ENTRY_NOT_FOUND -> "未能在飞书中找到打卡入口，可能方案需要更新"
        // 与上一条分开描述，因为**用户要做的事完全不同**：
        // 上面是"方案里的选择器不对"，这里是"你配的链接不对"
        CheckInResult.DEEPLINK_LANDING_MISMATCH ->
            "深链跳转后未落在考勤页，请在设置页检查打卡入口链接"
        CheckInResult.DEEPLINK_UNRESOLVED ->
            "深链无法打开（格式错误或飞书未安装）"
        CheckInResult.NETWORK_ERROR -> "网络异常"
        CheckInResult.DEVICE_LOCKED -> "设备处于锁屏，无法自动解锁"
        CheckInResult.PERMISSION_MISSING -> "缺少必要权限"
        CheckInResult.SKIPPED_BUSY -> "已有打卡任务在执行"
        CheckInResult.FAILURE -> "未能确认打卡结果，请手动核实"
    }

    private companion object {
        /** 拉起飞书后等待界面切换 */
        const val LAUNCH_SETTLE_MS = 1_500L

        /** 点击导航时每个步骤的默认超时（方案里没单独指定时用） */
        const val STEP_TIMEOUT_MS = 8_000L

        /** 等待无障碍服务连接的最长时间 */
        const val SERVICE_WAIT_MS = 8_000L

        /** 确认打卡结果的轮询间隔 */
        const val VERIFY_POLL_MS = 500L

        /** 确认打卡结果的超时 */
        const val VERIFY_TIMEOUT_MS = 10_000L

        /** 重试间隔（与设置页文案「每次间隔 1 分钟」保持一致） */
        const val RETRY_INTERVAL_MS = 60_000L
    }
}

/**
 * 空实现的深链发送器。
 *
 * 当引擎没有拿到真正的发送器时使用（纯 JVM 单测、或刻意关闭深链）。
 * 它把每次跳转都报成「无接收方」，于是 [EntryOpener] 会自然地
 * 退回到点击导航 —— 这正是「不配深链时行为与升级前一致」这条要求的实现。
 *
 * 之所以用一个显式的对象而不是把 `deepLinkLauncher` 的类型
 * 直接敞开成可空、在 [EntryOpener] 里反复判空：
 * 判空散落在多处容易漏，而空对象模式只在一处兜底。
 */
private object NoopDeepLinkLauncher : DeepLinkSender {
    override fun launch(url: String, packageName: String?): DeepLinkResult =
        DeepLinkResult.NoHandler(url)
}
