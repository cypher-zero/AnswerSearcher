package com.answersearcher.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 截屏管理器
 * 使用 MediaProjection API 进行屏幕截取
 */
class ScreenCaptureManager(private val context: Context) {

    private var mediaProjection: MediaProjection? = null
    private var width = 0
    private var height = 0
    private var density = 0

    init {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        width = metrics.widthPixels
        height = metrics.heightPixels
        density = metrics.densityDpi
    }

    /**
     * 初始化 MediaProjection
     * @param resultCode 从 Activity 回调获取的 resultCode
     * @param data 从 Activity 回调获取的 Intent
     */
    fun init(resultCode: Int, data: Intent): Boolean {
        if (resultCode != Activity.RESULT_OK) return false
        val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)
        return mediaProjection != null
    }

    /**
     * 截取当前屏幕
     * @return 截屏 Bitmap，超时或失败返回 null
     *
     * 关键修复：旧实现依赖"丢弃首帧后再等第二帧"的假设，在静态屏幕上
     * VirtualDisplay 可能只产出一帧，导致协程永久挂起、调用方窗口卡在隐藏状态
     * （表现为"莫名卡死 + 隐藏到后台"）。
     * 现改为：
     *  - 直接取第一帧（更稳妥，不再依赖第二帧）；
     *  - 整体包裹超时，超时即返回 null，绝不无限挂起；
     *  - 取消时（含超时）立即释放 VirtualDisplay / ImageReader，避免资源泄漏。
     */
    suspend fun captureScreen(timeoutMs: Long = 4000): Bitmap? {
        val projection = mediaProjection ?: return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                val handler = Handler(Looper.getMainLooper())
                val vd = projection.createVirtualDisplay(
                    "ScreenCapture",
                    width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface, null, handler
                )

                // 被取消（含超时）时释放所有资源
                cont.invokeOnCancellation {
                    runCatching { vd.release() }
                    runCatching { reader.setOnImageAvailableListener(null, null) }
                    runCatching { reader.close() }
                }

                reader.setOnImageAvailableListener({ r ->
                    val image: Image? = r.acquireLatestImage()
                    if (image == null) return@setOnImageAvailableListener
                    val bitmap = imageToBitmap(image, r, width, height)
                    image.close()
                    runCatching { vd.release() }
                    runCatching { r.setOnImageAvailableListener(null, null) }
                    runCatching { r.close() }
                    if (!cont.isCompleted) cont.resume(bitmap)
                }, handler)
            }
        }
    }

    /**
     * 将 Image 转换为裁剪掉 rowPadding 的 Bitmap
     */
    private fun imageToBitmap(image: Image, reader: ImageReader, w: Int, h: Int): Bitmap {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * w

        val raw = Bitmap.createBitmap(
            w + rowPadding / pixelStride,
            h,
            Bitmap.Config.ARGB_8888
        )
        raw.copyPixelsFromBuffer(buffer)

        return if (rowPadding > 0) {
            val cropped = Bitmap.createBitmap(raw, 0, 0, w, h)
            raw.recycle()
            cropped
        } else {
            raw
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        mediaProjection?.stop()
        mediaProjection = null
    }
}
