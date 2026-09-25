package com.feishu.checkin.core.shizuku

import timber.log.Timber

/**
 * 飞书入口探测器。
 *
 * ## 为什么需要「探测」而不是「猜」
 *
 * 在实现深链方案前，必须先弄清一件事：
 * **飞书有没有一个可以直达考勤页的组件？**
 *
 * 有两种可能：
 * - 有专门的 Activity（如 `AttendanceActivity`）且能裸启动
 * - 只能在网关注册表中注册，必须走 AppLink 携带参数
 *
 * 这两条路实现完全不同，靠猜必然做错。探测一次就能确定，
 * 而且结果可以反复复用。
 *
 * ## 探测的四个问题
 *
 * 1. **飞书装了哪个版本、包名是什么** —— 是否品牌定制版
 * 2. **有哪些 Activity 名字与考勤相关** —— 是否存在直达入口
 * 3. **注册了哪些 scheme / host** —— AppLink 会落到哪个组件
 * 4. **当前前台 Activity 是谁** —— 建立"落点校验"的基线
 *
 * 第 4 项是后续运行时最有用的能力：任何时刻都能知道飞书在哪个页面。
 *
 * ## 输出设计
 *
 * 探测结果既写文件（便于导出分析）又返回结构化对象（便于 UI 展示）。
 * 全部原始输出也保留 —— 因为解析规则可能漏掉有价值的信息，
 * 而原始输出不会。留一手总比事后重跑省事。
 */
