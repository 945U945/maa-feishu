package com.feishu.checkin.checkin.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 打卡结果语义的单元测试。
 *
 * ## 这组测试守护的是一条业务规则
 *
 * 「ALREADY_DONE 与 SKIPPED_BUSY 不是失败」这条规则看起来只是
 * 几个布尔值，但它决定了用户对告警的信任度：
 *
 * 如果每天「已打卡」都被标红，用户会在两周内学会忽略所有红色提示，
 * 于是真正的「未找到打卡入口」被淹没 —— 而那是会让人漏打卡的问题。
 *
 * 这种「语义契约」最容易在后续重构中被无意破坏
 * （比如有人为了「统一处理」把 SKIPPED 归到 else 分支）。
 * 因此必须有测试锁住。
 */
class CheckInResultTest {

    @Test
    fun `success 与 alreadyDone 属于成功类`() {
        assertTrue(CheckInResult.SUCCESS.isSuccessLike)
        assertTrue(CheckInResult.ALREADY_DONE.isSuccessLike)
    }

    @Test
    fun `alreadyDone 不是失败`() {
        assertFalse(
            "「今日已打卡」是最常见的正常情况，绝不能算失败",
            CheckInResult.ALREADY_DONE.isFailure,
        )
    }

    @Test
    fun `skippedBusy 不是失败`() {
        assertFalse(
            "「已有任务在执行」是并发保护的预期结果，不是错误",
            CheckInResult.SKIPPED_BUSY.isFailure,
        )
        assertFalse(CheckInResult.SKIPPED_BUSY.isSuccessLike)
    }

    @Test
    fun `需要用户关注的失败类型`() {
        val failureResults = listOf(
            CheckInResult.ENTRY_NOT_FOUND,
            CheckInResult.NETWORK_ERROR,
            CheckInResult.DEVICE_LOCKED,
            CheckInResult.PERMISSION_MISSING,
            CheckInResult.FAILURE,
        )
        failureResults.forEach {
            assertTrue("$it 应当被标记为失败", it.isFailure)
        }
    }

    @Test
    fun `成功与失败互斥`() {
        CheckInResult.entries.forEach { result ->
            assertFalse(
                "$result 不能同时是成功和失败",
                result.isSuccessLike && result.isFailure,
            )
        }
    }

    @Test
    fun `只有瞬时性问题才值得重试`() {
        // 值得重试：可能自愈
        assertTrue(CheckInResult.FAILURE.isRetryable)
        assertTrue(CheckInResult.NETWORK_ERROR.isRetryable)

        // 不值得重试：确定性失败
        assertFalse("找不到入口，重试还是找不到", CheckInResult.ENTRY_NOT_FOUND.isRetryable)
        assertFalse("解不了锁，重试还是解不了", CheckInResult.DEVICE_LOCKED.isRetryable)
        assertFalse("要用户去开权限，重试无意义", CheckInResult.PERMISSION_MISSING.isRetryable)

        // 正常终态也不该重试
        assertFalse(CheckInResult.SUCCESS.isRetryable)
        assertFalse(CheckInResult.ALREADY_DONE.isRetryable)
        assertFalse(CheckInResult.SKIPPED_BUSY.isRetryable)
    }
}

/**
 * 幂等键的测试。
 *
 * 幂等键用「日期 + 类型」而不是随机 UUID，这是打卡场景的关键差异 ——
 * 随机 ID 只能防「同一次请求提交两次」，
 * 日期 ID 才能防「同一天被不同触发源各打一次」。
 */
class CheckInRequestTest {

    private fun requestAt(
        kind: CheckInKind,
        dayOfMonth: Int,
        planId: String = "builtin_workbench",
    ): CheckInRequest {
        // 用固定日期构造，避免测试依赖当前时间
        val cal = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.SEPTEMBER, dayOfMonth, 9, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return CheckInRequest(
            kind = kind,
            planId = planId,
            scheduledAt = cal.timeInMillis,
            startedAt = cal.timeInMillis,
        )
    }

    @Test
    fun `同一天同类型的幂等键应相同`() {
        val a = requestAt(CheckInKind.CLOCK_IN, 25)
        val b = requestAt(CheckInKind.CLOCK_IN, 25)
        assertEquals("同一天同一类型必须是同一个键", a.dedupeKey, b.dedupeKey)
    }

    @Test
    fun `同一天不同类型的幂等键应不同`() {
        val clockIn = requestAt(CheckInKind.CLOCK_IN, 25)
        val clockOut = requestAt(CheckInKind.CLOCK_OUT, 25)
        assertTrue(
            "上班和下班是两次独立打卡，键必须不同",
            clockIn.dedupeKey != clockOut.dedupeKey,
        )
    }

    @Test
    fun `不同日期的幂等键应不同`() {
        val day1 = requestAt(CheckInKind.CLOCK_IN, 25)
        val day2 = requestAt(CheckInKind.CLOCK_IN, 26)
        assertTrue("跨天必须换键，否则第二天打不了卡", day1.dedupeKey != day2.dedupeKey)
    }

    @Test
    fun `不同方案的幂等键应不同`() {
        val planA = requestAt(CheckInKind.CLOCK_IN, 25, "builtin_workbench")
        val planB = requestAt(CheckInKind.CLOCK_IN, 25, "builtin_profile")
        assertTrue(planA.dedupeKey != planB.dedupeKey)
    }

    @Test
    fun `幂等键格式应包含日期与类型`() {
        val req = requestAt(CheckInKind.CLOCK_IN, 25)
        val key = req.dedupeKey
        assertTrue("键里应含方案 ID", key.contains("builtin_workbench"))
        assertTrue("键里应含类型名", key.contains("CLOCK_IN"))
        assertTrue("键里应含 8 位日期", key.contains("20260925"))
    }
}
