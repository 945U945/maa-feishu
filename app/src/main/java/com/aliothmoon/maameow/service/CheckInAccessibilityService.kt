package com.aliothmoon.maameow.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.aliothmoon.maameow.domain.checkin.CheckInAction
import com.aliothmoon.maameow.domain.checkin.CheckInRule
import com.aliothmoon.maameow.domain.checkin.MatchMode
import com.aliothmoon.maameow.domain.checkin.NodeSelector
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * 打卡无障碍服务。
 *
 * 这是整个打卡能力的基石：Android 的无障碍服务可以读取当前界面的
 * **控件树**（文字、ID、坐标、可点击性），并代替用户执行点击。
 *
 * 相比截图匹配的优势：
 * - 不受防截屏（FLAG_SECURE）限制 —— 飞书等 App 只能靠这条路径
 * - 不受分辨率/DPI 影响，无需为每个机型录制模板
 * - 直接拿到文字内容，识别打卡结果是"读"而不是"猜"
 *
 * 所有对外能力都通过 [instance] 暴露给业务层调用；
 * 服务本身只负责查找与操作控件，不含任何业务判断。
 */
class CheckInAccessibilityService : AccessibilityService() {

    companion object {
        /**
         * 当前活跃的服务实例。
         *
         * 无障碍服务由系统托管，生命周期不受 App 控制，
         * 因此用静态引用让业务层拿到。断开时必须清空，避免泄漏与误用。
         */
        @Volatile
        var instance: CheckInAccessibilityService? = null
            private set

        /** 服务是否已连接（用户是否在系统设置里开启了本应用的无障碍权限）。 */
        fun isReady(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Timber.i("打卡无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 只做页面变化通知，不在此处做业务处理：
        // 业务由 CheckInEngine 主动轮询控件树完成，避免事件洪泛导致逻辑竞态。
        // 缓存最新事件来源包名，供引擎判断"当前是否已切到目标 App"。
        event?.packageName?.let { _currentPackage = it.toString() }
    }

    override fun onInterrupt() {
        Timber.w("打卡无障碍服务被中断")
    }

    override fun onDestroy() {
        instance = null
        Timber.i("打卡无障碍服务已断开")
    }

    /** 最近一次界面事件的来源包名，用于确认 App 是否已切到前台。 */
    @Volatile
    private var _currentPackage: String? = null

    val currentPackage: String? get() = _currentPackage

    /** 取当前活跃窗口的根控件节点，失败返回 null（如界面切换瞬间）。 */
    private fun rootNode(): AccessibilityNodeInfo? = try {
        rootInActiveWindow
    } catch (e: Exception) {
        Timber.w(e, "获取根控件失败")
        null
    }

    /**
     * 按选择器查找控件。
     *
     * 匹配规则：选择器里填写的条件**全部填写项都要满足**（与关系），
     * 这样"文字=打卡 且 可点击"能精确定位到按钮本身，
     * 而不是页面上任意一处出现"打卡"两个字的文本标签。
     *
     * @param selector 选择条件
     * @param packageName 限定只在该包名的窗口内查找，传 null 表示不限制
     */
    fun findNode(selector: NodeSelector, packageName: String? = null): AccessibilityNodeInfo? {
        val root = rootNode() ?: return null
        val hits = mutableListOf<AccessibilityNodeInfo>()
        collectMatches(root, selector, packageName, hits)
        return hits.getOrNull(selector.index.coerceAtLeast(0))
    }

    /**
     * 深度优先遍历控件树，收集全部匹配项。
     *
     * 遍历时对每个节点都尝试回收，避免大量节点对象造成的 native 内存压力；
     * 但命中的节点必须保留（返回给调用方使用），因此命中项不回收。
     */
    private fun collectMatches(
        node: AccessibilityNodeInfo,
        selector: NodeSelector,
        packageName: String?,
        out: MutableList<AccessibilityNodeInfo>,
    ) {
        // 包名不符直接跳过整棵子树（多窗口场景下能显著减少遍历量）
        if (packageName != null && node.packageName?.toString() != packageName) {
            return
        }

        if (matches(node, selector)) {
            out += AccessibilityNodeInfo.obtain(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectMatches(child, selector, packageName, out)
        }
    }

    /** 判断单个节点是否满足选择器的全部条件。 */
    private fun matches(node: AccessibilityNodeInfo, selector: NodeSelector): Boolean {
        if (selector.clickableOnly && !node.isClickable) {
            // 允许"可点击的祖先"命中：很多 App 把 onClick 挂在父容器上，
            // 文字却在子 TextView 里，只判自身 isClickable 会漏掉。
            if (!hasClickableAncestor(node)) return false
        }

        selector.text?.let { expected ->
            val actual = node.text?.toString() ?: return false
            if (!matchText(actual, expected, selector.matchMode)) return false
        }

        selector.viewId?.let { expected ->
            val actual = node.viewIdResourceName ?: return false
            if (actual != expected) return false
        }

        selector.contentDesc?.let { expected ->
            val actual = node.contentDescription?.toString() ?: return false
            if (!matchText(actual, expected, selector.matchMode)) return false
        }

        // 三个条件都没填的选择器会匹配一切，视为无效
        if (selector.text == null && selector.viewId == null && selector.contentDesc == null) {
            return false
        }

        return true
    }

    /** 向上查找最近的可点击祖先节点（最多 4 层，避免无谓遍历）。 */
    private fun hasClickableAncestor(node: AccessibilityNodeInfo?): Boolean {
        var cur = node?.parent
        var depth = 0
        while (cur != null && depth < 4) {
            if (cur.isClickable) return true
            cur = cur.parent
            depth++
        }
        return false
    }

    /** 文本匹配。 */
    private fun matchText(actual: String, expected: String, mode: MatchMode): Boolean = when (mode) {
        MatchMode.EXACT -> actual == expected
        MatchMode.CONTAINS -> actual.contains(expected)
        MatchMode.REGEX -> Regex(expected).containsMatchIn(actual)
    }

    /**
     * 点击控件。
     *
     * 优先用控件自身的 [AccessibilityNodeInfo.performAction]，
     * 这是最精确的方式；若失败（部分 App 拒绝无障碍点击），
     * 回退到手势点击坐标。
     */
    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        // 1. 先找可点击的祖先（文字节点常常自身不可点）
        var target: AccessibilityNodeInfo? = node
        var depth = 0
        while (target != null && !target.isClickable && depth < 4) {
            target = target.parent
            depth++
        }

        target?.takeIf { it.isClickable }?.let {
            if (it.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Timber.d("通过 performAction 点击成功")
                return true
            }
        }

        // 2. 回退手势点击
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty) return false
        return clickCoordinate(rect.exactCenterX(), rect.exactCenterY())
    }

    /**
     * 手势点击指定坐标。
     *
     * API 24+ 支持 [dispatchGesture]，低版本设备不可用（会返回 false）。
     */
    fun clickCoordinate(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Timber.w("系统版本低于 7.0，不支持手势点击")
            return false
        }
        val path = android.graphics.Path().apply { moveTo(x, y) }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0L, 60L))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** 读取控件文字（含内容描述），用于判定打卡结果。 */
    fun readNodeText(node: AccessibilityNodeInfo): String {
        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        return when {
            text.isNotEmpty() && desc.isNotEmpty() -> "$text $desc"
            text.isNotEmpty() -> text
            else -> desc
        }
    }

    /**
     * 等待控件出现，用于"页面加载完再操作"。
     *
     * @return 命中的控件，超时返回 null
     */
    suspend fun awaitNode(
        selector: NodeSelector,
        packageName: String? = null,
        timeoutMs: Long,
        pollIntervalMs: Long = 300L,
    ): AccessibilityNodeInfo? = withContext(Dispatchers.Default) {
        withTimeoutOrNull(timeoutMs) {
            while (true) {
                findNode(selector, packageName)?.let { return@withTimeoutOrNull it }
                delay(pollIntervalMs)
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }
    }

    /**
     * 转储当前界面控件树，供"控件探针"功能使用。
     *
     * 这是解决用户无法截图问题的关键：
     * 飞书开启防截屏后拿不到图片，但控件树是结构中文本，
     * 导出后即可让用户回传，用于精确配置打卡规则。
     *
     * @param maxDepth 最大递归深度，防止超深树导致输出爆炸
     */
    fun dumpTree(maxDepth: Int = 25): List<NodeDump> {
        val root = rootNode() ?: return emptyList()
        val out = mutableListOf<NodeDump>()
        dumpRecursive(root, 0, maxDepth, out)
        return out
    }

    private fun dumpRecursive(
        node: AccessibilityNodeInfo,
        depth: Int,
        maxDepth: Int,
        out: MutableList<NodeDump>,
    ) {
        if (depth > maxDepth) return
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        val id = node.viewIdResourceName.orEmpty()

        // 只输出"有意义"的节点：有文字、有ID、或有描述，或可点击
        if (text.isNotEmpty() || desc.isNotEmpty() || id.isNotEmpty() || node.isClickable) {
            out += NodeDump(
                depth = depth,
                className = node.className?.toString().orEmpty(),
                text = text,
                viewId = id,
                contentDesc = desc,
                clickable = node.isClickable,
                enabled = node.isEnabled,
                bounds = "${rect.left},${rect.top},${rect.right},${rect.bottom}",
                packageName = node.packageName?.toString().orEmpty(),
            )
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { dumpRecursive(it, depth + 1, maxDepth, out) }
        }
    }
}

/**
 * 控件树单节点快照，用于序列化导出。
 *
 * 字段刻意保持扁平简单，方便直接转 JSON 给用户复制回传。
 */
@androidx.annotation.Keep
data class NodeDump(
    val depth: Int,
    val className: String,
    val text: String,
    val viewId: String,
    val contentDesc: String,
    val clickable: Boolean,
    val enabled: Boolean,
    val bounds: String,
    val packageName: String,
)