class FeishuEntryProbe(
    private val shell: ShizukuShell,
) {

    /**
     * 执行完整探测。
     *
     * 每一步都是独立的，某一步失败不影响其它步 ——
     * 探测本身就是为了发现「哪里不对劲」，不应该因为一处失败就全废。
     */
    fun probe(packageName: String): ProbeReport {
        val steps = mutableListOf<ProbeStep>()

        // ── 1. 包基本信息 ──
        val pkgInfo = shell.exec(listOf("dumpsys", "package", packageName))
        steps += ProbeStep(
            title = "包信息 (dumpsys package)",
            detail = summarizePackage(pkgInfo),
            raw = pkgInfo.stdout,
            success = pkgInfo.isSuccess,
        )

        // ── 2. 所有 Activity ──
        //
        // 注意必须用 `sh -c` 包一层。Shizuku.newProcess 起的是**单个进程**，
        // 不经过 shell 解析 —— 命令里的 `|` 会被当成普通参数传给 dumpsys，
        // 而不是建立管道。只有交给 sh 解释才会得到预期的行为。
        val activities = shell.exec(
            listOf(
                "sh", "-c",
                "dumpsys package $packageName | grep -i activity",
            ),
        )
        steps += ProbeStep(
            title = "Activity 清单",
            detail = pickInterestingActivities(activities.stdout),
            raw = activities.stdout,
            success = activities.stdout.isNotBlank(),
        )

        // ── 3. 考勤相关组件 ──
        val attendance = shell.exec(
            listOf(
                "sh", "-c",
                "dumpsys package $packageName | grep -iE 'attendance|checkin|check_in|punch|kaoqin'",
            ),
        )
        steps += ProbeStep(
            title = "考勤相关组件（关键词匹配）",
            detail = if (attendance.stdout.isBlank()) {
                "未找到名字含 attendance/checkin/punch 的组件 —— " +
                    "说明考勤很可能是**网关注册的应用**，必须走 AppLink，无法裸启动"
            } else {
                attendance.stdout.lineSequence()
                    .filter { it.isNotBlank() }
                    .take(30)
                    .joinToString("\n")
            },
            raw = attendance.stdout,
            success = true,
        )

        // ── 4. scheme / host 注册 ──
        val schemes = shell.exec(
            listOf("sh", "-c", "dumpsys package $packageName | grep -iE 'scheme|applink|host='"),
        )
        steps += ProbeStep(
            title = "scheme 与 host 注册",
            detail = if (schemes.stdout.isBlank()) "未匹配到 scheme 注册信息" else {
                schemes.stdout.lineSequence()
                    .filter { it.isNotBlank() }
                    .take(30)
                    .joinToString("\n")
            },
            raw = schemes.stdout,
            success = true,
        )

        // ── 5. 当前前台页面 ──
        val foreground = currentForegroundActivity()
        steps += ProbeStep(
            title = "当前前台 Activity",
            detail = foreground ?: "读取失败（可能是 ROM 限制或 dumpsys 输出格式差异）",
            raw = foreground ?: "",
            success = foreground != null,
        )

        Timber.i("探测完成，共 %d 步", steps.size)
        return ProbeReport(packageName = packageName, steps = steps)
    }

    /**
     * 读当前前台 Activity 的完整类名。
     *
     * ## 这是 Shizuku 在打卡场景里最有价值的单一能力
     *
     * 无障碍只能看到当前应用的节点树，**无法知道这是哪个 Activity**。
     * 于是「深链是否落在考勤页」只能靠文案猜 —— 而飞书首页和考勤页
     * 都有「打卡」「考勤」这类字眼，猜错就会在错误页面上操作。
     *
     * 有了前台 Activity 名，判断就是确定的：
     * 落在 `...AttendanceActivity` 就是对了，落在 `...MainActivity`
     * 就是没跳过去。**这比文案匹配可靠一个量级。**
     *
     * ## 输出格式的坑
     *
     * `dumpsys activity activities` 的输出格式在不同 Android 版本
     * 与不同 ROM 上差异很大。这里**不假设固定格式**，而是用多路匹配：
     * 优先找 `mResumedActivity`（最准），退回 `topResumedActivity`，
     * 再退回 `ResumedActivity`。
     *
     * 三路都试不到就返回 null，由上层降级到文案判定 ——
     * 不猜、不硬解析，避免给出错误的"确定结论"。
     */
    fun currentForegroundActivity(): String? {
        val result = shell.exec(
            listOf("sh", "-c", "dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity|ResumedActivity'"),
        )
        val text = result.stdout.ifBlank { result.stderr }
        if (text.isBlank()) {
            // 上面那条在部分 ROM 上会被 grep 的退出码影响，退回全量输出自己找
            val full = shell.exec(listOf("dumpsys", "activity", "activities"))
            return parseForeground(full.stdout)
        }
        return parseForeground(text)
    }

    /**
     * 从 dumpsys 输出里解析前台 Activity。
     *
     * 典型行形如：
     * ```
     * mResumedActivity: ActivityRecord{1a2b3c u0 com.ss.android.lark/.main.MainActivity t123}
     * ```
     * 目标是从 `{...}` 里取出 `包名/Activity` 这段。
     */
    private fun parseForeground(text: String): String? {
        val patterns = listOf(
            "mResumedActivity",
            "topResumedActivity",
            "ResumedActivity",
        )
        for (p in patterns) {
            val line = text.lineSequence().firstOrNull { it.contains(p) } ?: continue

            // 取大括号里的内容，再从中抠出 包名/Activity
            val braceContent = line.substringAfter('{', "").substringBefore('}', "")
            if (braceContent.isEmpty()) continue

            // 格式：<hash> <user> <pkg>/<activity> <task>
            // 找含 '/' 的那一段，且要含 'com.' 以排除 <task> 之类的干扰
            braceContent.split(' ')
                .firstOrNull { it.contains('/') && it.contains('.') }
                ?.let { return it }
        }
        return null
    }

    /** 包信息摘要（只保留有用字段，避免几 MB 的原始输出糊满界面） */
    private fun summarizePackage(result: ShellResult): String {
        if (!result.isSuccess && result.stdout.isBlank()) {
            return "读取失败：${result.stderr.take(200)}"
        }
        val lines = result.stdout.lineSequence()
        val wanted = listOf(
            "versionName", "versionCode", "firstInstallTime", "lastUpdateTime",
            "targetSdk", "minSdk", "primaryCpuAbi",
        )
        val found = lines.filter { line ->
            wanted.any { line.trim().startsWith(it) }
        }.take(12).joinToString("\n")

        return found.ifBlank { "未匹配到常见字段，但命令执行成功（见原始输出）" }
    }

    /** 从 Activity 清单里挑出值得关注的（含关键词的） */
    private fun pickInterestingActivities(raw: String): String {
        if (raw.isBlank()) return "未获取到 Activity 清单"

        val keywords = listOf(
            "attendance", "checkin", "check_in", "punch", "kaoqin",
            "workplace", "main", "splash", "webview", "browser",
        )

        val interesting = raw.lineSequence()
            .filter { line -> keywords.any { line.contains(it, ignoreCase = true) } }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(40)
            .toList()

        val nonBlankLines = raw.lineSequence().count { it.isNotBlank() }

        return buildString {
            append("grep 输出共 ").append(nonBlankLines).append(" 行非空；\n")
            append("其中含关键词的 ").append(interesting.size).append(" 条：\n")
            if (interesting.isEmpty()) {
                append("（无 —— 说明 Activity 命名不含这些关键词，需要人工看原始输出）")
            } else {
                append(interesting.joinToString("\n"))
            }
        }
    }
}

/** 单个探测步骤的结果 */
data class ProbeStep(
    val title: String,
    val detail: String,
    val raw: String,
    val success: Boolean,
)

/** 完整探测报告 */
data class ProbeReport(
    val packageName: String,
    val steps: List<ProbeStep>,
    val generatedAt: Long = System.currentTimeMillis(),
) {
    /**
     * 导出为可读文本。
     *
     * 保留原始输出 —— 解析规则可能漏掉信息，
     * 而原始输出不会。用户把这份文本发出来就能完整复现问题。
     */
    fun toReadableText(): String = buildString {
        appendLine("=".repeat(70))
        appendLine("飞书打卡入口探测报告")
        appendLine("包名   : $packageName")
        appendLine("时间   : ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date(generatedAt))}")
        appendLine("=".repeat(70))
        appendLine()

        steps.forEachIndexed { index, step ->
            appendLine("─".repeat(70))
            appendLine("[${index + 1}] ${step.title}  ${if (step.success) "OK" else "失败"}")
            appendLine("─".repeat(70))
            appendLine(step.detail)
            appendLine()
            if (step.raw.isNotBlank() && step.raw != step.detail) {
                appendLine("--- 原始输出 ---")
                appendLine(step.raw)
                appendLine()
            }
        }
    }
}
