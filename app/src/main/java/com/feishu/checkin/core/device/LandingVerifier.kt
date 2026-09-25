package com.feishu.checkin.core.device

import com.feishu.checkin.checkin.model.EntryFormats
import timber.log.Timber

/**
 * 落点判定结论。
 *
 * 三态而不是布尔：因为「确定跳对了」「确定跳错了」「无法确定」
 * 需要三种不同处理。用布尔会逼着把「无法确定」硬塞进某一侧，
 * 而这两个方向都会出事：
 * - 塞进「对了」→ 可能在错误页面上乱点
 * - 塞进「错了」→ 明明成功却报失败，用户失去信任
 */
sealed interface LandingVerdict {
    /** 确认落在考勤页 */
    data class OnTarget(val evidence: String) : LandingVerdict

    /** 确认没落在考勤页 */
    data class OffTarget(val evidence: String, val currentPage: String?) : LandingVerdict

    /** 证据不足，无法判定 */
    data class Inconclusive(val reason: String) : LandingVerdict
}

/**
 * 落点校验器。
 *
 * ## 解决的问题
 *
 * 发出一条深链后，**必须确认它真的落在了考勤页**，否则后续点击
 * 就是在错误的页面上乱操作 —— 后果可能很严重（比如在聊天页误点发送）。
 *
 * ## 两种证据，双保险
 *
 * ### 证据一：前台 Activity 名（需要 Shizuku）
 *
 * `dumpsys activity` 读到的 Activity 完整类名是不会骗人的。
 * 但它有个盲区：**飞书内部大量使用单 Activity + Fragment 架构**，
 * 考勤页可能和首页是同一个 Activity 的不同 Fragment。
 * 这种情况下 Activity 名无法区分页面。
 *
 * ### 证据二：界面文案（用无障碍读）
 *
 * 无障碍能读到真实控件树，考勤页会有「上班打卡」「下班打卡」
 * 这类特征文案。它的盲区是：文案可能被版本更新改掉。
 *
 * ### 为什么要同时看两个
 *
 * 单独任一都有盲区，但盲区**不重叠**：
 * - Activity 名分不出 Fragment → 文案能分
 * - 文案被改 → Activity 名不受影响
 *
 * 所以判定逻辑是：
 * - 任一证据**明确指向考勤页** → OnTarget（乐观，因为两个都是强证据）
 * - Activity 名**明确指向非考勤页**（如落在浏览器）→ OffTarget
 * - 两个都拿不到 → Inconclusive，由调用方按用户设定处理
 *
 * ## 关于浏览器
 *
 * 落到浏览器是必须**明确识别**的一种失败 ——
 * 它说明链接根本没唤起飞书，用户需要换链接，
 * 而不是去调方案里的控件选择器。
 */
