package com.aliothmoon.maameow.overlay

import android.app.Application
import android.content.ComponentCallbacks
import android.content.res.Configuration
import android.graphics.Color
import android.view.ViewGroup
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.aliothmoon.maameow.data.preferences.AppSettingsManager
import com.aliothmoon.maameow.domain.models.OverlayControlMode
import com.aliothmoon.maameow.domain.models.RunMode
import com.aliothmoon.maameow.domain.launch.LaunchPipeline
import com.aliothmoon.maameow.domain.launch.LaunchSession
import com.aliothmoon.maameow.overlay.border.BorderOverlayManager
import com.aliothmoon.maameow.presentation.LocalFloatingWindowContext
import com.aliothmoon.maameow.presentation.ProvideInputFocusManager
import com.aliothmoon.maameow.presentation.view.panel.ExpandedControlPanel
import com.aliothmoon.maameow.schedule.model.CountdownState
import com.aliothmoon.maameow.service.AccessibilityHelperService
import com.aliothmoon.maameow.theme.MaaMeowTheme
import com.aliothmoon.maameow.utils.Misc
import com.aliothmoon.maameow.utils.UiScale
import com.petterp.floatingx.FloatingX
import com.petterp.floatingx.assist.FxDisplayMode
import com.petterp.floatingx.assist.FxGravity
import com.petterp.floatingx.assist.FxScopeType
import com.petterp.floatingx.compose.enableComposeSupport
import com.petterp.floatingx.listener.IKeyBackListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

