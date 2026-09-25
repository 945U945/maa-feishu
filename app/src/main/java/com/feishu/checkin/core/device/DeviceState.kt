package com.feishu.checkin.core.device

/**
 * 设备状态查询接口。
 *
 * ## 为什么抽成接口而不是直接调 Android API
 *
 * 这是从 MAA 抄来的最关键的一个结构决策。MAA 的核心处理类不持有 `Context`，
 * 而是把 `keyguardLocked` / `deviceLocked` / `screenInteractive` 这些平台查询
 * 以 lambda 的形式注入：
 *
 * ```kotlin
 * // MAA 的做法（简化示意）
 * class TaskProcessor(
 *     private val keyguardLocked: () -> Boolean,
 *     private val screenInteractive: () -> Boolean,
 * )
 * ```
 *
 * 好处是执行逻辑可以在纯 JVM 环境里跑单元测试 —— 不必连真机、
 * 不必搭 Robolectric，直接把 lambda 换成 `{ true }` 就能模拟锁屏场景。
 *
 * ## 与「直接持 Context」的对比
 *
 * 直接持 `Context` 的写法更短，但会让执行引擎与 Android 框架绑死，
 * 于是「锁屏时应该跳过还是硬拉起」这类分支逻辑只能靠真机手测 ——
 * 而这类分支恰恰是最容易出问题、最需要覆盖的部分。
 */
interface DeviceStateProvider {
    /** 是否处于锁屏界面 */
    fun isKeyguardLocked(): Boolean

    /** 屏幕是否亮着 */
    fun isScreenInteractive(): Boolean

    /** 本机是否已安装飞书 */
    fun isFeishuInstalled(): Boolean

    /** 飞书的实际包名（标准版或极速版），未安装则返回 null */
    fun feishuPackageName(): String?
}

/**
 * 设备的唤醒与解锁控制。
 *
 * 打卡场景的现实约束：手机大概率是息屏放兜里的，
 * 而无障碍服务只能在屏幕点亮且解锁后才能真正操作界面。
 * 因此执行前必须尽力把设备唤醒。
 *
 * 需要说明的边界：Android 从 8.0 起限制了后台应用拉屏幕，
 * 且各家 ROM 收紧程度不同。因此 [wakeUp] 返回布尔值表示
 * 「是否成功唤起」，调用方（执行引擎）必须处理失败情况 ——
 * 而不是假设一定能亮屏。这也是 [com.feishu.checkin.checkin.model.CheckInResult.DEVICE_LOCKED]
 * 这个结果类型存在的原因。
 */
interface AwakeController {
    /**
     * 尝试唤醒屏幕。
     *
     * @return true 表示屏幕已亮（或本来就亮着）
     */
    fun wakeUp(): Boolean

    /**
     * 尝试关闭锁屏界面。
     *
     * 注意对已设置密码的设备，这个方法**不会**绕过密码 ——
     * 这是系统的安全边界，应用无法也**不应该**突破。
     * 因此只对「无密码 / 划一下就解锁」的设备有效。
     *
     * @return true 表示锁屏已解除
     */
    fun dismissKeyguard(): Boolean

    /**
     * 综合确保设备可用：先亮屏，再尝试解锁，最后复核状态。
     *
     * @return true 表示设备已处于「可操作」状态
     */
    fun ensureAwakeAndUnlocked(): Boolean
}