class LandingVerifier(
    /**
     * 读当前前台 Activity。
     *
     * 以 lambda 注入，无 Shizuku 时传 null。
     * 返回 null 表示读不到（而非"没有前台页面"）。
     */
    private val foregroundReader: (() -> String?)? = null,
) {

    /**
     * 校验落点。
     *
     * @param landingKeywords 方案定义的"应出现文案"
     * @param pageText 当前界面上的可见文本（无障碍提供，可为空串）
     * @param landedUrl 若系统报告了当前页面 URL（浏览器场景），用于识别中转页
     */
    fun verify(
        landingKeywords: List<String>,
        pageText: String,
        landedUrl: String? = null,
    ): LandingVerdict {
        val foreground = runCatching { foregroundReader?.invoke() }
            .onFailure { Timber.v(it, "读取前台 Activity 失败") }
            .getOrNull()

        Timber.d("落点校验: foreground=%s, url=%s", foreground, landedUrl)

        // ── 证据零：明显的失败信号 ──

        // 跳到浏览器中转页 —— 链接没唤起飞书，必须明确报错
        if (EntryFormats.looksLikeFallbackPage(landedUrl)) {
            return LandingVerdict.OffTarget(
                evidence = "落在飞书 AppLink 下载/中转网页，说明链接未唤起飞书客户端",
                currentPage = landedUrl,
            )
        }
        if (isBrowser(foreground)) {
            return LandingVerdict.OffTarget(
                evidence = "落在浏览器（$foreground），链接未能唤起飞书",
                currentPage = foreground,
            )
        }

        // ── 证据一：Activity 名 ──

        val activitySaysTarget = foreground != null && looksLikeAttendanceActivity(foreground)
        val activitySaysFeishu = foreground != null && isFeishu(foreground)

        // ── 证据二：界面文案 ──

        val textSaysTarget = landingKeywords.isNotEmpty() &&
            landingKeywords.any { kw -> kw.isNotEmpty() && pageText.contains(kw) }

        // ── 综合判定 ──

        if (activitySaysTarget && textSaysTarget) {
            return LandingVerdict.OnTarget("Activity 名与界面文案双重确认: $foreground")
        }
        if (activitySaysTarget) {
            return LandingVerdict.OnTarget("Activity 名确认落在考勤页: $foreground")
        }
        if (textSaysTarget) {
            return LandingVerdict.OnTarget("界面文案确认落在考勤页（命中方案关键字）")
        }

        // 明确不是飞书 —— 跳转到别的地方去了
        if (foreground != null && !activitySaysFeishu && !isLauncher(foreground)) {
            return LandingVerdict.OffTarget(
                evidence = "前台应用不是飞书: $foreground",
                currentPage = foreground,
            )
        }

        // 在飞书里但不在考勤页 —— 这是最典型的情况：
        // 链接打开了飞书但没跳到考勤（比如过期链接落回首页）
        if (activitySaysFeishu) {
            return LandingVerdict.OffTarget(
                evidence = "已在飞书内，但页面特征不符合考勤页（可能链接指向首页或其他应用）",
                currentPage = foreground,
            )
        }

        // 桌面（说明跳转没发生）也算明确失败
        if (isLauncher(foreground)) {
            return LandingVerdict.OffTarget(
                evidence = "仍停留在桌面，跳转未生效",
                currentPage = foreground,
            )
        }

        return LandingVerdict.Inconclusive(
            reason = "无法读取前台 Activity，且界面文案未命中方案关键字" +
                if (landingKeywords.isEmpty()) "（方案未配置落点关键字）" else "",
        )
    }

    /**
     * 是否飞书系应用。
     *
     * 接收可空值并与 null 比较 —— 语义上「读不到前台组件」
     * 当然不构成「是飞书」，返回 false 是正确且自然的，
     * 强求调用方先判空只会让逻辑更啰嗦。
     */
    private fun isFeishu(component: String?): Boolean {
        if (component == null) return false
        val lower = component.lowercase()
        return lower.contains("com.ss.android.lark") ||
            lower.contains("com.larksuite.suite") ||
            // 飞书内部大量组件走这个包名
            lower.contains("com.ss.android.lark.meego")
    }

    /**
     * Activity 名是否像考勤页。
     *
     * 关键词来自飞书的命名习惯。注意这里**只做粗筛** ——
     * 真实类名无法预先穷举，所以宁可漏判（退回文案判定）
     * 也不要误判（把非考勤页认成考勤页）。
     */
    private fun looksLikeAttendanceActivity(component: String): Boolean {
        val lower = component.lowercase()
        val keywords = listOf(
            "attendance",
            "checkin",
            "check_in",
            "punch",
            "kaoqin",
            "attendanceweb",
        )
        return keywords.any { lower.contains(it) }
    }

    /**
     * 是否处于桌面。
     *
     * 桌面意味着跳转根本没发生 —— 这是个明确的失败信号，
     * 比"无法判定"更有信息量。
     */
    private fun isLauncher(component: String?): Boolean {
        if (component == null) return false
        val lower = component.lowercase()
        val launchers = listOf(
            "launcher", "home", "nexuslauncher", "touchwiz",
            "miui.home", "oppo.launcher", "vivo.launcher",
            "huawei.android.launcher", "smartisanos.launcher",
        )
        return launchers.any { lower.contains(it) }
    }

    /** 是否处于浏览器 */
    private fun isBrowser(component: String?): Boolean {
        if (component == null) return false
        val lower = component.lowercase()
        val browsers = listOf(
            "chrome", "browser", "firefox", "webkit", "webview",
            "mtt", "ucmobile", "quark", "sogou", "baidu.searchbox",
            "miui.browser", "heytapbrowser", "vivo.browser",
        )
        return browsers.any { lower.contains(it) }
    }

    /**
     * 生成面向用户的诊断说明。
     *
     * 失败时给的信息**必须能指导下一步动作** ——
     * 只说"落点不对"等于没说。这里把「当前在哪」和「该怎么办」
     * 都说清楚。
     */
    fun describeFailure(verdict: LandingVerdict.OffTarget): String = buildString {
        append("深链跳转后未落在考勤页。")
        append("\n")
        append("证据: ").append(verdict.evidence)
        if (verdict.currentPage != null) {
            append("\n当前页面: ").append(verdict.currentPage)
        }
        append("\n")
        append(
            when {
                verdict.evidence.contains("浏览器") || verdict.evidence.contains("中转") ->
                    "建议：链接可能失效或飞书版本过低，请在飞书里重新复制考勤应用的链接。"

                verdict.evidence.contains("桌面") ->
                    "建议：检查链接格式是否正确，或确认飞书已安装。"

                verdict.evidence.contains("已在飞书内") ->
                    "建议：这条链接可能指向飞书首页而非考勤应用，" +
                        "请确认复制的是「考勤打卡」应用的链接（长按应用 → 分享 → 复制链接）。"

                else -> "建议：在设置页检查打卡入口链接是否正确。"
            },
        )
    }
}
