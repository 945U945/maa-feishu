package com.feishu.checkin.core.device

import com.feishu.checkin.checkin.model.AppLinkEntry
import com.feishu.checkin.checkin.model.EntrySource
import com.feishu.checkin.checkin.model.EntryStrategy
import timber.log.Timber

/**
 * 「打开打卡入口」这一步的结论。
 *
 * ## 为什么这一步需要单独的结论类型
 *
 * 老实现里这一步只有一个 Boolean（点击导航走通没走通），
 * 于是所有失败都被压成 `ENTRY_NOT_FOUND` —— 而用户看到这个提示
 * 完全不知道该做什么：是链接填错了？飞书没装？还是方案过期了？
 *
 * 新实现把「用哪种方式打开的」和「打开后落在哪」都带出来，
 * 因为**这两条信息决定用户下一步该干什么**。
 */
sealed interface EntryOpening {

    /**
     * 已成功把飞书带到考勤页（或至少带到了应出现落点关键字的地方）。
     *
     * @param via 用哪种方式成功的
     * @param detail 诊断细节（写进执行时间线）
     */
    data class Opened(val via: EntrySource, val detail: String) : EntryOpening

    /**
     * 深链发出去了，但校验发现没落在考勤页。
     *
     * 按用户选择（「直接报失败并提示我」），这里**不自动回退**到点击导航 ——
     * 落点不符通常意味着链接本身配错了，继续往下点只会在错误的页面上乱操作。
     *
     * @param diagnostic 可直接展示给用户的诊断文本
     */
    data class Misplaced(val diagnostic: String) : EntryOpening

    /** 深链根本没发出去（格式错误、飞书未安装、无接收方） */
    data class Unresolved(val reason: String) : EntryOpening

    /** 没有任何可用手段（未配置链接、无快捷方式、且禁用点击导航） */
    data class NoEntry(val reason: String) : EntryOpening
}

/**
 * 打卡入口打开器 —— 新入口策略的执行者。
 *
 * ## 三级降级，顺序由 [EntryStrategy] 决定
 *
 * ```
 * ① 用户自定义深链（最精确，最贴合企业配置）
 *      ↓ 失败或无配置
 * ② 桌面快捷方式 / 内置候选深链（零配置兜底）
 *      ↓ 失败或无配置
 * ③ 传统点击导航（最笨但兼容性最好）
 * ```
 *
 * 关键设计：**每一级成功后立刻做落点校验**，通过就停。
 * 不做「先全部试一遍再看结果」—— 深链跳转是有副作用的
 * （会改变前台页面），叠加执行会让诊断信息互相污染。
 *
 * ## 为什么落点不符时不继续降级
 *
 * 这是用户明确选的行为（「直接报失败并提示我」）。
 * 理由：链接配错是**确定性**问题，自动回退到点击导航会让
 * 「用户以为自己配的链接在起作用」—— 于是错误被掩盖，
 * 直到某天点击导航也失效了，用户拿到的是一堆互相矛盾的日志。
 *
 * 报失败并把「当前落在哪」说清楚，用户改一次链接就好了。
 *
 * ## 依赖都以 lambda 注入
 *
 * [launcher] / [verifier] 之外的部分（读设置、读页面文案）都以 lambda 传入，
 * 这样本类可以在纯 JVM 单测里完整驱动，不需要真机、不需要 Robolectric。
 */
