package com.aliothmoon.maameow.domain.checkin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.math.abs

/**
 * 图像模板匹配引擎（双引擎中的第二条路径）。
 *
 * 用途：为**未开启防截屏**的 App 提供识别能力，也作为无障碍方案的兜底。
 *
 * ⚠️ 重要限制：本引擎对飞书无效。
 * 飞书对打卡页启用了 FLAG_SECURE，系统截图只能得到纯黑图像，
 * 任何模板匹配都不可能命中。飞书必须使用无障碍控件定位。
 * 因此本引擎属于"通用能力"，而非飞书打卡的默认路径。
 *
 * 实现说明：
 * 为避免引入 OpenCV 这类重型依赖（会显著增大 APK 体积并拖慢构建），
 * 这里采用**灰度降采样 + 逐像素绝对差**的轻量匹配。
 * 对打卡按钮这类高对比、形状固定的目标足够可靠。
 */
object ImageTemplateMatcher {

    /** 匹配结果。 */
    sealed interface MatchResult {
        /** 命中，[score] 为相似度（0~1），[rect] 为屏幕坐标区域 */
        data class Found(val rect: Rect, val score: Double) : MatchResult

        /** 未命中，[bestScore] 为搜索中出现的最高相似度，便于调参 */
        data class NotFound(val bestScore: Double) : MatchResult

        /** 截屏被系统拒绝（目标 App 开启了防截屏） */
        data object ScreenSecured : MatchResult
    }

    /**
     * 执行一次模板匹配。
     *
     * @param timeoutMs 总超时时间，内部会周期性重试截屏
     */
    suspend fun match(
        context: Context,
        template: ImageTemplate,
        timeoutMs: Long,
    ): MatchResult = withContext(Dispatchers.Default) {
        val tmpl = loadTemplate(context, template.assetPath)
            ?: return@withContext MatchResult.NotFound(0.0)

        val deadline = System.currentTimeMillis() + timeoutMs
        var best = 0.0
        while (System.currentTimeMillis() < deadline) {
            when (val shot = captureScreen(context)) {
                null -> {
                    // 截屏失败：多为防截屏所致，直接返回专用状态让上层给出准确提示
                    return@withContext MatchResult.ScreenSecured
                }

                else -> {
                    val score = matchOnce(shot, tmpl, template.roi)
                    best = maxOf(best, score)
                    if (score >= template.threshold) {
                        val rect = locate(shot, tmpl, template.roi)
                        if (rect != null) {
                            return@withContext MatchResult.Found(rect, score)
                        }
                    }
                    shot.recycle()
                }
            }
            delay(POLL_INTERVAL_MS)
        }
        MatchResult.NotFound(best)
    }

    /** 从 assets 加载模板图并转为灰度降采样数组。 */
    private fun loadTemplate(context: Context, assetPath: String): GrayImage? = try {
        context.assets.open(assetPath).use { BitmapFactory.decodeStream(it) }
            ?.let { toGray(it) }
    } catch (e: Exception) {
        Timber.w(e, "模板加载失败：%s", assetPath)
        null
    }

