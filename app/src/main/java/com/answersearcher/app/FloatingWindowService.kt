package com.answersearcher.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.*
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.answersearcher.app.databinding.FloatingControlsBinding
import com.answersearcher.app.databinding.FloatingDisplayBinding
import kotlinx.coroutines.*

/**
 * 悬浮窗服务
 *
 * 采用双窗口方案实现"按钮可点击 + 其余区域点击穿透"：
 * - Display 窗口：显示答案文本，FLAG_NOT_TOUCHABLE（完全点击穿透）
 * - Control 窗口：包含拖拽/截屏/关闭按钮，可交互
 *
 * 两个窗口同步移动，用户通过拖拽手柄调整位置。
 */
class FloatingWindowService : Service() {

    companion object {
        var mediaProjectionResultCode: Int = 0
        var mediaProjectionData: Intent? = null
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "floating_window_service"
        private const val CAPTURE_TIMEOUT_MS = 4000L

        // 题目识别区域：把屏幕按高度均分 4 份，只取中间两份（即中间一半），
        // 避免 OCR 把题目之外的干扰文字（相邻题目、选项解析等）一起识别，
        // 导致模糊搜索匹配到错误条目（例如"以下哪个不是A、J、C的解释"）。
        private const val QUESTION_REGION_TOP_FRACTION = 0.25f
        private const val QUESTION_REGION_HEIGHT_FRACTION = 0.50f
    }

    private lateinit var windowManager: WindowManager
    private lateinit var displayBinding: FloatingDisplayBinding
    private lateinit var controlBinding: FloatingControlsBinding

    private lateinit var displayParams: WindowManager.LayoutParams
    private lateinit var controlParams: WindowManager.LayoutParams

    private var screenCaptureManager: ScreenCaptureManager? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var isSearching = false
    private var screenWidth = 0
    private var screenHeight = 0

    // 拖拽相关
    private var displayHeight = 0
    private val gap = 8 // display 和 control 之间的间距 (px)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        getScreenSize()

        initDisplayView()
        initControlView()
        initScreenCapture()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    // ==================== 初始化 ====================

