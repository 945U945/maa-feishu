package com.feishu.checkin.core.device

import com.feishu.checkin.checkin.model.AppLinkEntry
import com.feishu.checkin.checkin.model.EntrySource
import com.feishu.checkin.checkin.model.EntryStrategy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EntryOpener] 的单元测试。
 *
 * ## 这个类是「新方案能不能用」的关键
 *
 * 它决定按什么顺序尝试入口、什么情况下停、什么情况下继续降级。
 * 尤其两条规则必须有测试锁死：
 *
 * 1. **落点不符时立即停止**（用户明确选的行为）——
 *    不能自动回退到点击导航，否则用户配错的链接会被掩盖
 * 2. **深链发不出去时继续降级** ——
 *    这时用户没做错什么，用旧方案跑通比直接失败更有价值
 *
 * ## 测试手法
 *
 * 用一个记录调用的假启动器 + 一个固定返回的假文案读取器，
 * 完整驱动所有分支。不需要真机、不需要 Shizuku。
 */
class EntryOpenerTest {

    /**
     * 假的深链发送器。
     *
     * 按 url 查预设的返回值，并记录收到了哪些 url ——
     * 「尝试了哪些、以什么顺序尝试」正是这里要验证的东西。
     *
     * 实现 [DeepLinkSender] 接口而不是继承 [DeepLinkLauncher]：
     * 后者需要真实 Context，而 Android mock jar 在 JVM 上
     * 对框架构造器抛 `Stub!`，继承方案根本无法实例化。
     */
    private class FakeLauncher(
        private val responses: Map<String, DeepLinkResult>,
        private val default: DeepLinkResult = DeepLinkResult.NoHandler("unmatched"),
    ) : DeepLinkSender {

        val attempted = mutableListOf<String>()

        override fun launch(url: String, packageName: String?): DeepLinkResult {
            attempted += url
            return responses[url] ?: default
        }
    }

    private fun entry(url: String, source: EntrySource = EntrySource.USER) =
        AppLinkEntry(source = source, url = url)

    private fun opener(
        launcher: DeepLinkSender,
        verifier: LandingVerifier = LandingVerifier(foregroundReader = { null }),
        clickNavResult: Boolean = true,
        clicked: MutableList<Unit>? = null,
    ) = EntryOpener(
        launcher = launcher,
        verifier = verifier,
        clickNavigation = {
            clicked?.add(Unit)
            clickNavResult
        },
    )

    private val keywords = listOf("上班打卡")

    /**
     * 一个「确定落在飞书首页」的校验器 —— 制造 OffTarget。
     *
     * 用它而不是「读不到前台 Activity」，是因为后者会得到
     * [LandingVerdict.Inconclusive]，而 Inconclusive 按乐观处理
     * （见 [EntryOpener.settleAndVerify] 的注释），
     * 无法用来测试「落点不符」这条路径。
     */
    private fun offTargetVerifier(): LandingVerifier =
        LandingVerifier(foregroundReader = { "com.ss.android.lark.main.MainActivity" })

    // ────────────────────── 只有点击导航 ──────────────────────

    @Test
    fun `CLICK_ONLY 策略直接走点击导航`() = runBlocking {
        val launcher = FakeLauncher(emptyMap())
        val clicked = mutableListOf<Unit>()

        val result = opener(launcher, clicked = clicked).open(
            strategy = EntryStrategy.CLICK_ONLY,
            entries = listOf(entry("https://applink.feishu.cn/client/op/open")),
            planKeywords = keywords,
            feishuPackage = "com.ss.android.lark",
            readPageText = { "上班打卡" },
        )

        assertEquals(0, launcher.attempted.size)
        assertEquals(1, clicked.size)
        assertTrue(result is EntryOpening.Opened)
    }

    @Test
    fun `CLICK_ONLY 点击导航失败时返回无入口`() = runBlocking {
        val result = opener(FakeLauncher(emptyMap()), clickNavResult = false).open(
            strategy = EntryStrategy.CLICK_ONLY,
            entries = emptyList(),
            planKeywords = keywords,
            feishuPackage = null,
            readPageText = { "" },
        )

        assertTrue(result is EntryOpening.NoEntry)
    }

    @Test
    fun `无候选时退化为点击导航`() = runBlocking {
        // 用户没配链接、也没装 Shizuku —— 必须还能用旧方案跑
        val clicked = mutableListOf<Unit>()
        val result = opener(FakeLauncher(emptyMap()), clicked = clicked).open(
            strategy = EntryStrategy.AUTO,
            entries = emptyList(),
            planKeywords = keywords,
            feishuPackage = null,
            readPageText = { "上班打卡" },
        )

        assertEquals(1, clicked.size)
        assertTrue(result is EntryOpening.Opened)
    }

