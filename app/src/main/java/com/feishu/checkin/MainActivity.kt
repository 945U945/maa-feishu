package com.feishu.checkin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.feishu.checkin.core.di.entryPoint
import com.feishu.checkin.ui.FeishuCheckInApp
import com.feishu.checkin.ui.theme.FeishuCheckInTheme
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 主界面。
 *
 * 刻意保持极薄 —— 只做三件事：
 * 1. 装启动图（避免白屏一帧）
 * 2. 用户每次打开应用时幂等重排闹钟（三层恢复里的第二层，兜底国产 ROM）
 * 3. 承载 Compose 内容
 *
 * ## 为什么每次进入都重排闹钟
 *
 * 成本几乎为零（一次 cancel + 一次 setAlarmClock），
 * 但换来的是「用户打开过应用就一定有闹钟」的强保证。
 *
 * 这一层存在的意义是应对国产 ROM 拦截 `BOOT_COMPLETED` 的情况 ——
 * 那是三层恢复里唯一不可靠的一环，而用户手动打开应用
 * 是最可靠的动作。
 *
 * 注意用 `onStart` 而不是 `onCreate`：用户从后台切回来时也会重排。
 * 切回来的场景很常见（用户改完设置切出去再回来），
 * 每次都确保调度正确比省那点开销重要得多。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // 必须在 super.onCreate 之前调用，否则启动图不生效。
        // 不装的话冷启动会先白屏一帧再进主题色，观感上像闪了一下
        installSplashScreen()

        super.onCreate(savedInstanceState)

        // 全屏沉浸：让 Compose 自己处理系统栏内边距
        enableEdgeToEdge()

        setContent {
            FeishuCheckInTheme {
                FeishuCheckInApp()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ensureAlarmScheduled()
        refreshRepositories()
    }

    /**
     * 幂等重排闹钟。
     *
     * 用 runCatching 包住全部逻辑：这是**兜底路径**，
     * 它的失败不应该影响用户正常使用界面 ——
     * 用户来打开应用可能是想看记录，不是来打卡的。
     */
    private fun ensureAlarmScheduled() {
        lifecycleScope.launch {
            runCatching {
                val deps = entryPoint()
                val settings = deps.settingsRepository().current()
                if (!settings.enabled) {
                    Timber.d("自动打卡未启用，跳过重排")
                    return@runCatching
                }
                val next = deps.alarmScheduler().scheduleNext(settings)
                Timber.i("进入应用，已确认闹钟调度: %s", next ?: "未排定")
            }.onFailure { Timber.w(it, "进入应用时重排闹钟失败") }
        }
    }

    /** 刷新记录与方案缓存，保证界面显示的是最新数据 */
    private fun refreshRepositories() {
        lifecycleScope.launch {
            runCatching {
                val deps = entryPoint()
                deps.recordRepository().refresh()
                deps.planRepository().refresh()
            }.onFailure { Timber.w(it, "刷新数据失败") }
        }
    }
}