class EntryOpener(
    /**
     * 链接发送器。
     *
     * 依赖 [DeepLinkSender] 接口而不是 [DeepLinkLauncher] 具体类 ——
     * 后者持有 Context，在纯 JVM 单测里无法构造（Android mock jar
     * 对框架构造器抛 `Stub!`）。见 [DeepLinkSender] 的注释。
     */
    private val launcher: DeepLinkSender,
    private val verifier: LandingVerifier,
    /**
     * 传统点击导航的执行体。
     *
     * 返回 true 表示已经到达考勤页。由引擎注入
     * （它需要无障碍服务实例，那东西只有引擎拿得到）。
     */
    private val clickNavigation: suspend () -> Boolean,
) {

    /**
     * 打开打卡入口。
     *
     * @param strategy      入口策略（决定尝试顺序）
     * @param entries       按优先级排好序的深链候选（用户自定义在前）
     * @param planKeywords  方案里配置的「应出现文案」，落点校验用
     * @param feishuPackage 飞书包名，用于锁定跳转接收方
     * @param readPageText  读当前界面可见文本（无障碍提供）
     */
    suspend fun open(
        strategy: EntryStrategy,
        entries: List<AppLinkEntry>,
        planKeywords: List<String>,
        feishuPackage: String?,
        readPageText: suspend () -> String,
    ): EntryOpening {
        val usable = entries.filter { it.isUsable }

        Timber.i(
            "打开入口: 策略=%s, 可用深链=%d, 落点关键字=%d",
            strategy, usable.size, planKeywords.size,
        )

        return when (strategy) {
            EntryStrategy.CLICK_ONLY -> clickOnly(strategy)

            EntryStrategy.DEEP_LINK_FIRST,
            EntryStrategy.AUTO,
            -> deepLinkFirst(strategy, usable, planKeywords, feishuPackage, readPageText)

            EntryStrategy.SHORTCUT_FIRST -> {
                // 目前快捷方式的探测手段受限（各厂商 Launcher 实现不同），
                // 所以 SHORTCUT_FIRST 与 DEEP_LINK_FIRST 走同一套候选列表 ——
                // 桌面快捷方式在候选列表里以来源标记体现。
                // 保留这个枚举值是为了未来接入真正的快捷方式枚举后不必改协议。
                deepLinkFirst(strategy, usable, planKeywords, feishuPackage, readPageText)
            }
        }
    }

    /**
     * 深链优先的路径。
     *
     * 逐个候选尝试，**每个候选成功后立即校验落点**：
     * - 落点正确 → 结束，返回 Opened
     * - 落点不符 → 结束，返回 Misplaced（按用户选择，不继续降级）
     * - 深链根本没发出去 → 继续下一个候选
     *
     * 注意「落点不符」和「深链没发出去」的处理不同：
     * 前者说明链接**可用但指错地方**，继续试别的没有意义
     * （配置问题，候选都是同一个来源）；
     * 后者说明这条链接**在当前设备上不可用**，换下一条是合理的。
     */
    private suspend fun deepLinkFirst(
        strategy: EntryStrategy,
        entries: List<AppLinkEntry>,
        planKeywords: List<String>,
        feishuPackage: String?,
        readPageText: suspend () -> String,
    ): EntryOpening {
        if (entries.isEmpty()) {
            Timber.i("没有可用的深链候选，直接走点击导航")
            return clickOnly(strategy)
        }

        var lastUnresolved: String? = null

        for (entry in entries) {
            Timber.i("尝试深链候选 [%s] %s", entry.source, entry.url.take(120))

            val result = runCatching { launcher.launch(entry.url, feishuPackage) }
                .onFailure { Timber.w(it, "跳转异常: %s", entry.url.take(80)) }
                .getOrElse { DeepLinkResult.Rejected(entry.url, it.message ?: "未知异常") }

            when (result) {
                is DeepLinkResult.NoHandler -> {
                    lastUnresolved = "没有应用能打开这条链接（飞书未安装或链接格式不受支持）"
                    Timber.w("深链无接收方，尝试下一个候选")
                    continue
                }

                is DeepLinkResult.Rejected -> {
                    lastUnresolved = result.reason
                    Timber.w("深链被拒绝: %s", result.reason.take(160))
                    continue
                }

                is DeepLinkResult.Launched -> {
                    // 跳转已发出，等页面稳定后校验落点
                    val verdict = settleAndVerify(planKeywords, readPageText)

                    when (verdict) {
                        is LandingVerdict.OnTarget ->
                            return EntryOpening.Opened(
                                via = entry.source,
                                detail = "深链命中：${verdict.evidence}",
                            )

                        is LandingVerdict.OffTarget ->
                            // 用户选择：直接报失败，不回退
                            return EntryOpening.Misplaced(verifier.describeFailure(verdict))

                        is LandingVerdict.Inconclusive -> {
                            // 证据不足时**按乐观处理**：深链机制本身成功了，
                            // 只是读不到证据（比如没开 Shizuku 且方案没配关键字）。
                            // 报失败会让「本来能用」的配置被误判 ——
                            // 后续的点击打卡环节仍然有兜底判断
                            // （找不到打卡按钮会返回 ENTRY_NOT_FOUND，不会静默点错）
                            Timber.w("落点无法判定，按乐观继续: %s", verdict.reason)
                            return EntryOpening.Opened(
                                via = entry.source,
                                detail = "深链已跳转（落点无法判定：${verdict.reason}）",
                            )
                        }
                    }
                }
            }
        }

        // 所有候选都没能发出去 —— 这是「链接不可用」而非「链接配错」
        Timber.w("所有深链候选均不可用: %s", lastUnresolved)
        return EntryOpening.Unresolved(
            lastUnresolved ?: "所有入口链接均无法打开",
        )
    }

    /**
     * 跳转后等待页面稳定，再校验落点。
     *
     * 等待是必需的：`am start` 返回只代表**跳转请求被受理**，
     * 页面切换还要几百毫秒。立刻校验会读到旧页面，
     * 得到「还在桌面」这种假失败。
     *
     * 采用「轮询直到读到页面内容或超时」而不是固定 sleep ——
     * 设备快时立刻通过，慢时也不会误判。
     */
    private suspend fun settleAndVerify(
        planKeywords: List<String>,
        readPageText: suspend () -> String,
    ): LandingVerdict {
        val deadline = System.currentTimeMillis() + LANDING_SETTLE_MS
        var lastVerdict: LandingVerdict = LandingVerdict.Inconclusive("尚未读取界面")

        while (System.currentTimeMillis() < deadline) {
            val text = runCatching { readPageText() }
                .onFailure { Timber.v(it, "读取界面文本失败") }
                .getOrDefault("")

            lastVerdict = verifier.verify(planKeywords, text)

            // 已能判定就立刻返回，不等满超时
            if (lastVerdict !is LandingVerdict.Inconclusive) {
                return lastVerdict
            }

            kotlinx.coroutines.delay(LANDING_POLL_MS)
        }

        return lastVerdict
    }

    /**
     * 传统点击导航。
     *
     * 这是最后一道防线，也是唯一**不依赖任何配置**的手段。
     * 它慢、脆，但兼容所有版本 —— 所以必须保留。
     */
    private suspend fun clickOnly(strategy: EntryStrategy): EntryOpening {
        Timber.i("使用传统点击导航（策略 %s）", strategy)
        val ok = runCatching { clickNavigation() }
            .onFailure { Timber.w(it, "点击导航异常") }
            .getOrDefault(false)

        return if (ok) {
            EntryOpening.Opened(
                via = EntrySource.BUILT_IN,
                detail = "通过界面点击导航到达考勤页",
            )
        } else {
            EntryOpening.NoEntry("点击导航未能到达考勤页")
        }
    }

    private companion object {
        /** 跳转后等待落点稳定的窗口 */
        const val LANDING_SETTLE_MS = 6_000L

        /** 落点轮询间隔 */
        const val LANDING_POLL_MS = 400L
    }
}
