package com.aliothmoon.maameow.domain.service

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.view.WindowManager
import androidx.core.content.FileProvider
import com.aliothmoon.maameow.BuildConfig
import com.aliothmoon.maameow.data.config.AppPathConfig
import com.aliothmoon.maameow.data.preferences.AppSettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 日志导出服务。
 *
 * 用途：把运行日志、触发日志、崩溃日志打包成 zip，供用户导出排查问题。
 *
 * 为什么需要导出：打卡失败的原因往往藏在细节里（控件没匹配上、权限被拦、
 * 时间窗判断跳过等）。用户遇到问题时，一份完整日志能让排查效率提高很多。
 *
 * 相比原 MAA-Meow 工程的同名类，移除了全部 MAA 版本、游戏客户端、
 * 资源包等排障字段，改为输出打卡相关的运行环境信息。
 */
class LogExportService(
    private val context: Context,
    private val pathConfig: AppPathConfig,
    private val appSettingsManager: AppSettingsManager,
) {
    companion object {
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
        private val INFO_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS (Z)")
        private const val MAX_KEEP_EXPORTS = 5
    }

    /** 日志导出目录名。 */
    private val exportDirName = "exports"

    /**
     * 导出日志为 zip。
     *
     * 即使没有任何日志也仍会生成一个包含设备信息的包 ——
     * 设备信息本身就是排障的重要线索，不该因为没有日志就失败。
     *
     * @return 生成的 zip 文件，失败返回 null
     */
    suspend fun exportZip(): File? = withContext(Dispatchers.IO) {
        try {
            val exportDir = File(pathConfig.debugDir, exportDirName).apply { mkdirs() }
            cleanupOldExports(exportDir)

            val stamp = ZonedDateTime.now().format(DATE_FORMAT)
            val outFile = File(exportDir, "checkin_log_$stamp.zip")

            ZipOutputStream(BufferedOutputStream(outFile.outputStream())).use { zos ->
                // 1. 设备与环境信息
                zos.addText("device_info.txt", buildDeviceInfo())

                // 2. 各类日志目录（触发日志、崩溃日志、应用运行日志）
                collectDir(zos, pathConfig.debugDir, "debug", skipDir = exportDir)
                collectDir(zos, pathConfig.logDir, "log")
            }

            Timber.i("日志导出完成：%s", outFile.absolutePath)
            outFile
        } catch (e: Exception) {
            Timber.e(e, "日志导出失败")
            null
        }
    }

    /** 生成用于分享的 Intent。 */
    fun buildShareIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            file,
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** 递归收集目录下的文件并写入 zip。 */
    private fun collectDir(
        zos: ZipOutputStream,
        dir: File,
        prefix: String,
        skipDir: File? = null,
    ) {
        if (!dir.exists() || !dir.isDirectory) return
        dir.walkTopDown()
            .onEnter { it != skipDir && !it.name.startsWith(".") }
            .filter { it.isFile }
            .forEach { file ->
                runCatching {
                    val rel = file.relativeTo(dir).path.replace('\\', '/')
                    FileInputStream(file).use { zos.addEntry("$prefix/$rel", it) }
                }.onFailure { Timber.w(it, "日志文件打包失败：%s", file.name) }
            }
    }

    /** 只保留最近若干次导出，避免用户反复导出把存储占满。 */
    private fun cleanupOldExports(exportDir: File) {
        runCatching {
            val files = exportDir.listFiles { f -> f.isFile }?.sortedByDescending { it.lastModified() }
                ?: return
            files.drop(MAX_KEEP_EXPORTS).forEach { it.delete() }
        }
    }

    private fun ZipOutputStream.addText(name: String, content: String) {
        addEntry(name, content.byteInputStream())
    }

    private fun ZipOutputStream.addEntry(name: String, input: InputStream) {
        putNextEntry(ZipEntry(name))
        input.copyTo(this, bufferSize = 64 * 1024)
        closeEntry()
    }

    /**
     * 构建设备与运行环境快照。
     *
     * 字段选择原则：只保留与打卡结果相关的信息。
     * 例如屏幕分辨率影响控件坐标、厂商影响后台保活策略、
     * 电池优化状态影响闹钟能否准时触发。
     */
    private fun buildDeviceInfo(): String = buildString {
        val line = "=".repeat(60)
        appendLine(line)
        appendLine("=== 飞书打卡助手 · 运行环境信息 ===")
        appendLine("导出时间    : ${ZonedDateTime.now().format(INFO_TIME_FORMAT)}")
        appendLine("应用包名    : ${BuildConfig.APPLICATION_ID}")
        appendLine("应用版本    : ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("构建类型    : ${BuildConfig.BUILD_TYPE}")
        appendLine(line)
        appendLine("--- 设备 ---")
        appendLine("厂商型号    : ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("系统版本    : Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("安全补丁    : ${Build.VERSION.SECURITY_PATCH}")
        appendLine("CPU 架构    : ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("屏幕        : ${screenInfo}")
        appendLine("内存        : ${memoryInfo}")
        appendLine(line)
        appendLine("--- 运行状态 ---")
        appendLine("前台服务模式: ${appSettingsManager.runMode.value}")
        appendLine("唤醒解锁方式: ${appSettingsManager.wakeUnlockType.value}")
        appendLine("电池优化    : ${if (isBatteryOptimized()) "已开启（可能导致定时不准）" else "已关闭（推荐）"}")
        appendLine("无障碍服务  : ${if (isAccessibilityEnabled()) "已开启" else "未开启（打卡将无法执行）"}")
        appendLine(line)
    }

    private val screenInfo: String
        get() = runCatching {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = context.resources.displayMetrics
            "${metrics.widthPixels}x${metrics.heightPixels} (${metrics.densityDpi}dpi)"
        }.getOrDefault("未知")

    private val memoryInfo: String
        get() = runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            "总 ${info.totalMem / 1024 / 1024}MB / 可用 ${info.availMem / 1024 / 1024}MB"
        }.getOrDefault("未知")

    private fun isBatteryOptimized(): Boolean = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        !pm.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(false)

    /**
     * 检查无障碍服务是否已启用。
     *
     * 直接读系统设置里的已启用服务列表 —— 这是唯一可靠的判断方式。
     * 不能只看自己的静态引用：服务进程可能被系统杀掉，
     * 但权限仍然是开启状态，两者含义不同。
     */
    private fun isAccessibilityEnabled(): Boolean = runCatching {
        val enabled = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        enabled.split(':').any { it.contains(context.packageName) }
    }.getOrDefault(false)
}
