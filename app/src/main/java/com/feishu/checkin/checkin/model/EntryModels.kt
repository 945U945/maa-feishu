package com.feishu.checkin.checkin.model

import kotlinx.serialization.Serializable

/**
 * 打卡入口的定位方式。
 *
 * ## 为什么要重新设计这一步
 *
 * 最初的做法是纯无障碍点击导航：拉起飞书 → 点「工作台」→ 下滑找考勤
 * → 点进去。这条路**能work但极其脆弱**：
 *
 * - 「工作台」tab 的文案、位置、是否叫这个名字，各企业各版本都不同
 * - 考勤应用在工作台里的位置取决于企业管理员配置，可能在第 3 屏
 * - 飞书每次改版都可能让某一步失效，而失效后用户只会看到
 *   「未找到打卡入口」，完全不知道该改什么
 *
 * 实测下来，这条路径的失败率随飞书版本迭代**单调上升** ——
 * 每修一次，下次改版又断。
 *
 * ## 新思路：把「找入口」从点击改成跳转
 *
 * 飞书工作台的每个应用都有对应的 **AppLink 深链**，
 * 用户在飞书里长按应用 → 分享 → 复制链接就能拿到。
 * 拿到链接后，一条 Intent 就能直接落到考勤页：
 *
 *     拉起飞书（或不用拉起）→ 跳转链接 → 落在考勤页 → 点打卡按钮
 *
 * 这样**绕过了整条导航链**，只剩最后「点按钮」一步还需要无障碍 ——
 * 而这一步是无论如何省不掉的（点按钮就是操作本身）。
 *
 * ## 三级降级
 *
 * [EntryStrategy] 描述按什么顺序尝试各种入口方式。
 * 先试最精确的（用户自己复制的链接），再试次优的（桌面快捷方式），
 * 最后才退回最笨但兼容性最好的传统点击导航。
 */
@Serializable
data class AppLinkEntry(
    /**
     * 链接来源。
     *
     * 区分来源是为了在失败时给出**不同的提示**：
     * 用户自定义链接错了要提示「请重新复制」；
     * 内置候选全失败要提示「请手动配置链接」。
     */
    val source: EntrySource,

    /** 链接本身 */
    val url: String,

    /** 便于用户辨认的名称（如「XX企业考勤打卡」） */
    val label: String = "",
) {
    /**
     * 是否为合法可用的链接。
     *
     * 校验比较宽松 —— 只认协议头与宿主，**不校验路径**。
     * 原因：不同企业的考勤应用 AppLink 路径千差万别
     * （有 /client/op/open、有自建应用的各种 path），
     * 强行校验路径会把合法链接挡在门外，得不偿失。
     *
     * 而「路径对不对」这件事由**落点校验**来兜底（见
     * [CheckInPlan.landingKeywords]）—— 跳过去发现不是考勤页，
     * 比在配置阶段猜路径准得多。
     */
    val isUsable: Boolean
        get() = url.isNotBlank() && EntryFormats.looksLikeFeishuLink(url)
}

/** 入口来源 */
@Serializable
enum class EntrySource {
    /** 用户自己粘贴的链接 —— 最高优先级，最贴合其企业配置 */
    USER,

    /** 应用内置的候选链接 —— 零配置兜底 */
    BUILT_IN,

    /** 桌面快捷方式图标 */
    SHORTCUT,
}

/**
 * 入口定位策略：决定按什么顺序尝试进入考勤页。
 *
 * ## 顺序设计的依据是「哪个更可能成功 + 失败了怎么排查」
 *
 * 从上到下，成功可能性递减，但排查难度也递减：
 *
 * 1. [DEEP_LINK_FIRST]（默认）
 *    用户配的链接最贴合其企业，成功率最高。
 *    失败时的原因也最明确（链接不对），报错能直接指导行动。
 *
 * 2. [SHORTCUT_FIRST]
 *    桌面快捷方式不需要用户配置任何东西，但**依赖用户手动创建过**，
 *    且不同厂商 Launcher 对快捷方式的存储方式不同，探测不一定成功。
 *
 * 3. [CLICK_ONLY]
 *    完全退回传统点击导航。最笨、最慢、最易碎，
 *    但**不依赖任何额外配置**，是最后的保底。
 */
@Serializable
enum class EntryStrategy {
    /** 深链 → 快捷方式 → 点击导航 */
    AUTO,

    /** 优先深链，失败后依次退 */
    DEEP_LINK_FIRST,

