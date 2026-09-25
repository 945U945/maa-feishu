package com.feishu.checkin.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.feishu.checkin.checkin.model.StepSelector
import com.feishu.checkin.checkin.model.SwipeDirection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * 本项目唯一的自动化执行通道。
 *
 * ## 为什么用无障碍而不是截图识别
 *
 * 飞书对考勤等关键页面设置了 `FLAG_SECURE`，截图 API 会返回全黑图像，
 * 图像识别路线物理上不可行。因此改为读取控件树定位元素：
 * 它不受 FLAG_SECURE 影响，且定位精度远高于像素匹配 ——
 * 方案不会因为换主题、改字号、系统深色模式而失效。
 *
 * ## 生命周期与外部调用
 *
 * 系统会在用户开启服务后启动本类。外部（如 [com.feishu.checkin.checkin.engine.CheckInEngine]）
 * 通过 [instance] 拿到当前实例，实现步骤执行。
 * 之所以用静态引用而非绑定服务，是因为无障碍服务的连接由系统管理，
 * 应用无法主动 bind —— 这是该 API 的固有约束。
 */
class CheckInAccessibilityService : AccessibilityService() {

    companion object {
        /**
         * 当前活跃实例。
         *
         * 使用前提：调用方必须先通过 [AccessibilitySupport.isEnabled] 确认服务已开，
         * 否则这里可能为 null（服务未启用）或持有已失效的实例（服务被系统回收）。
         */
        @Volatile
        var instance: CheckInAccessibilityService? = null
            private set

        /** 服务是否已连接（可用于 UI 实时反映状态） */
        fun isConnected(): Boolean = instance != null
    }

    /** 当前前台应用包名，供 UI 判断「是否在飞书里」 */
    private val _currentPackage = MutableStateFlow<String?>(null)
    val currentPackage: StateFlow<String?> = _currentPackage.asStateFlow()

    /**
     * 页面内容变化信号。
     *
     * 步骤执行时需要「等某个控件出现」，靠轮询控件树效率低且时机不准。
     * 这里把系统的内容变化事件转成一个可等待的信号，
     * 让等待逻辑既及时又不空转。
     */
    @Volatile
    private var contentChangeSignal = CompletableDeferred<Unit>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Timber.i("无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString()
                if (pkg != null && pkg != _currentPackage.value) {
                    _currentPackage.value = pkg
                    Timber.d("前台应用切换: %s", pkg)
                }
                signalContentChanged()
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            -> signalContentChanged()
        }
    }

    private fun signalContentChanged() {
        val old = contentChangeSignal
        contentChangeSignal = CompletableDeferred()
        if (!old.isCompleted) old.complete(Unit)
    }

    /**
     * 等待一次页面内容变化。
     *
     * 返回的 Deferred 在事件到达时完成；调用方自行加超时。
     * 若等待期间事件已发生，这里会立即返回已完成的信号，不会漏掉。
     */
    fun awaitContentChange(): CompletableDeferred<Unit> = contentChangeSignal

    override fun onInterrupt() {
        Timber.w("无障碍服务被中断")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        Timber.i("无障碍服务已断开")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ────────────────────── 对外能力 ──────────────────────

    /** 当前活动窗口的控件树根 */
    fun rootNode(): AccessibilityNodeInfo? = runCatching { rootInActiveWindow }.getOrNull()

    /** 采集当前界面控件树的可读文本（控件探针用） */
    fun dumpCurrentTree(): String = TreeDumper.dump(rootNode())

    /**
     * 查找一个控件。
     *
     * 优先返回「节点自身或其可点击祖先」—— 因为实际点击要靠祖先，
     * 把这一步收在这里，调用方就不必关心无障碍的点击继承规则。
     */
    fun findNode(selector: StepSelector): AccessibilityNodeInfo? =
        NodeFinder.findOne(rootNode(), selector)

    /**
     * 点击一个控件。
     *
     * 两级策略：
     * 1. 优先用 `ACTION_CLICK` —— 这是无障碍原生方式，不产生真实触摸事件，
     *    在部分机型上更稳定，也不受屏幕遮挡影响。
     * 2. 失败时回退到坐标手势点击 —— 处理「控件声明不可点击但设有 onClick」
     *    这类飞书自绘控件的特殊情况。
     *
     * @return 是否点击成功
     */
    fun click(selector: StepSelector): Boolean {
        val node = findNode(selector) ?: run {
            Timber.w("未找到控件: %s", selector.describe())
            return false
        }

        val clickable = NodeFinder.findClickableAncestor(node)
        if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Timber.d("ACTION_CLICK 成功: %s", selector.describe())
            return true
        }

        // 回退：按控件中心点做手势点击
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() <= 0 || rect.height() <= 0) {
            Timber.w("控件无有效边界，无法坐标点击: %s", selector.describe())
            return false
        }
        val ok = tapAt(rect.centerX().toFloat(), rect.centerY().toFloat())
        Timber.d("坐标点击 %s: %s", if (ok) "成功" else "失败", selector.describe())
        return ok
    }

    /**
     * 在指定坐标做一次点击手势。
     *
     * 需要 API 24+ 的 dispatchGesture；本项目 minSdk 28，恒可用。
     */
    private fun tapAt(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * 执行一次滑动。
     *
     * 用于翻页寻找入口。距离按屏幕比例计算，保证在不同分辨率设备上
     * 表现一致 —— 写死像素值会在小屏上滑过头、大屏上滑不动。
     */
    fun swipe(direction: SwipeDirection): Boolean {
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()
        val margin = 0.2f
        val (x1, y1, x2, y2) = when (direction) {
            SwipeDirection.UP -> arrayOf(w / 2, h * (1 - margin), w / 2, h * margin)
            SwipeDirection.DOWN -> arrayOf(w / 2, h * margin, w / 2, h * (1 - margin))
            SwipeDirection.LEFT -> arrayOf(w * (1 - margin), h / 2, w * margin, h / 2)
            SwipeDirection.RIGHT -> arrayOf(w * margin, h / 2, w * (1 - margin), h / 2)
        }
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 300L))
            .build()
        return runCatching { dispatchGesture(gesture, null, null) }
            .onFailure { Timber.w(it, "滑动手势失败") }
            .getOrDefault(false)
    }

    /** 执行返回 */
    fun pressBack(): Boolean =
        runCatching { performGlobalAction(GLOBAL_ACTION_BACK) }
            .onFailure { Timber.w(it, "返回操作失败") }
            .getOrDefault(false)

    /**
     * 在当前界面查找任意一段文字是否存在。
     *
     * 用于判定页面状态（如「已打卡」「打卡成功」），
     * 比定位具体控件更健壮 —— 文案通常比控件 ID 稳定。
     */
    fun containsAnyText(keywords: List<String>): String? {
        if (keywords.isEmpty()) return null
        val root = rootNode() ?: return null
        val texts = collectTexts(root)
        for (kw in keywords) {
            val hit = texts.firstOrNull { it.contains(kw, ignoreCase = true) }
            if (hit != null) return hit
        }
        return null
    }

    private fun collectTexts(root: AccessibilityNodeInfo): List<String> {
        val out = mutableListOf<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 1500) {
            val n = queue.removeFirst()
            visited++
            n.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
            n.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { queue.addLast(it) }
            }
        }
        return out
    }
}
