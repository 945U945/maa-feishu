package com.feishu.checkin.core.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LandingVerifier] 的单元测试。
 *
 * ## 测试策略
 *
 * [LandingVerifier] 的两个输入源（前台 Activity 名、界面文案）
 * 都以 lambda / 参数注入，所以这里可以完整驱动所有分支 ——
 * 不需要真机、不需要 Shizuku、不需要无障碍服务。
 *
 * 这正是一开始把这些依赖做成可注入的原因：
 * 落点判定是「跳对了还是跳错了」的唯一依据，
 * 判错会导致在错误页面上操作，是最不该出错的一环。
 */
class LandingVerifierTest {

    private val keywords = listOf("上班打卡", "下班打卡")

    /** 构造一个「读不到前台 Activity」的校验器 —— 模拟没装 Shizuku */
    private fun noActivity(): LandingVerifier = LandingVerifier(foregroundReader = { null })

    /** 构造一个「前台是给定组件」的校验器 */
    private fun at(component: String): LandingVerifier =
        LandingVerifier(foregroundReader = { component })

    // ────────────────────── OnTarget ──────────────────────

    @Test
    fun `Activity 名与文案双确认时判定在目标页`() {
        val verifier = at("com.ss.android.lark.attendance.ui.AttendanceActivity")
        val verdict = verifier.verify(keywords, "上班打卡\n下班打卡")

        assertTrue(verdict is LandingVerdict.OnTarget)
    }

    @Test
    fun `仅 Activity 名像考勤页也判定在目标页`() {
        // 飞书可能改了文案但类名没变
        val verifier = at("com.ss.android.lark.attendance.AttendanceActivity")
        val verdict = verifier.verify(keywords, "")

        assertTrue(verdict is LandingVerdict.OnTarget)
    }

    @Test
    fun `仅文案命中就判定在目标页`() {
        // 飞书大量用单 Activity + Fragment，类名分不出页面 ——
        // 这时文案是唯一证据，必须认
        val verifier = noActivity()
        val verdict = verifier.verify(keywords, "上班打卡\n下班打卡\n外勤")

        assertTrue(verdict is LandingVerdict.OnTarget)
    }

    @Test
    fun `命中任一关键字即可`() {
        val verifier = noActivity()
        assertTrue(verifier.verify(keywords, "下班打卡") is LandingVerdict.OnTarget)
        assertTrue(verifier.verify(keywords, "上班打卡") is LandingVerdict.OnTarget)
    }

    // ────────────────────── OffTarget：浏览器 / 中转页 ──────────────────────

    @Test
    fun `落在浏览器时判定失败`() {
        val verifier = at("com.android.chrome/com.google.android.apps.chrome.Main")
        val verdict = verifier.verify(keywords, "打开飞书 App")

        assertTrue("落在浏览器必须判 OffTarget", verdict is LandingVerdict.OffTarget)
    }

    @Test
    fun `落在 AppLink 下载页时判定失败`() {
        val verifier = noActivity()
        val verdict = verifier.verify(
            landingKeywords = keywords,
            pageText = "",
            landedUrl = "https://applink.feishu.cn/download",
        )

        assertTrue(verdict is LandingVerdict.OffTarget)
    }

    @Test
    fun `浏览器判定优先于文案命中`() {
        // 极端情况：浏览器页面里恰好有"上班打卡"四个字（比如搜索结果页）。
        // 这时必须仍然判失败 —— 在浏览器里点"打卡"毫无意义
        val verifier = at("com.android.chrome/com.google.android.apps.chrome.Main")
        val verdict = verifier.verify(keywords, "上班打卡")

        assertTrue(verdict is LandingVerdict.OffTarget)
    }

    // ────────────────────── OffTarget：其它 ──────────────────────

    @Test
    fun `落在桌面时判定为跳转未生效`() {
        val verifier = at("com.miui.home/.launcher.Launcher")
        val verdict = verifier.verify(keywords, "")

        assertTrue(verdict is LandingVerdict.OffTarget)
    }

    @Test
    fun `落在非飞书应用时判定失败`() {
        val verifier = at("com.tencent.mm/.ui.LauncherUI")
        val verdict = verifier.verify(keywords, "")

        assertTrue(verdict is LandingVerdict.OffTarget)
    }

