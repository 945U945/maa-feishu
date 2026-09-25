package com.feishu.checkin.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * 主题配色。
 *
 * ## 为什么用飞书蓝做主色
 *
 * 从交互一致性出发：用户看到蓝色会自然联想到飞书，
 * 减少「这个应用和飞书到底什么关系」的困惑。
 * 同时也避免用飞书的品牌色本身 —— 那会有冒充官方应用的嫌疑，
 * 所以取一个相近但不相同的蓝。
 *
 * ## 动态取色（Material You）
 *
 * Android 12+ 支持从壁纸取色，这套方案让应用自然融入用户设备，
 * 代价是失去品牌识别度。这里的选择是**默认开启动态取色**，
 * 因为它对用户的实际体验提升更大 —— 这个应用没有强品牌诉求。
 */
private val FeishuBlue = Color(0xFF1B6BFF)
private val FeishuBlueDark = Color(0xFF9CC3FF)

/** 亮色配色 */
private val LightColors = lightColorScheme(
    primary = FeishuBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E4FF),
    onPrimaryContainer = Color(0xFF001945),

    secondary = Color(0xFF565E71),
    onSecondary = Color.White,

    // 打卡成功用绿色（语义色，不随品牌色变化）
    tertiary = Color(0xFF1B7F4B),
    onTertiary = Color.White,

    // 失败用红色 —— 注意这不是股票涨跌色，而是通用的错误语义色
    error = Color(0xFFBA1A1A),
    onError = Color.White,

    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF44474F),
    outline = Color(0xFF74777F),
)

/** 暗色配色 */
private val DarkColors = darkColorScheme(
    primary = FeishuBlueDark,
    onPrimary = Color(0xFF00306B),
    primaryContainer = Color(0xFF004494),
    onPrimaryContainer = Color(0xFFD9E4FF),

    secondary = Color(0xFFBEC6DC),
    onSecondary = Color(0xFF283041),

    tertiary = Color(0xFF7FDBA6),
    onTertiary = Color(0xFF00391E),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),

    background = Color(0xFF111318),
    onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF1A1C1E),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF44474F),
    onSurfaceVariant = Color(0xFFC4C6D0),
    outline = Color(0xFF8E9099),
)

/**
 * 应用主题。
 *
 * @param darkTheme 是否使用暗色。默认跟随系统 —— 打卡可能在早上
 *   天没亮时打开，跟随系统能保证不刺眼
 * @param dynamicColor 是否启用动态取色（仅 Android 12+ 有效）
 */
@Composable
fun FeishuCheckInTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}
