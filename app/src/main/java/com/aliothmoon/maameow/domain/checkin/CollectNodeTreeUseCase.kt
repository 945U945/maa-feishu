package com.aliothmoon.maameow.domain.checkin

import android.content.Context

/**
 * 控件树采集用例（「控件探针」）。
 *
 * ## 为什么需要这个功能
 *
 * 飞书等应用对打卡页面启用了**防截屏（FLAG_SECURE）**：
 * 系统截图接口会直接返回失败或纯黑图像，导致图像模板匹配完全不可用。
 * 这是 Android 的安全机制，无法通过代码绕过。
 *
 * 但无障碍服务读取的是**控件树**（结构化的文字、ID、坐标），
 * 完全不经过截屏通道，因此不受防截屏限制。
 *
 * 本用例把当前界面的控件树整理成可读文本，
 * 用户导出后即可用于精确配置打卡规则 —— 相当于"用文字代替截图"。
 *
 * ## 输出格式
 *
 * 采用缩进树 + 关键字段的形式，例如：
 * ```
 * [0] android.widget.FrameLayout id=... bounds=0,0,1080,2340
 *   [1] android.widget.TextView text="打卡" id=btn_checkin clickable=true bounds=100,1800,980,1980
 * ```
 * 只输出有信息量的节点（有文字/ID/描述或可点击），避免刷屏。
 */
class CollectNodeTreeUseCase(
    private val isAccessibilityReady: () -> Boolean,
    private val dumpTree: () -> List<com.aliothmoon.maameow.service.NodeDump>,
    private val currentPackage: () -> String?,
    private val isPackageInstalled: suspend (String) -> Boolean,
    private val appContext: Context,
) {

    /** 采集结果。 */
    sealed interface Result {
        /** 成功，[text] 为可直接展示/导出的控件树文本 */
        data class Success(
            val text: String,
            val nodeCount: Int,
            val packageName: String?,
        ) : Result

        /** 无障碍服务未开启 */
        data object AccessibilityNotReady : Result

        /** 界面为空（可能不在任何应用界面） */
        data object EmptyTree : Result
    }

    /**
     * 采集当前界面的控件树。
     *
     * @param packageFilter 只保留该包名的节点，传 null 保留全部
     */
    suspend operator fun invoke(packageFilter: String? = null): Result {
        if (!isAccessibilityReady()) return Result.AccessibilityNotReady

        val nodes = dumpTree()
        if (nodes.isEmpty()) return Result.EmptyTree

        val filtered = if (packageFilter.isNullOrBlank()) {
            nodes
        } else {
            nodes.filter { it.packageName == packageFilter }
        }

        // 过滤后为空说明当前界面不是目标应用，此时回退输出全部，
        // 让用户能看到"实际停在了哪个界面"，便于排查
        val effective = filtered.ifEmpty { nodes }

        val text = buildString {
            appendLine("# 界面控件树快照")
            appendLine("# 采集时间：${java.time.LocalDateTime.now()}")
            appendLine("# 当前前台应用：${currentPackage() ?: "未知"}")
            appendLine("# 节点总数：${effective.size}")
            appendLine("#")
            appendLine("# 说明：把本文件内容回传，即可据此配置打卡规则。")
            appendLine("#       重点关注 clickable=true 且 text 含「打卡」的节点。")
            appendLine()
            for (node in effective) {
                append(buildNodeLine(node))
                appendLine()
            }
        }

        return Result.Success(
            text = text,
            nodeCount = effective.size,
            packageName = currentPackage(),
        )
    }

    /** 把单个节点格式化为一行带缩进的文本。 */
    private fun buildNodeLine(node: com.aliothmoon.maameow.service.NodeDump): String {
        val indent = "  ".repeat(node.depth.coerceAtMost(20))
        val sb = StringBuilder(indent)
        sb.append('[').append(node.depth).append("] ")
        sb.append(node.className.substringAfterLast('.'))

        if (node.text.isNotBlank()) {
            sb.append(" text=\"").append(node.text.replace("\n", "\\n")).append('"')
        }
        if (node.viewId.isNotBlank()) {
            sb.append(" id=").append(node.viewId)
        }
        if (node.contentDesc.isNotBlank()) {
            sb.append(" desc=\"").append(node.contentDesc.replace("\n", "\\n")).append('"')
        }
        if (node.clickable) sb.append(" clickable=true")
        if (!node.enabled) sb.append(" enabled=false")
        sb.append(" bounds=").append(node.bounds)
        return sb.toString()
    }

    /** 检查目标应用是否已安装，供配置界面提示用户。 */
    suspend fun checkTargetInstalled(packageName: String): Boolean =
        isPackageInstalled(packageName)
}