    @Test
    fun `在飞书内但不在考勤页时判定失败`() {
        // 这是最典型的情况：链接打开了飞书（跳转"成功"了），
        // 但落在首页而不是考勤页。
        // 必须判失败 —— 否则会在首页上找打卡按钮，必然失败且报错含糊
        val verifier = at("com.ss.android.lark.main.MainActivity")
        val verdict = verifier.verify(keywords, "")

        assertTrue(verdict is LandingVerdict.OffTarget)
    }

    // ────────────────────── Inconclusive ──────────────────────

    @Test
    fun `读不到 Activity 且文案未命中时无法判定`() {
        val verifier = noActivity()
        val verdict = verifier.verify(keywords, "一些无关的文字")

        assertTrue(verdict is LandingVerdict.Inconclusive)
    }

    @Test
    fun `方案未配关键字且读不到 Activity 时无法判定`() {
        val verifier = noActivity()
        val verdict = verifier.verify(emptyList(), "")

        assertTrue(verdict is LandingVerdict.Inconclusive)
    }

    @Test
    fun `空关键字列表不会误命中`() {
        // 如果实现里对空列表处理不当（比如 some 对空集合返回 false 是安全的，
        // 但如果写成 any/isNotEmpty 弄反了），会出现"永远命中"
        val verifier = noActivity()
        val verdict = verifier.verify(emptyList(), "上班打卡")

        assertTrue("空关键字列表不应命中", verdict !is LandingVerdict.OnTarget)
    }

    // ────────────────────── describeFailure ──────────────────────

    @Test
    fun `失败说明包含当前页面信息`() {
        val verifier = at("com.android.chrome/.Main")
        val verdict = verifier.verify(keywords, "") as LandingVerdict.OffTarget
        val text = verifier.describeFailure(verdict)

        assertTrue("说明里应含证据", text.contains("浏览器"))
        assertTrue("说明里应含建议", text.contains("建议"))
    }

    @Test
    fun `浏览器失败的建议指向重新复制链接`() {
        val verifier = at("com.android.chrome/.Main")
        val verdict = verifier.verify(keywords, "") as LandingVerdict.OffTarget

        val text = verifier.describeFailure(verdict)
        // 用户的行动必须是「重新复制链接」，而不是「去调控件选择器」
        assertTrue(
            "浏览器场景应引导重新复制链接",
            text.contains("重新复制") || text.contains("失效"),
        )
    }

    @Test
    fun `飞书内非考勤页的建议指向确认链接指向`() {
        val verifier = at("com.ss.android.lark.main.MainActivity")
        val verdict = verifier.verify(keywords, "") as LandingVerdict.OffTarget

        val text = verifier.describeFailure(verdict)
        assertTrue(
            "应提示链接可能指向首页而非考勤应用",
            text.contains("首页") || text.contains("考勤"),
        )
    }

    // ────────────────────── 边界 ──────────────────────

    @Test
    fun `前台组件名可空且与 null 比较安全`() {
        // foregroundReader 返回 null 是常态（无 Shizuku），
        // isFeishu / isLauncher / isBrowser 都被调用，不应抛异常
        val verifier = LandingVerifier(foregroundReader = { null })
        val verdict = verifier.verify(keywords, "")
        // 只验证不抛异常即可
        assertTrue(verdict is LandingVerdict.Inconclusive)
    }

    @Test
    fun `读取前台 Activity 抛异常时不崩溃`() {
        val verifier = LandingVerifier(
            foregroundReader = { throw IllegalStateException("binder died") },
        )
        // 应被捕获，退化为"读不到"，而不是让整个打卡流程崩掉
        val verdict = verifier.verify(keywords, "上班打卡")
        assertEquals(true, verdict is LandingVerdict.OnTarget)
    }

    @Test
    fun `Lark 国际版包名被识别为飞书`() {
        val verifier = at("com.larksuite.suite.attendance.AttendanceActivity")
        val verdict = verifier.verify(keywords, "")

        assertTrue(verdict is LandingVerdict.OnTarget)
    }
}