class OverlayController(
    private val context: Application,
    val borderOverlayManager: BorderOverlayManager,
    private val fwViewModelOwner: OverlayViewModelOwner,
    private val launchPipeline: LaunchPipeline,
    private val appSettings: AppSettingsManager
) {

    companion object {
        private const val MAIN_PANEL_TAG = "MP_TAG"
        private const val FLOAT_BALL_TAG = "FB_TAG"
        private const val ENABLE_LOG = false
    }

    private val _isLocked = MutableStateFlow(true)
    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()
    private var calculatedPanelLayout: Pair<Int, Int>? = null
    private var orientation = context.resources.configuration.orientation

    private val _signal = MutableSharedFlow<LaunchSession>(extraBufferCapacity = 1)
    val signal: SharedFlow<LaunchSession> = _signal.asSharedFlow()

    private var currentMode: OverlayControlMode = OverlayControlMode.ACCESSIBILITY
    private var pipelineStateJob: Job? = null

    private val _countdownState = MutableStateFlow<CountdownState>(CountdownState.Idle)
    val countdownState: StateFlow<CountdownState> = _countdownState.asStateFlow()

    var onCountdownClick: (() -> Unit)? = null
    private var tempCountdownListener: (() -> Unit)? = null

    fun setTemporaryCountdownListener(listener: (() -> Unit)?) {
        this.tempCountdownListener = listener
    }

    fun handleCountdownClick() {
        val temp = tempCountdownListener
        if (temp != null) {
            temp.invoke()
        } else {
            onCountdownClick?.invoke()
        }
    }

    fun updateCountdownState(state: CountdownState) {
        _countdownState.value = state
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val callback = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            val newLayout = calculatePanelLayout(newConfig)
            val changed = newConfig.orientation != orientation || newLayout != calculatedPanelLayout
            calculatedPanelLayout = newLayout
            orientation = newConfig.orientation
            if (changed) {
                applyPanelLayout(newConfig, newLayout)
            }
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onLowMemory() {
        }
    }

    fun setup() {
        context.registerComponentCallbacks(callback)
        scope.launch {
            appSettings.runMode.collect { mode ->
                when (mode) {
                    RunMode.FOREGROUND -> {
                        install()
                        // 触发初始布局计算
                        callback.onConfigurationChanged(context.resources.configuration)
                        startPipelineStateObserver()
                    }

                    RunMode.BACKGROUND -> {
                        stopPipelineStateObserver()
                        hideAll()
                        uninstall()
                    }
                }
            }
        }
    }

    private fun startPipelineStateObserver() {
        if (pipelineStateJob != null) return
        pipelineStateJob = scope.launch {
            var previous = launchPipeline.session.value
            launchPipeline.session.collect { session ->
                Timber.d("OverlayController: LaunchPipeline session changed: $session")
                onSessionChanged(previous, session)
                previous = session
            }
        }
    }

    private fun stopPipelineStateObserver() {
        pipelineStateJob?.cancel()
        pipelineStateJob = null
    }

    /** 仅「正在执行打卡」算运行态；倒计时/准备阶段不算 */
    private fun LaunchSession.isRunning(): Boolean =
        this is LaunchSession.InFlight &&
                (phase is LaunchSession.Phase.Preparing || phase is LaunchSession.Phase.Starting)

    private suspend fun onSessionChanged(previous: LaunchSession, current: LaunchSession) {
        // 仅在 show() 之后才响应运行态切换
        // 前台定时任务不会自动 show：未开控制层时这里直接 return，无悬浮球/音量键
        if (!_isActive.value) return

        val wasActive = previous.isRunning()
        val isActive = current.isRunning()

        when {
            // 进入运行态：隐藏主面板，显示悬浮控件
            isActive && !wasActive -> {
                hideMainPanel()
                when (currentMode) {
                    OverlayControlMode.FLOAT_BALL -> {
                        showFloatBall()
                        Timber.d("OverlayController: 打卡执行中，显示悬浮球")
                    }

                    OverlayControlMode.ACCESSIBILITY -> {
                        borderOverlayManager.show()
                        Timber.d("OverlayController: 打卡执行中，显示边框")
                    }
                }
            }

            // 离开运行态：恢复主面板
            wasActive && !isActive -> {
                when (currentMode) {
                    OverlayControlMode.ACCESSIBILITY -> {
                        borderOverlayManager.hide()
                        showMainPanel()
                        Timber.d("OverlayController: 打卡结束，隐藏边框，恢复主面板")
                    }

                    OverlayControlMode.FLOAT_BALL -> {
                        hideFloatBall()
                        showMainPanel()
                        Timber.d("OverlayController: 打卡结束，隐藏悬浮球，恢复主面板")
                    }
                }
                _signal.tryEmit(current)
            }
        }
    }

    fun getNewComposeView(): ComposeView {
        return ComposeView(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            fwViewModelOwner.let {
                setViewTreeLifecycleOwner(it)
                setViewTreeViewModelStoreOwner(it)
                setViewTreeSavedStateRegistryOwner(it)
            }
            calculatedPanelLayout?.let {
                layoutParams = ViewGroup.LayoutParams(
                    it.first,
                    it.second
                )
            }
            setContent {
                val themeMode by appSettings.themeMode.collectAsStateWithLifecycle()
                val fontSizeScale by appSettings.fontSizeScale.collectAsStateWithLifecycle()

                MaaMeowTheme(themeMode = themeMode) {
                    val baseDensity = LocalDensity.current
                    val configuration = LocalConfiguration.current
                    // 浮窗跟随主界面页面缩放；fontScale 钳制保持历史行为
                    val effectiveScale = AppSettingsManager.resolveFontSizeScale(
                        stored = fontSizeScale,
                        smallestWidthDp = configuration.smallestScreenWidthDp,
                        fontScale = baseDensity.fontScale,
                    )
                    CompositionLocalProvider(
                        LocalFloatingWindowContext provides true,
                        LocalDensity provides Density(
                            density = baseDensity.density * effectiveScale / 100f,
                            fontScale = UiScale.clampOverlayFontScale(baseDensity.fontScale),
                        )
                    ) {
                        ProvideInputFocusManager {
                            val isLocked by _isLocked.collectAsStateWithLifecycle()
                            ExpandedControlPanel(
                                onClose = ::onCloseControlPanel,
                                onHome = { Misc.bringAppToFront(context) },
                                isLocked = isLocked,
                                onLockToggle = { locked ->
                                    _isLocked.value = locked
                                    if (locked) {
                                        lockMainPanelPosition()
                                    } else {
                                        unlockMainPanelPosition()
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun calculatePanelLayout(config: Configuration): Pair<Int, Int> {
        val density = context.resources.displayMetrics.density
        val height =
            config.screenHeightDp * density * if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                0.85
            } else {
                0.6
            }
        val width = config.screenWidthDp * density * 0.85
        return width.toInt() to height.toInt()
    }

    private fun applyPanelLayout(config: Configuration, layout: Pair<Int, Int>) {
        val density = context.resources.displayMetrics.density
        val (width, height) = layout
        Timber.d("applyPanelLayout: $width x $height")

        FloatingX.controlOrNull(MAIN_PANEL_TAG)?.let {
            val show = it.isShow()
            if (show) {
                it.hide()
            }
            val screenWidth = config.screenWidthDp * density
            val screenHeight = config.screenHeightDp * density
            val centerX = (screenWidth - width) / 2
            val centerY = (screenHeight - height) / 2
            Timber.d("recalculatePanelLayout: screenSize=${screenWidth}x${screenHeight}, panelSize=${width}x${height}, center=($centerX, $centerY)")
            it.move(centerX, centerY)
            it.updateView(getNewComposeView())
            if (show) {
                it.show()
            }
        }

        FloatingX.controlOrNull(FLOAT_BALL_TAG)?.let {
            if (it.isShow()) {
                val screenWidth = config.screenWidthDp * density
                val screenHeight = config.screenHeightDp * density
                Timber.d("FLOAT_BALL_TAG $screenWidth x $screenHeight")
                val ballX = screenWidth
                val ballY = (screenHeight) / 2
                Timber.d("FLOAT_BALL_TAG $ballX x $ballY")
                it.move(ballX, ballY)
            }
        }
    }

    fun install() {
        if (!FloatingX.isInstalled(MAIN_PANEL_TAG)) {
            FloatingX.install {
                enableComposeSupport()
                setContext(context)
                setTag(MAIN_PANEL_TAG)
                setScopeType(FxScopeType.SYSTEM)
                setLayoutView(getNewComposeView())
                setEnableEdgeAdsorption(false)
                setEnableLog(ENABLE_LOG)
                setGravity(FxGravity.CENTER)
                setEnableSafeArea(false)
                setEnableAnimation(true)
                setDisplayMode(FxDisplayMode.ClickOnly)
                setEnableKeyBoardAdapt(true)
                setKeyBackListener(object : IKeyBackListener {
                    override fun onBackPressed(): Boolean {
                        // 消费返回，避免落到底层 Activity；关闭走面板「隐藏」按钮
                        return true
                    }
                })
            }
        }
        if (!FloatingX.isInstalled(FLOAT_BALL_TAG)) {
            FloatingX.install {
                enableComposeSupport()
                setContext(context)
                setTag(FLOAT_BALL_TAG)
                setScopeType(FxScopeType.SYSTEM)
                setLayoutView(
                    ComposeView(context).apply {
                        setBackgroundColor(Color.TRANSPARENT)
                        fwViewModelOwner.let {
                            setViewTreeLifecycleOwner(it)
                            setViewTreeViewModelStoreOwner(it)
                            setViewTreeSavedStateRegistryOwner(it)
                        }
                        setContent {
                            // 悬浮窗需要在后台强行保持监听，不使用 collectAsStateWithLifecycle
                            val session by launchPipeline.session.collectAsState()
                            val countdown by countdownState.collectAsState()
                            FloatBall(
                                onClick = {
                                    if (countdown is CountdownState.Counting) {
                                        handleCountdownClick()
                                    } else {
                                        onFloatBallClick()
                                    }
                                },
                                isRunning = session is LaunchSession.InFlight,
                                countdownSeconds = (countdown as? CountdownState.Counting)?.remainingSeconds,
                            )
                        }
                    }
                )
                setEnableLog(ENABLE_LOG)
                setEnableEdgeAdsorption(true)
                setGravity(FxGravity.RIGHT_OR_CENTER)
                setEnableAnimation(true)
            }
        }
        Timber.d("OverlayController: install completed")
    }

    fun uninstall() {
        FloatingX.uninstallAll()
        Timber.d("OverlayController: uninstall completed")
    }

    fun onFloatBallClick() {
        if (_countdownState.value is CountdownState.Counting) {
            handleCountdownClick()
            return
        }
        hideFloatBall()
        // 点击悬浮球 = 用户主动中断当前打卡流程
        launchPipeline.submit(com.aliothmoon.maameow.domain.launch.LaunchUserEvent.Cancel)
        showMainPanel()
    }

    fun onCloseControlPanel() {
        hideMainPanel()
        if (currentMode == OverlayControlMode.FLOAT_BALL) {
            showFloatBall()
        }
    }

    fun showMainPanel() {
        scope.launch {
            fwViewModelOwner.start()
            FloatingX.controlOrNull(MAIN_PANEL_TAG)?.show()
        }
    }

    fun hideMainPanel() {
        scope.launch {
            FloatingX.controlOrNull(MAIN_PANEL_TAG)?.hide()
            fwViewModelOwner.stop()
        }
    }

    fun showFloatBall() {
        FloatingX.controlOrNull(FLOAT_BALL_TAG)?.show()
    }

    fun hideFloatBall() {
        FloatingX.controlOrNull(FLOAT_BALL_TAG)?.hide()
    }

    fun lockMainPanelPosition() {
        FloatingX.controlOrNull(MAIN_PANEL_TAG)?.updateConfig {
            setDisplayMode(FxDisplayMode.ClickOnly)
        }
    }

    fun unlockMainPanelPosition() {
        FloatingX.controlOrNull(MAIN_PANEL_TAG)?.updateConfig {
            setDisplayMode(FxDisplayMode.Normal)
        }
    }

    /**
     * 首页手动启动控制层后才 _isActive
     * 定时/LAUNCH_PROFILE 前台静默启动不会走这里，需用户事先 show 过才能在 RUNNING 时出球/边框
     */
    fun show(mode: OverlayControlMode = OverlayControlMode.ACCESSIBILITY) {
        if (appSettings.runMode.value != RunMode.FOREGROUND) {
            Timber.w("OverlayController: 非前台模式，忽略 show 请求")
            return
        }
        currentMode = mode

        when (mode) {
            OverlayControlMode.ACCESSIBILITY -> {
                // 快捷键模式：隐藏悬浮球，注册音量键监听，显示主面板
                hideFloatBall()
                registerVolumeKeyListener()
                showMainPanel()
                Timber.d("OverlayController: 快捷键模式已启动")
            }

            OverlayControlMode.FLOAT_BALL -> {
                // 悬浮球模式：注销音量键监听，显示悬浮球
                unregisterVolumeKeyListener()
                showFloatBall()
                Timber.d("OverlayController: 悬浮球模式已启动")
            }
        }
        _isActive.value = true
    }

    suspend fun applyMode(mode: OverlayControlMode) {
        if (currentMode == mode) return

        Timber.d("FloatWindowCompose: 切换模式 $currentMode -> $mode")

        // 隐藏当前模式的 UI
        when (currentMode) {
            OverlayControlMode.ACCESSIBILITY -> {
                // 旧模式是快捷键模式，隐藏边框（如果正在显示）
                borderOverlayManager.hide()
            }

            OverlayControlMode.FLOAT_BALL -> {
                // 旧模式是悬浮球模式，隐藏悬浮球
                hideFloatBall()
            }
        }

        // 应用新模式
        currentMode = mode
        when (mode) {
            OverlayControlMode.ACCESSIBILITY -> {
                registerVolumeKeyListener()
                if (launchPipeline.session.value.isRunning()) {
                    borderOverlayManager.show()
                }
            }

            OverlayControlMode.FLOAT_BALL -> {
                unregisterVolumeKeyListener()
                showFloatBall()
            }
        }
    }

    suspend fun hideAll() {
        hideMainPanel()
        hideFloatBall()
        borderOverlayManager.hide()
        unregisterVolumeKeyListener()
        _isActive.value = false
    }

    fun toggleMainPanel() {
        val mainPanelShowing = FloatingX.controlOrNull(MAIN_PANEL_TAG)?.isShow() == true
        if (mainPanelShowing) {
            hideMainPanel()
        } else {
            showMainPanel()
        }
    }

    fun registerVolumeKeyListener() {
        AccessibilityHelperService.onVolumeUpDownPressed.set {
            scope.launch {
                toggleMainPanel()
                // 音量键 = 用户主动中断当前打卡流程
                launchPipeline.submit(com.aliothmoon.maameow.domain.launch.LaunchUserEvent.Cancel)
            }
        }
    }

    fun unregisterVolumeKeyListener() {
        AccessibilityHelperService.onVolumeUpDownPressed.set(null)
    }

}
