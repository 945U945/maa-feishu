package com.feishu.checkin.accessibility

import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityNodeInfo
import com.feishu.checkin.checkin.model.StepSelector
import timber.log.Timber

/**
 * 无障碍能力的状态查询与检视工具。
 *
 * 单独抽出来是因为「无障碍服务是否已开启」这个判断在多个地方要用：
 * 健康检查卡片、打卡前置校验、控件探针 —— 但它们的调用方都不该
 * 依赖 [CheckInAccessibilityService] 这个具体实现类
 * （服务可能尚未启动，持有它的引用不安全）。
 */
object AccessibilitySupport {

    /**
     * 无障碍服务是否已在本机开启。
     *
     * 直接读 Settings.Secure 而不是查 [CheckInAccessibilityService.instance]：
     * 后者为 null 有两种可能 —— 「没开启」或「已开启但当前进程还没连上服务」，
     * 两者对用户的提示完全不同，用 settings 判断语义更准确。
     */
    fun isEnabled(context: Context): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false

        val expected = "${context.packageName}/${CheckInAccessibilityService::class.java.name}"
        return enabledServices.split(':').any { it.equals(expected, ignoreCase = true) }
    }
}

/**
 * 在控件树中按 [StepSelector] 查找节点。
 *
 * 查找策略：
 * 1. 先在「无障碍最佳实践」的意义上做广度优先 —— 因为真正可点击的
 *    控件通常不在很深的位置，BFS 能更快命中，也避免被深层装饰节点误导。
 * 2. 每个节点依次用 selector 的各字段做匹配，任一字段命中即算候选。
 * 3. 命中多个时按 [StepSelector.index] 取第 index 个。
 *
 * 之所以不用 `findAccessibilityNodeInfosByText` 这类系统 API：
 * 它们只支持文本匹配，而飞书很多按钮只有 contentDescription 或 viewId，
 * 且系统 API 无法处理「文本包含」与多条件组合。
 */
internal object NodeFinder {

    private const val MAX_DEPTH = 60

    /**
     * 查找并返回匹配的节点列表。
     *
     * @param root 起始根节点（通常是 rootInActiveWindow）
     */
    fun findAll(root: AccessibilityNodeInfo?, selector: StepSelector): List<AccessibilityNodeInfo> {
        if (root == null || !selector.isValid) return emptyList()
        val matched = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.addLast(root to 0)
        while (queue.isNotEmpty()) {
            val (node, depth) = queue.removeFirst()
            if (depth > MAX_DEPTH) continue
            if (matches(node, selector)) matched.add(node)
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                queue.addLast(child to depth + 1)
            }
        }
        return matched
    }

    /** 按 index 取一个匹配节点 */
    fun findOne(root: AccessibilityNodeInfo?, selector: StepSelector): AccessibilityNodeInfo? {
        val all = findAll(root, selector)
        return all.getOrNull(selector.index.coerceAtLeast(0))
    }

    /** 单个节点是否满足 selector 的任一条件 */
    private fun matches(node: AccessibilityNodeInfo, selector: StepSelector): Boolean {
        if (selector.text.isNotEmpty()) {
            val t = node.text?.toString()
            if (!TextUtils.equals(t, selector.text)) {
                // 文本完全匹配不中时，退一步按包含判断：
                // 飞书按钮文案常带空格或不可见字符
                if (t == null || !t.trim().equals(selector.text.trim(), ignoreCase = true)) {
                    // 继续检查其它字段
                } else {
                    return true
                }
            } else {
                return true
            }
        }
        if (selector.textContains.isNotEmpty()) {
            val t = node.text?.toString().orEmpty()
            if (t.contains(selector.textContains, ignoreCase = true)) return true
        }
        if (selector.viewId.isNotEmpty()) {
            val id = node.viewIdResourceName
            if (id != null && (id == selector.viewId || id.endsWith("/${selector.viewId.substringAfterLast('/')}"))) {
                return true
            }
        }
        if (selector.className.isNotEmpty()) {
            if (node.className?.toString() == selector.className) return true
        }
        if (selector.contentDesc.isNotEmpty()) {
            val d = node.contentDescription?.toString()
            if (d != null && d.contains(selector.contentDesc, ignoreCase = true)) return true
        }
        return false
    }

    /**
     * 从节点向上回溯，找到第一个真正可点击的祖先。
     *
     * 无障碍自动化里极常见的坑：文本所在的 TextView 本身不可点击，
     * 点击事件由外层容器处理。直接对 TextView 调 performAction 会静默失败。
     */
    fun findClickableAncestor(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var cur = node
        var hops = 0
        while (cur != null && hops < 12) {
            if (cur.isClickable && cur.isEnabled) return cur
            cur = cur.parent
            hops++
        }
        return null
    }
}

/**
 * 把控件树 dump 成可读文本，供「控件探针」页面使用。
 *
 * 输出格式刻意做成逐行缩进，便于直接贴进方案里当 selector，
 * 也方便用户肉眼比对飞书改版前后差异。
 */
internal object TreeDumper {

    fun dump(root: AccessibilityNodeInfo?, maxNodes: Int = 1200): String {
        if (root == null) return ""
        val sb = StringBuilder()
        var count = 0
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (count >= maxNodes) return
            count++
            sb.append("  ".repeat(depth))
            node.className?.toString()?.substringAfterLast('.')?.let { sb.append(it) }
            node.viewIdResourceName?.substringAfterLast('/')?.let { if (it.isNotEmpty()) sb.append(" #").append(it) }
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let { sb.append(" text=\"").append(it).append('"') }
            node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                ?.let { sb.append(" desc=\"").append(it).append('"') }
            if (node.isClickable) sb.append(" [clickable]")
            if (node.isScrollable) sb.append(" [scrollable]")
            if (!node.isEnabled) sb.append(" [disabled]")
            sb.append('\n')
            for (i in 0 until node.childCount) {
                val c = node.getChild(i) ?: continue
                walk(c, depth + 1)
            }
        }
        runCatching { walk(root, 0) }
            .onFailure { Timber.w(it, "dump 控件树失败") }
        if (count >= maxNodes) sb.append("… (已截断，共 $maxNodes 个节点)\n")
        return sb.toString()
    }
}
