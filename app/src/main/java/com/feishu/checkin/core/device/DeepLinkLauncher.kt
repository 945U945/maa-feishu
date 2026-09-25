package com.feishu.checkin.core.device

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.feishu.checkin.checkin.model.EntryFormats
import timber.log.Timber

/**
 * 跳转结果。
 *
 * 不返回 Boolean 是因为**失败的原因决定了用户该做什么**：
 * - 没应用能处理 → 检查飞书是否安装
 * - 跳到浏览器中转页 → 链接无效或飞书版本过低
 * - 抛异常 → 系统层面被拦截
 *
 * 用 Boolean 会把这些全压成「失败」，用户只能干瞪眼。
 */
sealed interface DeepLinkResult {
    /** 已成功发出跳转请求 */
    data class Launched(val usedUrl: String, val byShizuku: Boolean) : DeepLinkResult

    /** 没有任何应用能处理这个链接 */
    data class NoHandler(val url: String) : DeepLinkResult

    /** 跳转被系统或飞书拒绝 */
    data class Rejected(val url: String, val reason: String) : DeepLinkResult
}

/**
 * 「把一条链接发出去」这个能力的抽象。
 *
 * ## 为什么要抽出接口而不是直接用 [DeepLinkLauncher]
 *
 * [DeepLinkLauncher] 的构造需要一个真实的 `Context`，而
 * Android 的 mock `android.jar` 在 JVM 单测里对任何框架方法
 * （包括构造器）都抛 `Stub!` —— 这意味着**任何持有 Context 的类
 * 都无法在纯 JVM 测试里被构造**。
 *
 * 抽出接口后，[EntryOpener] 只依赖这一个方法，
 * 测试里给一个普通 Kotlin 对象即可完整驱动所有分支 ——
 * 不需要 Robolectric、不需要真机。
 *
 * 这也让「Shizuku 可选、深链可选」在类型上更清晰：
 * 需要的只是「发一条链接」的能力，而不是某个具体实现。
 */
interface DeepLinkSender {
    fun launch(url: String, packageName: String?): DeepLinkResult
}

/**
 * 深链启动器。
 *
 * ## 为什么值得单独做一层
 *
 * 直接 `startActivity(Intent(ACTION_VIEW, uri))` 在真机上有三个坑：
 *
 * ### 1. 会跳到浏览器而不是飞书
 *
 * 当没有应用能处理 `applink.feishu.cn` 时，系统会回退到浏览器打开
 * 那个 URL —— 而飞书的 AppLink 网页是个**下载引导页**。
 * 结果：自动化流程跑到浏览器里，然后一直等「考勤页出现」直到超时。
 *
 * 对策：跳转前用 `resolveActivity` 检查，优先选**飞书本尊**。
 *
 * ### 2. scheme 形式与 https 形式行为不同
 *
 * 官方文档明确说明：用 `feishu://applink/...` 时，
 * 打不开飞书就**直接失败，不会跳到网页**。
 * 这反而更适合自动化 —— 失败要干脆，不要掉进浏览器。
 *
 * 所以策略是：**先 https（兼容性最好），失败再试 scheme（失败更干脆）**。
 *
 * ### 3. 别家应用可能截胡
 *
 * 多个应用可能都注册了 `feishu://`（比如各种"飞书助手"类工具）。
 * 用 `Intent.setPackage()` 锁死飞书包名，确保只交给飞书。
 *
 * ## Shizuku 增强
 *
 * Shizuku 可用时改用 `am start` 发起跳转：
 * `am start -a android.intent.action.VIEW -d <url> -p <pkg>`
 *
 * 好处是走系统服务而非应用侧解析，不受「哪个应用先注册」影响，
 * 且能拿到更明确的错误输出（应用侧抛的异常往往只有一句话）。
 */
