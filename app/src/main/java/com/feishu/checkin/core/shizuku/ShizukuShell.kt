package com.feishu.checkin.core.shizuku

import android.os.ParcelFileDescriptor
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * 命令执行结果。
 *
 * 不返回裸的 String 而是带上退出码，是因为**很多命令失败时
 * 退出码非零但 stdout 非空**（比如 `dumpsys` 在部分 ROM 上会
 * 打印警告后继续）。只看 stdout 会把「部分失败」当成「完全成功」，
 * 从而解析出畸形的数据。
 */
data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val isSuccess: Boolean get() = exitCode == 0

    /** 合并输出，用于关键词检索 */
    val combined: String get() = "$stdout\n$stderr"

    fun firstLine(): String = stdout.lineSequence().firstOrNull { it.isNotBlank() } ?: ""
}

/**
 * 通过 Shizuku 执行 shell 命令。
 *
 * ## 为什么不直接用 Runtime.exec
 *
 * 普通应用执行 `Runtime.getRuntime().exec("am start ...")` 时，
 * **是以本应用自己的 uid 运行的** —— 而本应用没有 `am`/`dumpsys`
 * 需要的那些权限，命令会以各种含糊的方式失败：
 *
 * - `am start` 报 `SecurityException: Permission Denial`
 * - `dumpsys activity` 只输出自己应用的一小段
 * - 某些 ROM 上直接 `command not found`
 *
 * 通过 Shizuku 执行则以 **adb uid（或 root uid）** 运行，
 * 拿到的就是 adb shell 里的那份能力。
 *
 * ## 实现要点
 *
 * Shizuku 提供了两条路：
 * 1. `Shizuku.newProcess(String[], String[], String)` —— 直接起进程，
 *    返回 `ParcelFileDescriptor`。**Shizuku 12+ 才有**。
 * 2. `ShizukuBinderWrapper` 包装 `IContentService` 等系统服务 ——
 *    不适用于跑任意 shell 命令。
 *
 * 这里用第 1 条（也是 Shizuku 官方文档推荐的跑命令方式），
 * 并用反射调用以避免编译期依赖。
 *
 * ⚠️ **读取输出要用独立线程**，否则会死锁：
 * 子进程写满管道缓冲区（通常 64KB）后会阻塞等待父进程读取，
 * 而父进程若先 `waitFor()` 就会互相等死。
 * `dumpsys package` 的输出轻易超过几 MB，这个坑必踩。
 */
class ShizukuShell {

    /**
     * 执行命令。
     *
     * @param command 完整命令行；第一段是可执行文件，其余是参数。
     *                例如 `listOf("dumpsys", "activity", "activities")`
     * @return 执行结果；Shizuku 不可用时返回 exitCode = -1
     */
    fun exec(command: List<String>): ShellResult {
        if (command.isEmpty()) {
            return ShellResult(-1, "", "空命令")
        }

        return try {
            val process = newProcess(command)
                ?: return ShellResult(-1, "", "Shizuku.newProcess 返回空（服务已断开？）")

            // 三个流并行读，避免管道缓冲区写满导致死锁
            val stdoutReader = StreamGobbler(process.first)
            val stderrReader = StreamGobbler(process.second)
            stdoutReader.start()
            stderrReader.start()

            val exit = process.third.waitFor()
            // 等读完残余输出。给个上限，避免子进程异常时永久挂住
            stdoutReader.join(STREAM_JOIN_TIMEOUT_MS)
            stderrReader.join(STREAM_JOIN_TIMEOUT_MS)

            ShellResult(
                exitCode = exit,
                stdout = stdoutReader.text(),
                stderr = stderrReader.text(),
            )
        } catch (t: Throwable) {
            Timber.w(t, "Shizuku 执行命令失败: %s", command.joinToString(" "))
            ShellResult(-1, "", t.message ?: t.javaClass.simpleName)
        }
    }

    /** 便捷重载：直接传字符串命令 */
    fun exec(commandLine: String): ShellResult =
        exec(splitCommand(commandLine))

    /**
     * 分割命令行。
     *
     * 不简单地按空格 split —— 因为参数里可能有引号包起来的
     * 含空格内容（如 `am start -d "https://xxx?a=b c"`）。
     * 按空格切会把一个参数切成两个，命令行为随之改变。
     *
     * 这里实现一个最小可用的引号感知分割：支持单引号与双引号，
     * 不做转义处理（shell 命令里极少用到嵌套转义）。
     */
    private fun splitCommand(line: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null

        for (ch in line) {
            when {
                quote != null -> {
                    if (ch == quote) quote = null else current.append(ch)
                }
                ch == '"' || ch == '\'' -> quote = ch
                ch.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        out.add(current.toString())
                        current.clear()
                    }
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) out.add(current.toString())
        return out
    }

    /**
     * 调用 `Shizuku.newProcess`。
     *
     * 返回三元组 (stdout, stderr, Process)。用 Triple 而不是自定义类，
     * 是因为它只在这一个方法内部流转，不值得为它引入一个类型。
     *
     * 反射细节：`newProcess(String[] cmd, String[] env, String dir)`
     * 是静态方法，返回 `rikka.shizuku.ShizukuRemoteProcess`，
     * 它继承 `java.lang.Process` 并额外提供
     * `getOutputStream()` / `getErrorStream()` 返回 ParcelFileDescriptor。
     */
    private fun newProcess(command: List<String>): Triple<InputStream, InputStream, Process>? {
        val shizukuClass = Class.forName("rikka.shizuku.Shizuku")
        val method = shizukuClass.getMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        )
        val process = method.invoke(
            null,
            command.toTypedArray(),
            null,
            null,
        ) as? Process ?: return null

        // ShizukuRemoteProcess 覆写了这两个方法，返回 ParcelFileDescriptor
        val stdoutPfd = process.javaClass
            .getMethod("getOutputStream")
            .invoke(process) as? ParcelFileDescriptor
            ?: return null
        val stderrPfd = process.javaClass
            .getMethod("getErrorStream")
            .invoke(process) as? ParcelFileDescriptor
            ?: return null

        return Triple(
            ParcelFileDescriptor.AutoCloseInputStream(stdoutPfd),
            ParcelFileDescriptor.AutoCloseInputStream(stderrPfd),
            process,
        )
    }

    /**
     * 流读取线程。
     *
     * 必须独立线程 —— 见类注释里的死锁说明。
     */
    private class StreamGobbler(private val input: InputStream) : Thread() {
        private val buffer = StringBuilder()

        override fun run() {
            try {
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                    val chunk = CharArray(8 * 1024)
                    while (true) {
                        val n = reader.read(chunk)
                        if (n <= 0) break
                        buffer.append(chunk, 0, n)
                    }
                }
            } catch (t: Throwable) {
                // 流被提前关闭是正常情况（子进程退出），不视为错误
                Timber.v(t, "读取命令输出流结束")
            }
        }

        fun text(): String = synchronized(buffer) { buffer.toString() }
    }

    private companion object {
        /** 等待输出读完的上限，避免异常时永久挂起 */
        const val STREAM_JOIN_TIMEOUT_MS = 5_000L
    }
}