    // ────────────────────── 深链成功 ──────────────────────

    @Test
    fun `深链成功且落点正确时返回已打开`() = runBlocking {
        val url = "https://applink.feishu.cn/client/op/open?appId=a"
        val launcher = FakeLauncher(mapOf(url to DeepLinkResult.Launched(url, false)))

        val result = opener(launcher).open(
            strategy = EntryStrategy.DEEP_LINK_FIRST,
            entries = listOf(entry(url)),
            planKeywords = keywords,
            feishuPackage = "com.ss.android.lark",
            readPageText = { "上班打卡\n下班打卡" },
        )

        assertTrue(result is EntryOpening.Opened)
        assertEquals(EntrySource.USER, (result as EntryOpening.Opened).via)
        assertEquals(listOf(url), launcher.attempted)
    }

    @Test
    fun `落点无法判定时按乐观处理`() = runBlocking {
        // 没配关键字、也没 Shizuku —— 读不到任何证据。
        // 这里选择乐观（认为跳转成功）而不是报失败：
        // 报失败会让本来能用的配置被误判，而后续"找不到打卡按钮"
        // 仍然会返回 ENTRY_NOT_FOUND 兜住，不会静默点错
        val url = "https://applink.feishu.cn/client/op/open"
        val launcher = FakeLauncher(mapOf(url to DeepLinkResult.Launched(url, false)))

        val result = opener(launcher).open(
            strategy = EntryStrategy.DEEP_LINK_FIRST,
            entries = listOf(entry(url)),
            planKeywords = emptyList(),
            feishuPackage = null,
            readPageText = { "无关文字" },
        )

        assertTrue(result is EntryOpening.Opened)
    }

    // ────────────────────── 落点不符：立即停止 ──────────────────────

    @Test
    fun `落点不符时立即返回失败且不尝试后续候选`() = runBlocking {
        val first = "https://applink.feishu.cn/client/op/open?appId=wrong"
        val second = "https://applink.feishu.cn/client/op/open?appId=backup"

        val launcher = FakeLauncher(
            mapOf(
                first to DeepLinkResult.Launched(first, false),
                second to DeepLinkResult.Launched(second, false),
            ),
        )

        val result = opener(launcher, verifier = offTargetVerifier()).open(
            strategy = EntryStrategy.DEEP_LINK_FIRST,
            entries = listOf(entry(first), entry(second, EntrySource.BUILT_IN)),
            planKeywords = keywords,
            feishuPackage = null,
            // 落在飞书首页 —— OffTarget
            readPageText = { "" },
        )

        // 关键断言：只尝试了第一条
        assertEquals("落点不符后不应继续尝试下一个候选", listOf(first), launcher.attempted)
        assertTrue(result is EntryOpening.Misplaced)
    }

    @Test
    fun `落点不符时也不回退到点击导航`() = runBlocking {
        // 用户明确选择「直接报失败并提示我」——
        // 自动回退会让用户以为自己配的链接在起作用
        val url = "https://applink.feishu.cn/client/op/open"
        val launcher = FakeLauncher(mapOf(url to DeepLinkResult.Launched(url, false)))
        val clicked = mutableListOf<Unit>()

        val result = opener(launcher, verifier = offTargetVerifier(), clicked = clicked).open(
            strategy = EntryStrategy.DEEP_LINK_FIRST,
            entries = listOf(entry(url)),
            planKeywords = keywords,
            feishuPackage = null,
            readPageText = { "" },
        )

        assertEquals("落点不符时不应调用点击导航", 0, clicked.size)
        assertTrue(result is EntryOpening.Misplaced)
    }

    @Test
    fun `落点不符的诊断信息可直接展示`() = runBlocking {
        val url = "https://applink.feishu.cn/client/op/open"
        val launcher = FakeLauncher(mapOf(url to DeepLinkResult.Launched(url, false)))
        // 前台是浏览器 → OffTarget 且证据明确
        val verifier = LandingVerifier(
            foregroundReader = { "com.android.chrome/.Main" },
        )

        val result = opener(launcher, verifier).open(
            strategy = EntryStrategy.DEEP_LINK_FIRST,
            entries = listOf(entry(url)),
            planKeywords = keywords,
            feishuPackage = null,
            readPageText = { "" },
        )

        val diagnostic = (result as EntryOpening.Misplaced).diagnostic
        assertTrue("诊断应说明证据", diagnostic.contains("浏览器"))
        assertTrue("诊断应给建议", diagnostic.contains("建议"))
    }