    /** 优先桌面快捷方式 */
    SHORTCUT_FIRST,

    /** 只用传统点击导航（不使用任何新入口手段） */
    CLICK_ONLY,
}

/**
 * 链接格式识别。
 *
 * 抽成 object 而不是散在调用处，是因为这套判断既要在保存时校验
 * （阻止用户填错），又要在执行时兜底（旧数据可能不合规），
 * 两处必须用同一套规则，否则会出现「能存进去但跳不出去」的怪现象。
 */
object EntryFormats {

    /**
     * 飞书 AppLink 的官方宿主。
     *
     * 官方文档给出的形式是 `https://applink.feishu.cn/client/xxx/open`。
     * 注意还有两个变体必须一并支持：
     * - 品牌定制版 / 私有化部署会有自己的域名（形如 `xxx.feishu.cn`）
     * - Lark 国际版用 `applink.larksuite.com`
     */
    private val HTTPS_HOSTS = listOf(
        "applink.feishu.cn",
        "applink.larksuite.com",
        ".feishu.cn",
        ".larksuite.com",
        ".f.mioffice.cn",
    )

    /**
     * scheme 形式的降级协议。
     *
     * 官方文档明确提到：在飞书外部用 `feishu://applink/...` 这种写法时，
     * 会尝试直接打开飞书，**不会**在失败时跳到 AppLink 网页。
     * 这对自动化反而是好事 —— 网页中转会让流程卡在浏览器里。
     */
    private val DEEP_SCHEMES = listOf(
        "feishu://",
        "lark://",
        "larksuite://",
        "sslocal://",
    )

    /** 判断一条链接看起来是否像飞书深链 */
    fun looksLikeFeishuLink(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return false
        val lower = trimmed.lowercase()

        if (DEEP_SCHEMES.any { lower.startsWith(it) }) return true

        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return false
        }
        // 去掉协议头后取宿主部分
        val host = lower
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .substringBefore('?')

        return HTTPS_HOSTS.any { host == it || host.endsWith(it) }
    }

    /**
     * 把 https 形式转成 scheme 形式，作为跳转失败的降级。
     *
     * `https://applink.feishu.cn/client/op/open` →
     * `feishu://applink/client/op/open`
     *
     * 官方文档中的例子正是这个映射关系，所以转换是安全的。
     * 已经是 scheme 形式的原样返回。
     *
     * 注意 Lark 国际版要转成 `lark://`，不能一律转 `feishu://` ——
     * 装的是 Lark 而发 `feishu://` 会无人接收。
     */
    fun toSchemeForm(url: String): String? {
        val trimmed = url.trim()
        val lower = trimmed.lowercase()

        if (DEEP_SCHEMES.any { lower.startsWith(it) }) return trimmed

        if (!lower.startsWith("https://") && !lower.startsWith("http://")) {
            return null
        }

        val withoutProtocol = trimmed
            .removePrefix("https://").removePrefix("http://")
            .removePrefix("HTTPS://").removePrefix("HTTP://")

        val scheme = if (lower.contains("larksuite.com")) "lark://" else "feishu://"

        // 官方链接形如 applink.feishu.cn/client/op/open，
        // 对应 scheme 形式 feishu://applink/client/op/open ——
        // 即把宿主里的「applink」挪到 scheme 之后当第一段路径。
        val host = withoutProtocol.substringBefore('/')
        val pathAndQuery = withoutProtocol.removePrefix(host)

        return if (host.startsWith("applink.")) {
            "$scheme applink$pathAndQuery".replace(" ", "")
        } else {
            // 非标准宿主（如企业自有域名）没有对应的 scheme 形式，
            // 强行拼会得到一条打不开的链接 —— 不如老实返回 null
            null
        }
    }

    /**
     * 判断浏览器中转页的特征。
     *
     * 在飞书外部打开 AppLink 时，若飞书未安装或无法直接唤起，
     * 系统会落到飞书的**下载/中转网页**。
     * 此时界面是浏览器而不是飞书，必须识别出来并明确报错 ——
     * 否则会一直等「考勤页出现」直到超时，报一个含糊的失败。
     */
    fun looksLikeFallbackPage(url: String?): Boolean {
        if (url == null) return false
        val lower = url.lowercase()
        return lower.contains("applink.feishu.cn/") &&
            (lower.contains("/download") || lower.contains("/redirect") ||
                lower.contains("lk_unique") || lower.contains("open_app"))
    }
}