    private fun getScreenSize() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
    }

    /**
     * 初始化显示窗口（点击穿透）
     */
    private fun initDisplayView() {
        displayBinding = FloatingDisplayBinding.inflate(LayoutInflater.from(this))

        displayParams = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE

            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.START
            // 初始位置：屏幕中上方
            x = screenWidth / 2 - dpToPx(130)
            y = screenHeight / 3
        }

        windowManager.addView(displayBinding.root, displayParams)
    }

    /**
     * 初始化控制窗口（可交互）
     */
    private fun initControlView() {
        controlBinding = FloatingControlsBinding.inflate(LayoutInflater.from(this))

        controlParams = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE

            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.START
            x = displayParams.x
            y = displayParams.y + dpToPx(60) // 初始在 display 下方
        }

        setupDrag()
        setupScreenshot()
        setupClose()

        windowManager.addView(controlBinding.root, controlParams)

        // 测量 display 高度后重新定位 control
        displayBinding.root.post {
            displayHeight = displayBinding.root.height
            controlParams.y = displayParams.y + displayHeight + gap
            windowManager.updateViewLayout(controlBinding.root, controlParams)
        }
    }

    /**
     * 初始化截屏管理器
     */
    private fun initScreenCapture() {
        val data = mediaProjectionData
        if (data != null && mediaProjectionResultCode != 0) {
            screenCaptureManager = ScreenCaptureManager(this)
            val success = screenCaptureManager?.init(mediaProjectionResultCode, data) ?: false
            if (!success) {
                Toast.makeText(this, "截屏初始化失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ==================== 拖拽逻辑 ====================

    private fun setupDrag() {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        controlBinding.ivDrag.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = controlParams.x
                    initialY = controlParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()

                    controlParams.x = initialX + dx
                    controlParams.y = initialY + dy

                    // display 跟随移动，保持在 control 上方
                    displayParams.x = controlParams.x
                    displayParams.y = controlParams.y - displayHeight - gap

                    windowManager.updateViewLayout(controlBinding.root, controlParams)
                    windowManager.updateViewLayout(displayBinding.root, displayParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // 更新 display 高度（内容可能变化了）
                    displayHeight = displayBinding.root.height
                    true
                }
                else -> false
            }
        }
    }

    // ==================== 截屏搜索逻辑 ====================

    private fun setupScreenshot() {
        controlBinding.ivScreenshot.setOnClickListener {
            if (!isSearching) {
                onScreenshotClicked()
            }
        }
    }

    private fun onScreenshotClicked() {
        if (isSearching) return
        isSearching = true

        // 立即在窗口上显示「正在搜查中」，让用户知道已触发
        displayBinding.tvAnswer.text = getString(R.string.searching_now)

        serviceScope.launch {
            try {
                // ===== 1. 截屏 + OCR（仅在截屏瞬间隐藏窗口，OCR 时已恢复可见）=====
                val ocrText = captureAndOcr()

                // 确保窗口可见，并提示正在搜查
                controlBinding.root.visibility = View.VISIBLE
                displayBinding.root.visibility = View.VISIBLE
                displayBinding.tvAnswer.text = getString(R.string.searching_now)
                repositionDisplay()

                if (ocrText == null) {
                    displayBinding.tvAnswer.text = getString(R.string.ocr_failed)
                    repositionDisplay()
                    return@launch
                }

                // ===== 2. 表格模糊搜索（5 秒超时：超时即返回进程并提示「表格匹配未成功」）=====
                val match = withTimeoutOrNull(5_000) {
                    val excelData = AnswerApplication.excelData
                    if (excelData != null) {
                        withContext(Dispatchers.Default) {
                            SearchEngine.search(ocrText, excelData)
                        }
                    } else null
                }

                if (match != null) {
                    displayBinding.tvAnswer.text = buildString {
                        append(getString(R.string.found_in_table)).append("\n")
                        append("题目: ").append(match.question).append("\n")
                        append("答案: ").append(match.answer)
                    }
                } else {
                    // 5 秒超时或题库确实无匹配，统一提示「表格匹配未成功」
                    displayBinding.tvAnswer.text = getString(R.string.table_match_failed)
                }
                repositionDisplay()

            } catch (e: Exception) {
                displayBinding.tvAnswer.text = "搜索出错: ${e.message}"
                repositionDisplay()
            } finally {
                // 无论如何都恢复窗口可见并解锁，杜绝「卡在后台用不了」
                controlBinding.root.visibility = View.VISIBLE
                displayBinding.root.visibility = View.VISIBLE
                isSearching = false
            }
        }
    }

    /**
     * 截屏并对 OCR。为保证截图不含悬浮窗自身，仅在截屏瞬间隐藏窗口，
     * 截屏（无论成败）后立即恢复可见，OCR 识别时窗口已恢复（显示「正在搜查中」）。
     * 截屏带超时保护，绝不因等待画面帧而永久挂起；任何异常 / 取消都会在 finally 中
     * 恢复窗口可见，杜绝「卡在后台用不了」。
     * OCR 返回空时自动重试一次，显著提升一次成功率。
     */
    private suspend fun captureAndOcr(): String? {
        var result: String? = null
        try {
            repeat(2) { attempt ->
                // 隐藏窗口，等待帧缓冲刷新
                controlBinding.root.visibility = View.INVISIBLE
                displayBinding.root.visibility = View.INVISIBLE
                delay(250)

                // 截屏（内部已带超时，最多等待 CAPTURE_TIMEOUT_MS）
                val bmp = withTimeoutOrNull(CAPTURE_TIMEOUT_MS + 500) {
                    screenCaptureManager?.captureScreen(CAPTURE_TIMEOUT_MS)
                }

                // 无论截屏是否成功，立即恢复窗口可见
                controlBinding.root.visibility = View.VISIBLE
                displayBinding.root.visibility = View.VISIBLE

                if (bmp != null) {
                    // 仅截取题目区域：屏幕按高度均分 4 份，取中间两份（中间一半），
                    // 再交给 OCR，排除题目之外的干扰文字。
                    val regionTop = (bmp.height * QUESTION_REGION_TOP_FRACTION).toInt()
                    val regionHeight = (bmp.height * QUESTION_REGION_HEIGHT_FRACTION).toInt()
                        .coerceAtMost(bmp.height - regionTop)
                    val regionBmp = Bitmap.createBitmap(
                        bmp, 0, regionTop, bmp.width, regionHeight
                    )
                    val text = withContext(Dispatchers.Default) {
                        OCRManager.recognizeText(regionBmp)
                    }
                    regionBmp.recycle()
                    bmp.recycle()
                    if (text.isNotBlank()) {
                        result = text
                        return@repeat
                    }
                }
                // 第一次为空，稍候后重试一次
                if (attempt == 0) delay(200)
            }
        } finally {
            // 无论如何保证窗口恢复可见，避免「隐藏到后台」
            controlBinding.root.visibility = View.VISIBLE
            displayBinding.root.visibility = View.VISIBLE
        }
        return result
    }

    /** 内容变化后重新定位显示窗口（保持在控制窗口上方） */
    private fun repositionDisplay() {
        displayBinding.root.post {
            displayHeight = displayBinding.root.height
            displayParams.y = controlParams.y - displayHeight - gap
            windowManager.updateViewLayout(displayBinding.root, displayParams)
        }
    }

    // ==================== 关闭逻辑 ====================

    private fun setupClose() {
        controlBinding.ivClose.setOnClickListener {
            stopSelf()
        }
    }

    // ==================== 通知 ====================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_desc)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.channel_desc))
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    // ==================== 工具方法 ====================

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density).toInt()
    }

    // ==================== 生命周期 ====================

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        screenCaptureManager?.release()
        screenCaptureManager = null

        try {
            windowManager.removeView(displayBinding.root)
        } catch (e: Exception) { }
        try {
            windowManager.removeView(controlBinding.root)
        } catch (e: Exception) { }
    }
}