class DeepLinkLauncher(
    private val context: Context,
    /**
     * Shizuku 命令执行器。
     *
     * 以 lambda 注入而不是直接依赖 ShizukuShell ——
     * 这样本类在纯 JVM 环境下可测，也让「Shizuku 是可选」这一点
     * 在类型上就体现出来：没有它传 null 即可。
     */
    private val shizukuExec: ((List<String>) -> String?)? = null,
) : DeepLinkSender {

    /**
     * 打开深链。
     *
     * @param url 目标链接（https 或 scheme 形式）
     * @param packageName 飞书包名，用于锁定接收方；留空则不锁
     */
    override fun launch(url: String, packageName: String?): DeepLinkResult {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            return DeepLinkResult.Rejected(url, "链接为空")
        }

        // ── 优先用 Shizuku 的 am start（更确定） ──
        if (shizukuExec != null) {
            runCatching { launchViaAm(trimmed, packageName) }
                .onSuccess { return it }
                .onFailure { Timber.w(it, "am start 跳转失败，退回应用侧 Intent") }
        }

        // ── 应用侧 Intent ──
        launchViaIntent(trimmed, packageName)?.let { return it }

        // ── https 不行就试 scheme 形式 ──
        val schemeForm = EntryFormats.toSchemeForm(trimmed)
        if (schemeForm != null && schemeForm != trimmed) {
            Timber.i("https 形式未成功，尝试 scheme 形式: %s", schemeForm)
            launchViaIntent(schemeForm, packageName)?.let { return it }
        }

        return DeepLinkResult.NoHandler(trimmed)
    }

    /**
     * 通过 `am start` 跳转。
     *
     * 参数含义：
     * - `-a android.intent.action.VIEW` 视图意图
     * - `-d <url>`                       数据 URI
     * - `-p <package>`                   限定目标包，避免被别家截胡
     *
     * `-p` 是这里的关键：不加的话，装了多个能处理该 scheme 的应用时，
     * 系统可能弹选择框（自动化场景下没人点）或随机选一个。
     *
     * 输出判定：`am start` 成功时会打印 `Starting: Intent {...}`；
     * 失败时打印 `Error:` 或 `Exception`。这里据此判断，
     * 而不是只看退出码 —— am 在部分情况下失败仍返回 0。
     */
    private fun launchViaAm(url: String, packageName: String?): DeepLinkResult {
        val cmd = buildList {
            add("am")
            add("start")
            add("-a")
            add("android.intent.action.VIEW")
            add("-d")
            add(url)
            if (!packageName.isNullOrEmpty()) {
                add("-p")
                add(packageName)
            }
        }

        val output = shizukuExec?.invoke(cmd)
            ?: return DeepLinkResult.Rejected(url, "Shizuku 执行器不可用")

        Timber.d("am start 输出: %s", output.take(300))

        val lower = output.lowercase()
        return when {
            lower.contains("error") || lower.contains("exception") ->
                DeepLinkResult.Rejected(url, output.take(200))

            lower.contains("starting:") || lower.contains("starting intent") ->
                DeepLinkResult.Launched(url, byShizuku = true)

            else -> DeepLinkResult.Rejected(url, "输出无法判定成功: ${output.take(120)}")
        }
    }

    /**
     * 通过应用侧 Intent 跳转。
     *
     * 返回 null 表示「这条路走不通」，由调用方继续尝试下一种形式；
     * 返回非 null 表示已得出结论（成功或确定性失败）。
     */
    private fun launchViaIntent(url: String, packageName: String?): DeepLinkResult? {
        val uri = runCatching { Uri.parse(url) }.getOrNull()
            ?: return DeepLinkResult.Rejected(url, "URL 无法解析")

        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // 关联到已有任务，避免在飞书里堆叠出新页面栈
            addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }

        // 锁定接收方：先试指定包，失败再放开
        val target = resolveTarget(intent, packageName)
        if (target == null) {
            Timber.w("没有应用能处理该链接: %s", url)
            return null
        }
        intent.setPackage(target)

        return runCatching {
            context.startActivity(intent)
            Timber.i("已发起跳转: %s (接收方 %s)", url, target)
            DeepLinkResult.Launched(url, byShizuku = false)
        }.onFailure { t ->
            Timber.w(t, "跳转被拒绝: %s", url)
        }.getOrElse { t ->
            when (t) {
                is ActivityNotFoundException ->
                    DeepLinkResult.Rejected(url, "无 Activity 可处理（飞书未安装？）")
                is SecurityException ->
                    DeepLinkResult.Rejected(url, "系统拒绝：${t.message}")
                else -> DeepLinkResult.Rejected(url, t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /**
     * 决定把 Intent 交给谁。
     *
     * 优先飞书本尊 —— 因为浏览器同样能处理 https 链接，
     * 而落到浏览器就意味着流程跑偏（见类注释）。
     *
     * 返回 null 表示找不到任何接收方。
     */
    private fun resolveTarget(intent: Intent, preferredPackage: String?): String? {
        val pm = context.packageManager

        // 1. 指定包能处理就直接用
        if (!preferredPackage.isNullOrEmpty()) {
            val probe = Intent(intent).setPackage(preferredPackage)
            if (pm.resolveActivity(probe, 0) != null) return preferredPackage
        }

        // 2. 在候选里优先挑飞书包
        val candidates = pm.queryIntentActivities(intent, 0)
        if (candidates.isEmpty()) return null

        val preferred = listOf("com.ss.android.lark", "com.larksuite.suite", "com.ss.android.lark.meego")
        candidates.firstOrNull { c -> preferred.any { it == c.activityInfo.packageName } }
            ?.let { return it.activityInfo.packageName }

        // 3. 退而求其次：任何非浏览器的应用都行
        val browserPackages = listOf(
            "com.android.chrome", "com.android.browser", "org.mozilla.firefox",
            "com.microsoft.emmx", "com.tencent.mtt", "com.UCMobile",
        )
        candidates.firstOrNull { c ->
            browserPackages.none { it == c.activityInfo.packageName }
        }?.let { return it.activityInfo.packageName }

        // 4. 只剩浏览器了。仍然返回它 —— 让跳转发生，
        //    但调用方会通过落点校验发现「跑到了浏览器」而报错，
        //    比在这里静默返回 null 更容易排查
        val browser = candidates.firstOrNull { c ->
            browserPackages.any { it == c.activityInfo.packageName }
        }
        if (browser != null) {
            Timber.w("只能交给浏览器处理，落点校验将会失败")
            return browser.activityInfo.packageName
        }

        return null
    }
}
