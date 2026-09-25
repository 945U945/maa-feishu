package com.feishu.checkin.core.log

import android.util.Log
import timber.log.Timber

/**
 * 文件日志 Tree。
 *
 * 写入策略（借鉴 MAA 的取舍）：
 * - 默认只落 WARN 及以上，避免正常使用时日志疯长占用空间
 * - 用户开启「详细日志」后落全量，用于排查
 *
 * 写入是同步的：打卡是低频操作（一天两次），
 * 用异步换取的那点性能毫无意义，反而可能因进程被杀而丢日志。
 */
class FileLogTree(
    private val paths: AppPaths,
    private val verboseProvider: () -> Boolean,
) : Timber.Tree() {

    private val timeFormat = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)

    override fun isLoggable(tag: String?, priority: Int): Boolean =
        priority >= Log.WARN || verboseProvider()

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val level = when (priority) {
            Log.VERBOSE -> "V"
            Log.DEBUG -> "D"
            Log.INFO -> "I"
            Log.WARN -> "W"
            Log.ERROR -> "E"
            Log.ASSERT -> "A"
            else -> "?"
        }
        val ts = timeFormat.format(java.util.Date())
        val line = buildString {
            append(ts).append(' ').append(level).append('/')
            append(tag ?: "App").append(": ").append(message)
            if (t != null) {
                append('\n').append(Log.getStackTraceString(t))
            }
            append('\n')
        }
        runCatching { currentFile().appendText(line) }
    }

    /**
     * 按天切分日志文件。
     *
     * 按天而不是按大小滚动，是因为排查打卡问题几乎总是
     * 「看今天/昨天发生了什么」，按天归档更符合实际检索习惯。
     */
    @Synchronized
    private fun currentFile() = FileLogger.fileFor(paths.logDir, "app")

    companion object {
        private const val TAG = "FileLogTree"
    }
}

/** 日志文件工具，供 FileLogTree 与打卡记录共用 */
internal object FileLogger {

    private const val MAX_FILES = 60

    /** 生成当天日志文件，并顺带做一次旧文件清理 */
    @Synchronized
    fun fileFor(dir: java.io.File, prefix: String): java.io.File {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
            .format(java.util.Date())
        val f = java.io.File(dir, "${prefix}_$stamp.log")
        if (!f.exists()) {
            runCatching {
                f.createNewFile()
                prune(dir, prefix)
            }
        }
        return f
    }

    /** 只保留最近 MAX_FILES 个同前缀日志 */
    private fun prune(dir: java.io.File, prefix: String) {
        val files = dir.listFiles { f -> f.name.startsWith("${prefix}_") && f.name.endsWith(".log") }
            ?.sortedByDescending { it.name } ?: return
        if (files.size > MAX_FILES) {
            files.drop(MAX_FILES).forEach { it.delete() }
        }
    }
}
