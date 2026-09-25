package com.aliothmoon.maameow.utils

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 启动阶段埋点与兜底日志。
 *
 * 为什么需要它：
 *
 * 应用曾出现「点图标闪一下就退、且不留任何日志」的情况。排查时发现根因是
 * 崩溃处理器本身**晚于**出问题的代码才安装 —— 一旦 Koin 依赖解析在
 * `treeHolder.setup()` 阶段抛异常，此时 `Thread.setDefaultUncaughtExceptionHandler`
 * 还没挂上，堆栈既进不了 logcat（Release 下被系统吞掉）也落不了盘。
 *
 * 因此本类刻意做到：
 * - **零依赖**：不碰 Timber、不碰 Koin、不碰 AppSettingsManager，
 *   只用 android.util.Log 与 java.io，保证在最恶劣的情况下仍可用
 * - **最先初始化**：`MaaApplication.onCreate` 第一句就 `installFileAppender`
 * - **同步落盘**：每步都 flush，进程被杀也不丢
 *
 * 日志落点：`<filesDir>/FeishuCheckIn/debug/boot_trace.log`
 * （与 CrashHandler 的 crash_logs 同级，便于一起导出）
 */
object AppBootTrace {

    private const val TAG = "AppBootTrace"
    private const val DIR_NAME = "FeishuCheckIn"
    private const val SUB_DIR = "debug"
    private const val FILE_NAME = "boot_trace.log"

    /** 单文件上限，超过就直接覆盖重来，避免无限增长 */
    private const val MAX_BYTES = 256L * 1024

    @Volatile
    private var logFile: File? = null

    @Volatile
    private var lastCrashHandler: Thread.UncaughtExceptionHandler? = null

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * 安装文件落盘能力，并把崩溃处理器也链上。
     *
     * 必须在 Application.onCreate 的最开头调用。
     */
    fun installFileAppender(context: Context) {
        val app = context.applicationContext
        logFile = runCatching {
            val dir = File(File(app.filesDir, DIR_NAME), SUB_DIR)
            if (!dir.exists()) dir.mkdirs()
            File(dir, FILE_NAME).also {
                if (it.exists() && it.length() > MAX_BYTES) it.delete()
            }
        }.getOrNull()

        write("========== App start ==========")
        write("device=${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        write("pid=${android.os.Process.myPid()}")

        // 把崩溃也写进本文件：即便 CrashHandler 注入失败，这里仍能留痕
        lastCrashHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            write("========== UNCAUGHT ==========")
            write("thread=${thread.name}")
            write(stackOf(throwable))
            runCatching { lastCrashHandler?.uncaughtException(thread, throwable) }
        }
    }

    /** 记录一个正常推进的阶段。 */
    fun step(name: String) {
        write("[step ] $name")
    }

    /** 记录一次异常（不抛出，仅留痕）。 */
    fun fail(name: String, t: Throwable) {
        write("[FAIL ] $name")
        write(stackOf(t))
        // 同时进 logcat，方便 adb logcat 直接抓
        Log.e(TAG, name, t)
    }

    private fun stackOf(t: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        t.printStackTrace(pw)
        pw.flush()
        return sw.toString()
    }

    @Synchronized
    private fun write(line: String) {
        val ts = timeFormat.format(Date())
        Log.i(TAG, line)
        val f = logFile ?: return
        runCatching {
            f.appendText("$ts  $line\n")
        }
    }
}
