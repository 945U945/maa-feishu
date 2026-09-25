package com.feishu.checkin.core.log

import android.app.Application
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 启动追踪 + 崩溃兜底记录。
 *
 * ## 为什么需要这个类（血泪教训）
 *
 * 上一版实现里，崩溃处理器的安装时机排在日志系统初始化**之后**。
 * 结果当依赖注入在初始化阶段抛异常时，崩溃处理器还没挂上，
 * 堆栈既不落盘也进不了 logcat（Release 下系统吞掉了），
 * 用户看到的现象就是「点图标闪一下就退，且查不到任何原因」。
 *
 * 因此本类刻意做到：
 * 1. **零依赖**：只用 android.util.Log 与 java.io，
 *    不碰 Timber、不碰依赖注入、不碰任何业务单例
 * 2. **最先安装**：Application.onCreate 的第一条语句
 * 3. **同步落盘**：每行立即 flush，进程被杀也不丢
 *
 * 日志位置：`<filesDir>/FeishuCheckIn/logs/boot.log`
 */
object BootTrace {

    private const val TAG = "BootTrace"
    private const val FILE_NAME = "boot.log"
    private const val MAX_BYTES = 256L * 1024

    @Volatile
    private var logFile: File? = null

    @Volatile
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * 安装启动追踪与崩溃兜底。
     *
     * 必须在 Application.onCreate 的最开头调用 —— 早于依赖注入初始化，
     * 这样即便依赖注入整体失败，也能留下可读的堆栈。
     */
    fun install(app: Application) {
        logFile = runCatching {
            val dir = File(File(app.filesDir, "FeishuCheckIn"), "logs")
            if (!dir.exists()) dir.mkdirs()
            File(dir, FILE_NAME).also {
                if (it.exists() && it.length() > MAX_BYTES) it.delete()
            }
        }.getOrNull()

        write("══════════════ App 启动 ══════════════")
        write("设备: ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        write("进程: pid=${android.os.Process.myPid()}")

        // 链上系统原有的处理器，避免覆盖掉框架/调试器需要的行为
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            write("══════════════ 未捕获异常 ══════════════")
            write("线程: ${thread.name}")
            write(stackOf(throwable))
            runCatching { previousHandler?.uncaughtException(thread, throwable) }
        }
    }

    /** 记录一个已完成的启动阶段 */
    fun step(name: String) {
        write("[ 阶段 ] $name")
    }

    /** 记录一次异常但继续执行 */
    fun fail(name: String, t: Throwable) {
        write("[ 失败 ] $name")
        write(stackOf(t))
        Log.e(TAG, name, t)
    }

    private fun stackOf(t: Throwable): String {
        val sw = StringWriter()
        PrintWriter(sw).use { t.printStackTrace(it) }
        return sw.toString()
    }

    @Synchronized
    private fun write(line: String) {
        val ts = fmt.format(Date())
        Log.i(TAG, line)
        val f = logFile ?: return
        runCatching { f.appendText("$ts  $line\n") }
    }
}

/**
 * 崩溃记录。
 *
 * 与 [BootTrace] 的区别：BootTrace 关注「启动过程到哪一步了」，
 * CrashHandler 关注「崩溃现场的完整快照」并做文件轮转。
 * 两者互补，不合并 —— 因为 BootTrace 必须在崩溃处理器可用之前就工作。
 */
class CrashHandler(private val paths: AppPaths) {

    private companion object {
        const val MAX_CRASH_FILES = 20
    }

    fun install(app: Application) {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { save(thread, throwable) }
            runCatching { original?.uncaughtException(thread, throwable) }
        }
    }

    private fun save(thread: Thread, throwable: Throwable) {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val content = buildString {
            appendLine("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("线程: ${thread.name}")
            appendLine()
            appendLine("── 堆栈 ──")
            append(stackOf(throwable))
        }
        File(paths.crashDir, "crash_$stamp.txt").writeText(content)
        cleanOld()
    }

    private fun cleanOld() {
        runCatching {
            val files = paths.crashDir.listFiles()?.sortedByDescending { it.name } ?: return
            if (files.size > MAX_CRASH_FILES) files.drop(MAX_CRASH_FILES).forEach { it.delete() }
        }
    }

    private fun stackOf(t: Throwable): String {
        val sw = StringWriter()
        PrintWriter(sw).use { t.printStackTrace(it) }
        return sw.toString()
    }
}
