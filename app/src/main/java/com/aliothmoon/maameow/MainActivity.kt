package com.aliothmoon.maameow

import android.content.Intent
import android.os.Bundle
import android.view.ViewTreeObserver
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.aliothmoon.maameow.data.preferences.AppSettingsManager
import com.aliothmoon.maameow.presentation.ProvideInputFocusManager
import com.aliothmoon.maameow.presentation.navigation.AppNavigation
import com.aliothmoon.maameow.theme.MaaMeowTheme
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import timber.log.Timber

/**
 * 应用主 Activity。
 *
 * 相比原 MAA-Meow 工程，移除了：
 * - 画中画（游戏后台预览专有）
 * - Pallas 醉酒彩蛋（游戏梗，与打卡无关）
 * - 成就上报
 * - MAA 任务状态驱动的常亮逻辑（改为始终允许系统正常休眠，
 *   打卡是瞬时动作，不需要长时间保持屏幕常亮）
 *
 * 保留了启动画面、主题跟随、字体缩放等通用能力。
 */
class MainActivity : AppCompatActivity() {

    @Volatile
    private var isUiReady: Boolean = false

    private val appSettingsManager: AppSettingsManager by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition { !isUiReady }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        lifecycleScope.launch {
            (application as MaaApplication).awaitReady()
            delegate.localNightMode = appSettingsManager.themeMode.value.toAppCompatNightMode()
            initializeUi()
        }
    }

    private fun initializeUi() {
        window.decorView.viewTreeObserver.addOnPreDrawListener(object :
            ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                isUiReady = true
                window.decorView.viewTreeObserver.removeOnPreDrawListener(this)
                return true
            }
        })

        setContent {
            val themeMode by appSettingsManager.themeMode.collectAsStateWithLifecycle()
            val useSystemMonetColor by appSettingsManager.useSystemMonetColor.collectAsStateWithLifecycle()
            val fontSizeScale by appSettingsManager.fontSizeScale.collectAsStateWithLifecycle()

            MaaMeowTheme(themeMode = themeMode, useSystemMonetColor = useSystemMonetColor) {
                val baseDensity = LocalDensity.current
                val configuration = LocalConfiguration.current
                val effectiveScale = AppSettingsManager.resolveFontSizeScale(
                    stored = fontSizeScale,
                    smallestWidthDp = configuration.smallestScreenWidthDp,
                    fontScale = baseDensity.fontScale,
                )
                CompositionLocalProvider(
                    LocalDensity provides Density(
                        density = baseDensity.density * effectiveScale / 100f,
                        fontScale = baseDensity.fontScale,
                    )
                ) {
                    ProvideInputFocusManager {
                        AppNavigation(
                            mainTabNavigator = org.koin.compose.koinInject(),
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Timber.i("MainActivity 收到新的 Intent")
    }

    private fun AppSettingsManager.ThemeMode.toAppCompatNightMode(): Int = when (this) {
        AppSettingsManager.ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        AppSettingsManager.ThemeMode.WHITE -> AppCompatDelegate.MODE_NIGHT_NO
        AppSettingsManager.ThemeMode.DARK,
        AppSettingsManager.ThemeMode.PURE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
    }
}