    /**
     * 截取当前屏幕。
     *
     * 使用无障碍服务的 takeScreenshot（API 30+）。
     * 若目标 App 开启了 FLAG_SECURE，系统会回调失败，
     * 此时返回 null，由调用方转为 [MatchResult.ScreenSecured]。
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun captureScreenApi30(): Bitmap? {
        val service = com.aliothmoon.maameow.service.CheckInAccessibilityService.instance ?: return null
        return withTimeoutOrNull(3_000L) {
            val deferred = kotlinx.coroutines.CompletableDeferred<Bitmap?>()
            service.takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                java.util.concurrent.Executors.newSingleThreadExecutor(),
                object : android.accessibilityservice.AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: android.accessibilityservice.AccessibilityService.ScreenshotResult) {
                        val bmp = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                        // 硬件缓冲可能被回收，复制为软件位图后再释放
                        val copy = bmp?.copy(Bitmap.Config.ARGB_8888, false)
                        screenshot.hardwareBuffer.close()
                        bmp?.recycle()
                        deferred.complete(copy)
                    }

                    override fun onFailure(errorCode: Int) {
                        // 防截屏（FLAG_SECURE）会走到这里
                        Timber.w("截屏失败，错误码：%d（通常为应用启用了防截屏）", errorCode)
                        deferred.complete(null)
                    }
                },
            )
            deferred.await()
        }
    }

    /** 统一截屏入口，负责 API 版本分发。 */
    private suspend fun captureScreen(context: Context): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Timber.w("系统版本低于 11，不支持无障碍截屏")
            return null
        }
        return captureScreenApi30()
    }

    /** 灰度降采样后的图像：宽度固定，减少匹配计算量。 */
    private class GrayImage(val w: Int, val h: Int, val data: IntArray)

    /** 将位图转为灰度并降到统一宽度，兼顾精度与速度。 */
    private fun toGray(src: Bitmap): GrayImage {
        val scale = TARGET_WIDTH.toDouble() / src.width
        val w = TARGET_WIDTH
        val h = maxOf(1, (src.height * scale).toInt())
        val scaled = Bitmap.createScaledBitmap(src, w, h, true)
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled != src) scaled.recycle()

        val gray = IntArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            // 亮度加权，符合人眼感知
            gray[i] = (r * 299 + g * 587 + b * 114) / 1000
        }
        return GrayImage(w, h, gray)
    }

    /** 对整屏做一次相似度扫描，返回最高得分。 */
    private fun matchOnce(screen: Bitmap, tmpl: GrayImage, roi: Roi?): Double {
        val scr = toGray(screen)
        val best = scan(screen, scr, tmpl, roi, needRect = false)
        return best.first
    }

    /** 定位命中区域，返回屏幕坐标。 */
    private fun locate(screen: Bitmap, tmpl: GrayImage, roi: Roi?): Rect? {
        val scr = toGray(screen)
        val (score, offset) = scan(screen, scr, tmpl, roi, needRect = true)
        if (offset == null) return null
        // 从降采样坐标还原到原始屏幕坐标
        val factor = screen.width.toDouble() / scr.w
        return Rect(
            (offset.first * factor).toInt(),
            (offset.second * factor).toInt(),
            ((offset.first + tmpl.w) * factor).toInt(),
            ((offset.second + tmpl.h) * factor).toInt(),
        )
    }

    /**
     * 核心扫描：在屏幕灰度图上滑动模板，计算平均绝对差。
     *
     * 为控制耗时，横向与纵向都采用 [STEP] 像素步进；
     * 打卡按钮尺寸通常较大，步进不会导致漏检。
     */
    private fun scan(
        screen: Bitmap,
        scr: GrayImage,
        tmpl: GrayImage,
        roi: Roi?,
        needRect: Boolean,
    ): Pair<Double, Pair<Int, Int>?> {
        val x0 = ((roi?.left ?: 0f) * scr.w).toInt().coerceIn(0, scr.w - 1)
        val y0 = ((roi?.top ?: 0f) * scr.h).toInt().coerceIn(0, scr.h - 1)
        val x1 = ((roi?.right ?: 1f) * scr.w).toInt().coerceIn(x0 + 1, scr.w)
        val y1 = ((roi?.bottom ?: 1f) * scr.h).toInt().coerceIn(y0 + 1, scr.h)

        var bestScore = 0.0
        var bestX = -1
        var bestY = -1

        var y = y0
        while (y + tmpl.h <= y1) {
            var x = x0
            while (x + tmpl.w <= x1) {
                val diff = pixelDiff(scr, tmpl, x, y)
                // 相似度 = 1 - 归一化差异
                val score = 1.0 - diff / 255.0
                if (score > bestScore) {
                    bestScore = score
                    bestX = x
                    bestY = y
                }
                x += STEP
            }
            y += STEP
        }

        return if (needRect && bestX >= 0) {
            bestScore to (bestX to bestY)
        } else {
            bestScore to null
        }
    }

    /** 计算模板与屏幕指定位置的平均绝对差（0~255）。 */
    private fun pixelDiff(scr: GrayImage, tmpl: GrayImage, ox: Int, oy: Int): Double {
        var sum = 0L
        var count = 0
        // 抽样比较：每 3 个像素取 1 个，速度提升约 3 倍，精度损失可忽略
        var ty = 0
        while (ty < tmpl.h) {
            var tx = 0
            while (tx < tmpl.w) {
                val sv = scr.data[(oy + ty) * scr.w + (ox + tx)]
                val tv = tmpl.data[ty * tmpl.w + tx]
                sum += abs(sv - tv)
                count++
                tx += SAMPLE_STEP
            }
            ty += SAMPLE_STEP
        }
        return if (count == 0) 255.0 else sum.toDouble() / count
    }

    /** 降采样基准宽度：太大影响速度，太小丢失细节。 */
    private const val TARGET_WIDTH = 360

    /** 滑动步进（像素） */
    private const val STEP = 4

    /** 像素抽样步进 */
    private const val SAMPLE_STEP = 3

    /** 截屏重试间隔 */
    private const val POLL_INTERVAL_MS = 400L
}