    // ────────────────────── 深链发不出去：继续降级 ──────────────────────

    @Test
    fun `第一条深链无接收方时尝试下一条`() = runBlocking {
        val broken = "https://applink.feishu.cn/client/op/open?x=1"
        val good = "https://applink.feishu.cn/client/op/open?x=2"

        val launcher = FakeLauncher(
            mapOf(
                broken to DeepLinkResult.NoHandler(broken),
                good to DeepLinkResult.Launched(good, false),
            ),
        )

        val result = opener(launcher).open(
            strategy = EntryStrategy.DEEP_LINK_FIRST,
            entries = listOf(entry(broken), entry(good, EntrySource.BUILT_IN)),
            planKeywords = keywords,
            feishuPackage = null,
            readPageText = { "上班打卡" },
        )

        assertEquals(listOf(broken, good), launcher.attempted)
        assertTrue(result is EntryOpening.Opened)
        assertEquals(EntrySource.BUILT_IN, (result as EntryOpening.Opened).via)
    }

    @Test
    fun `所有深链都发不出去时返回 Unresolved`() = runBlocking {
        val a = "https://applink.feishu.cn/client/op/open?a"
        val b = "https://applink.feishu.cn/client/op/open?b"

        val launcher = FakeLauncher(
            mapOf(
                a to DeepLinkResult.NoHandler(a),
                b to DeepLinkResult.Rejected(b, "系统拒绝"),
            ),
        )

        val result = opener(launcher).open(
            strategy = EntryStrategy.DEEP_LINK_FIRST,
            entries = listOf(entry(a), entry(b, EntrySource.BUILT_IN)),
            planKeywords = keywords,
            feishuPackage = null,
            readPageText = { "" },
        )

        assertEquals(listOf(a, b), launcher.attempted)
        assertTrue(result is EntryOpening.Unresolved)
    }

    @Test
    fun `格式不合法的候选被跳过`() = runBlocking {
        val launcher = FakeLauncher(emptyMap())

        val result = opener(launcher).open(
            strategy = EntryStrategy.DEEP_LINK_FIRST,
            entries = listOf(
                AppLinkEntry(EntrySource.USER, "not a link"),
                AppLinkEntry(EntrySource.USER, "   "),
            ),
            planKeywords = keywords,
            feishuPackage = null,
            readPageText = { "上班打卡" },
        )

        // 全被过滤掉 → 退化为点击导航 → 成功
        assertEquals("不合法的候选不应被尝试", 0, launcher.attempted.size)
        assertTrue(result is EntryOpening.Opened)
    }

    @Test
    fun `启动器抛异常时按无接收方处理并返回 Unresolved`() = runBlocking {
        // 异常不能向上抛（会让整个打卡流程崩掉），而要转成
        // 「这条链接发不出去」—— 由引擎决定是否降级到点击导航
        // （引擎对 Unresolved 的处理见 CheckInEngine.runPipeline）
        val url = "https://applink.feishu.cn/client/op/open"
        val thrower = object : DeepLinkSender {
            override fun launch(url: String, packageName: String?): DeepLinkResult =
                throw IllegalStateException("binder died")
        }

        val result = opener(thrower).open(
            strategy = EntryStrategy.DEEP_LINK_FIRST,
            entries = listOf(entry(url)),
            planKeywords = keywords,
            feishuPackage = null,
            readPageText = { "" },
        )

        assertTrue("异常应被转成 Unresolved 而不是向上抛", result is EntryOpening.Unresolved)
    }

    @Test
    fun `SHORTCUT_FIRST 策略使用同一套候选列表`() = runBlocking {
        // 快捷方式探测尚未实现（各厂商 Launcher 实现不同），
        // SHORTCUT_FIRST 目前与 DEEP_LINK_FIRST 行为一致。
        // 这条测试把这个"临时一致性"记录下来 ——
        // 未来接入真实快捷方式枚举时，这条会失效并被更新
        val url = "https://applink.feishu.cn/client/op/open"
        val launcher = FakeLauncher(mapOf(url to DeepLinkResult.Launched(url, false)))

        val result = opener(launcher).open(
            strategy = EntryStrategy.SHORTCUT_FIRST,
            entries = listOf(entry(url)),
            planKeywords = keywords,
            feishuPackage = null,
            readPageText = { "上班打卡" },
        )

        assertEquals(listOf(url), launcher.attempted)
        assertTrue(result is EntryOpening.Opened)
    }
}
