package com.aliothmoon.maameow.data.config

import android.content.Context
import java.io.File

/**
 * 应用数据目录配置。
 *
 * 这是原 MAA-Meow 工程 `MaaPathConfig` 的精简替代品。
 * 原类承担了 MAA 资源包、游戏缓存、核心库等多套目录的布局管理，
 * 其中绝大多数对打卡功能毫无意义。
 *
 * 打卡真正需要的只有三类目录：
 * - 日志目录（触发日志、崩溃日志）
 * - 临时目录（导出文件的中转）
 * - 配置目录（打卡方案、用户设置）
 *
 * 之所以不直接使用应用沙箱的 cacheDir/filesDir 了事，
 * 是因为日志需要能被用户导出查看，集中在一处便于管理。
 */
class AppPathConfig(private val context: Context) {

    /** 应用私有根目录 */
    val rootDir: File by lazy {
        File(context.filesDir, "FeishuCheckIn").apply { mkdirs() }
    }

    /** 日志根目录 */
    val logDir: File by lazy {
        File(rootDir, "log").apply { mkdirs() }
    }

    /** 调试类目录：存放定时触发日志、崩溃日志等需要导出排查的内容 */
    val debugDir: File by lazy {
        File(rootDir, "debug").apply { mkdirs() }
    }

    /**
     * 提权进程（launcher，以 shell / root 身份运行）可写的调试目录。
     *
     * 原工程把它放在 MaaCore 独立数据目录下；打卡版没有 MaaCore，
     * 但仍需要一个 App 沙箱之外、shell 可访问的落点存放 launcher 启动日志，
     * 否则启动失败时无法从 App 侧读到日志。
     *
     * 使用 /data/local/tmp（shell 默认可写）下的应用专属子目录。
     */
    val coreDebugDir: File by lazy {
        File(CORE_DEBUG_ROOT, "FeishuCheckIn").apply { runCatching { mkdirs() } }
    }

    /** 配置目录：打卡方案、备份文件 */
    val configDir: File by lazy {
        File(rootDir, "config").apply { mkdirs() }
    }

    /** 临时目录：导出文件的中转站 */
    val tempDir: File by lazy {
        File(context.cacheDir, "temp").apply { mkdirs() }
    }

    /** 清理临时目录。应用启动或长时间运行后调用，避免缓存膨胀。 */
    fun clearTemp() {
        runCatching {
            tempDir.listFiles()?.forEach { it.deleteRecursively() }
        }
    }

    private companion object {
        const val CORE_DEBUG_ROOT = "/data/local/tmp"
    }
}
