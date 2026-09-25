package com.feishu.checkin.core.log

import android.content.Context
import java.io.File

/**
 * 应用目录布局。
 *
 * 集中在一处定义，避免各模块各自拼路径导致「日志写到哪了」说不清。
 * 所有目录在首次访问时自动创建。
 */
class AppPaths(private val context: Context) {

    /** 应用私有根目录 */
    val root: File by lazy { File(context.filesDir, "FeishuCheckIn").ensure() }

    /** 运行日志 */
    val logDir: File by lazy { File(root, "logs").ensure() }

    /** 打卡执行记录（每次一条 JSON） */
    val recordDir: File by lazy { File(root, "checkin_records").ensure() }

    /** 崩溃日志 */
    val crashDir: File by lazy { File(root, "crash_logs").ensure() }

    /** 临时文件（导出中转） */
    val tempDir: File by lazy { File(context.cacheDir, "temp").ensure() }

    fun clearTemp() {
        runCatching { tempDir.listFiles()?.forEach { it.deleteRecursively() } }
    }

    private fun File.ensure(): File = apply { if (!exists()) mkdirs() }
}
