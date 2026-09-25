package com.feishu.checkin.core.time

import com.feishu.checkin.checkin.model.EntrySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AppSettings.entryCandidates] 的单元测试。
 *
 * ## 为什么「顺序」值得单独测
 *
 * 入口候选的顺序决定了执行时**先用哪种手段**。顺序错了会怎样？
 * 内置候选（猜的链接）排到了用户自定义链接前面 ——
 * 这时用户明明配了正确的链接，却因为系统先去试那条猜的
 * 而得到「深链落点不符」的报错。用户会去改链接，
 * 怎么改都还是失败（因为他改的是对的，出错的是顺序）。
 *
 * 这类回归极难从现象反推，所以必须用测试锁死。
 */
class EntryCandidatesTest {

    private val userUrl = "https://applink.feishu.cn/client/op/open?appId=user"
    private val validDefault = AppSettings.DEFAULT

    @Test
    fun `用户配置了链接时只有一个候选且为 USER 来源`() {
        val settings = validDefault.copy(entryUrl = userUrl)
        val candidates = settings.entryCandidates()

        assertEquals(1, candidates.size)
        assertEquals(EntrySource.USER, candidates[0].source)
        assertEquals(userUrl, candidates[0].url)
    }

    @Test
    fun `用户配置了链接时不再混入内置候选`() {
        // 这是刻意的设计：如果混进去，用户链接失败后内置候选可能"意外成功"，
        // 于是用户以为自己的配置生效了 —— 而实际上内置候选跳到的
        // 未必是他企业的考勤页。掩盖真实问题比直接失败更糟
        val settings = validDefault.copy(entryUrl = userUrl)
        val sources = settings.entryCandidates().map { it.source }

        assertFalse(sources.contains(EntrySource.BUILT_IN))
    }

    @Test
    fun `未配置链接时使用内置候选`() {
        val settings = validDefault.copy(entryUrl = "")
        val candidates = settings.entryCandidates()

        assertTrue(candidates.isNotEmpty())
        assertTrue(candidates.all { it.source == EntrySource.BUILT_IN })
    }

    @Test
    fun `关闭深链时返回空列表`() {
        // 空列表的语义是「用户主动要求不用深链」，
        // 于是 EntryOpener 会直接走点击导航
        val settings = validDefault.copy(entryUrl = userUrl, deepLinkEnabled = false)

        assertTrue(settings.entryCandidates().isEmpty())
    }

    @Test
    fun `关闭深链时即使有自定义链接也不返回候选`() {
        val settings = validDefault.copy(
            entryUrl = userUrl,
            deepLinkEnabled = false,
        )

        assertEquals(0, settings.entryCandidates().size)
    }

    @Test
    fun `链接前后空格被裁掉`() {
        val settings = validDefault.copy(entryUrl = "  $userUrl  ")

        assertEquals(userUrl, settings.entryCandidates()[0].url)
    }

    @Test
    fun `canUseDeepLink 同时要求开关打开且链接非空`() {
        // 这个属性存在的意义就是「别让调用方自己拼两个条件」——
        // 拼漏一个就会在链接为空时还去尝试跳转
        assertTrue(
            validDefault.copy(entryUrl = userUrl, deepLinkEnabled = true).canUseDeepLink,
        )
        assertFalse(
            validDefault.copy(entryUrl = userUrl, deepLinkEnabled = false).canUseDeepLink,
        )
        assertFalse(
            validDefault.copy(entryUrl = "", deepLinkEnabled = true).canUseDeepLink,
        )
        assertFalse(
            validDefault.copy(entryUrl = "   ", deepLinkEnabled = true).canUseDeepLink,
        )
    }

    @Test
    fun `空白链接不产生候选`() {
        val settings = validDefault.copy(entryUrl = "   ")
        val candidates = settings.entryCandidates()

        // 退化为内置候选，而不是产生一个空白 url 的 USER 候选
        assertTrue(candidates.all { it.source == EntrySource.BUILT_IN })
    }

    @Test
    fun `内置候选的链接格式合法`() {
        // 如果内置候选本身格式就错，会白白浪费一次跳转尝试。
        // 这条测试确保内置候选至少"看起来像"飞书链接
        val candidates = validDefault.copy(entryUrl = "").entryCandidates()

        assertTrue("内置候选不应为空", candidates.isNotEmpty())
        candidates.forEach { entry ->
            assertTrue(
                "内置候选格式不合法: ${entry.url}",
                entry.isUsable,
            )
        }
    }
}
