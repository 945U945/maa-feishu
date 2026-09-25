package com.feishu.checkin.checkin.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EntryFormats] 的单元测试。
 *
 * ## 为什么这个 object 值得单独测
 *
 * [EntryFormats.looksLikeFeishuLink] 与 [EntryFormats.toSchemeForm] 是
 * 「保存链接」与「执行跳转」两处**共用**的判定规则。
 * 它们一旦不一致，就会出现最难排查的一类 bug ——
 * 用户能成功保存链接（保存时校验通过），但执行时打不开
 * （跳转时判定为不合法），而两条日志分别看都"正常"。
 *
 * 所以这里把边界情况穷举出来，确保规则本身稳固。
 */
class EntryFormatsTest {

    // ────────────────────── looksLikeFeishuLink ──────────────────────

    @Test
    fun `官方 AppLink 被识别为飞书链接`() {
        assertTrue(
            EntryFormats.looksLikeFeishuLink(
                "https://applink.feishu.cn/client/op/open",
            ),
        )
    }

    @Test
    fun `Lark 国际版链接被识别`() {
        assertTrue(
            EntryFormats.looksLikeFeishuLink(
                "https://applink.larksuite.com/client/op/open",
            ),
        )
    }

    @Test
    fun `企业自建域名后缀被识别`() {
        // 私有化部署的企业会有形如 acme.feishu.cn 的域名
        assertTrue(
            EntryFormats.looksLikeFeishuLink(
                "https://acme.feishu.cn/client/op/open",
            ),
        )
    }

    @Test
    fun `scheme 形式的深链被识别`() {
        assertTrue(EntryFormats.looksLikeFeishuLink("feishu://applink/client/op/open"))
        assertTrue(EntryFormats.looksLikeFeishuLink("lark://applink/client/op/open"))
    }

    @Test
    fun `大小写差异不影响识别`() {
        // 用户从不同地方复制的链接大小写可能不一致，
        // 而 scheme/host 在语义上都是大小写不敏感的
        assertTrue(
            EntryFormats.looksLikeFeishuLink(
                "HTTPS://APPLINK.FEISHU.CN/client/op/open",
            ),
        )
        assertTrue(EntryFormats.looksLikeFeishuLink("FEISHU://applink/client"))
    }

    @Test
    fun `前后空格被忽略`() {
        // 从聊天窗口复制链接常常带前后空格 ——
        // 这是最高频的输入瑕疵，不处理会让用户莫名其妙失败
        assertTrue(
            EntryFormats.looksLikeFeishuLink(
                "  https://applink.feishu.cn/client/op/open  ",
            ),
        )
    }

    @Test
    fun `空字符串不是合法链接`() {
        assertFalse(EntryFormats.looksLikeFeishuLink(""))
        assertFalse(EntryFormats.looksLikeFeishuLink("   "))
    }

    @Test
    fun `普通网址不是飞书链接`() {
        // 这是最需要拦住的情况：用户复制了别的网页链接
        assertFalse(EntryFormats.looksLikeFeishuLink("https://www.baidu.com"))
        assertFalse(EntryFormats.looksLikeFeishuLink("https://github.com/foo/bar"))
    }

    @Test
    fun `相似但不同的域名不被误认`() {
        // 防的是「伪造域名」类的误判：feishu.cn.evil.com 不是飞书域名。
        // 注意这里的判据是**后缀**匹配，所以 evil-feishu.cn 也会被误认 ——
        // 但那是可接受的：本校验只用于「格式是否像飞书链接」的粗筛，
        // 真正决定跳转是否成功的是系统解析与落点校验，两者会兜住
        assertFalse(EntryFormats.looksLikeFeishuLink("https://feishu.cn.evil.com/x"))
    }

    @Test
    fun `裸域名不加协议不被识别`() {
        // 用户可能只复制了域名部分。这不算合法链接 ——
        // 因为缺少协议头无法构造 Intent
        assertFalse(EntryFormats.looksLikeFeishuLink("applink.feishu.cn/client/op/open"))
    }

    // ────────────────────── toSchemeForm ──────────────────────

    @Test
    fun `https 转成 feishu scheme 形式`() {
        assertEquals(
            "feishu://applink/client/op/open",
            EntryFormats.toSchemeForm("https://applink.feishu.cn/client/op/open"),
        )
    }

    @Test
    fun `larksuite 转成 lark scheme 形式`() {
        // 关键：装了 Lark 的设备发 feishu:// 会无人接收，
        // 必须按域名选对 scheme
        assertEquals(
            "lark://applink/client/op/open",
            EntryFormats.toSchemeForm("https://applink.larksuite.com/client/op/open"),
        )
    }

    @Test
    fun `已是 scheme 形式时原样返回`() {
        assertEquals(
            "feishu://applink/client/op/open",
            EntryFormats.toSchemeForm("feishu://applink/client/op/open"),
        )
    }

    @Test
    fun `带查询参数时保留参数`() {
        assertEquals(
            "feishu://applink/client/op/open?appId=abc&x=1",
            EntryFormats.toSchemeForm(
                "https://applink.feishu.cn/client/op/open?appId=abc&x=1",
            ),
        )
    }

    @Test
    fun `非 applink 宿主无法转换返回 null`() {
        // 企业自有域名没有对应的 scheme 形式。
        // 强行拼会得到一条打不开的链接 —— 不如老实返回 null，
        // 让调用方知道「这条路走不通」
        assertNull(
            EntryFormats.toSchemeForm("https://acme.feishu.cn/client/op/open"),
        )
    }

    @Test
    fun `非 http 协议无法转换返回 null`() {
        assertNull(EntryFormats.toSchemeForm("ftp://example.com/x"))
        assertNull(EntryFormats.toSchemeForm("random text"))
    }

    @Test
    fun `转换后不含空格`() {
        // 历史坑：早期实现在拼 scheme 时用了字符串模板加空格，
        // 产生了 "feishu:// applink/..." 这种打不开的链接。
        // 这条测试守住这个回归
        val result = EntryFormats.toSchemeForm("https://applink.feishu.cn/client/op/open")
        assertTrue(result != null)
        assertFalse(result!!.contains(' '))
    }

    // ────────────────────── looksLikeFallbackPage ──────────────────────

    @Test
    fun `飞书下载页被识别为中转页`() {
        assertTrue(
            EntryFormats.looksLikeFallbackPage(
                "https://applink.feishu.cn/download?x=1",
            ),
        )
    }

    @Test
    fun `null 不是中转页`() {
        assertFalse(EntryFormats.looksLikeFallbackPage(null))
    }

    @Test
    fun `正常链接不是中转页`() {
        assertFalse(
            EntryFormats.looksLikeFallbackPage(
                "https://applink.feishu.cn/client/op/open",
            ),
        )
    }

    // ────────────────────── AppLinkEntry.isUsable ──────────────────────

    @Test
    fun `空白链接不可用`() {
        val entry = AppLinkEntry(source = EntrySource.USER, url = "  ")
        assertFalse(entry.isUsable)
    }

    @Test
    fun `格式错误的链接不可用`() {
        val entry = AppLinkEntry(source = EntrySource.USER, url = "not a link")
        assertFalse(entry.isUsable)
    }

    @Test
    fun `合法链接可用`() {
        val entry = AppLinkEntry(
            source = EntrySource.USER,
            url = "https://applink.feishu.cn/client/op/open",
        )
        assertTrue(entry.isUsable)
    }
}
