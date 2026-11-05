package com.artem.foodhuntbot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import java.util.concurrent.Executor

class BotService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var floatButton: Button? = null
    private var isRunning = false
    private val handler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { command -> handler.post(command) }

    // Координаты кнопок муравьев (находятся автоматически)
    private val buttons = mutableMapOf<String, Pair<Float, Float>>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        showFloatingButton()
    }

    private fun showFloatingButton() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        floatButton = Button(this).apply {
            text = "▶ СТАРТ"
            setBackgroundColor(Color.parseColor("#4CAF50"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                isRunning = !isRunning
                if (isRunning) {
                    text = "⏹ СТОП"
                    setBackgroundColor(Color.parseColor("#F44336"))
                    buttons.clear()
                    runLoop()
                } else {
                    text = "▶ СТАРТ"
                    setBackgroundColor(Color.parseColor("#4CAF50"))
                }
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 250
        }

        windowManager?.addView(floatButton, params)
    }

    private fun runLoop() {
        if (!isRunning) return

        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshotResult: ScreenshotResult) {
                val hwBuffer = screenshotResult.hardwareBuffer
                val colorSpace = screenshotResult.colorSpace
                val bitmap = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
                if (bitmap != null) {
                    val softBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    bitmap.recycle()
                    hwBuffer.close()
                    processScreen(softBitmap)
                    softBitmap.recycle()
                }
                if (isRunning) {
                    handler.postDelayed({ runLoop() }, 1800)
                }
            }

            override fun onFailure(errorCode: Int) {
                if (isRunning) {
                    handler.postDelayed({ runLoop() }, 2000)
                }
            }
        })
    }

    private fun processScreen(bmp: Bitmap) {
        val w = bmp.width
        val h = bmp.height

        // 1. Авто-калибровка кнопок в нижней части экрана (70% - 95% высоты)
        if (buttons.isEmpty()) {
            val sums = mutableMapOf(
                "red" to IntArray(3),
                "green" to IntArray(3),
                "blue" to IntArray(3),
                "yellow" to IntArray(3)
            )
            for (y in (h * 0.70).toInt() until (h * 0.95).toInt() step 6) {
                for (x in 0 until w step 6) {
                    val c = getColorName(bmp.getPixel(x, y))
                    if (c != null) {
                        sums[c]!![0] += x
                        sums[c]!![1] += y
                        sums[c]!![2] += 1
                    }
                }
            }
            for ((color, data) in sums) {
                if (data[2] > 5) {
                    buttons[color] = Pair((data[0] / data[2]).toFloat(), (data[1] / data[2]).toFloat())
                }
            }
        }

        if (buttons.isEmpty()) return

        // 2. Сканируем игровое поле (от 15% до 68% высоты экрана)
        val counts = mutableMapOf("red" to 0, "green" to 0, "blue" to 0, "yellow" to 0)
        for (y in (h * 0.15).toInt() until (h * 0.68).toInt() step 6) {
            for (x in 0 until w step 6) {
                val c = getColorName(bmp.getPixel(x, y))
                if (c != null) counts[c] = counts[c]!! + 1
            }
        }

        // Выбираем цвет, которого больше всего на поле, и нажимаем его кнопку
        val best = counts.entries.sortedByDescending { it.value }.firstOrNull { it.value > 5 && buttons.containsKey(it.key) }
        if (best != null) {
            val coords = buttons[best.key]!!
            tap(coords.first, coords.second)
        }
    }

    private fun getColorName(pixel: Int): String? {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        return when {
            r > 190 && g < 100 && b < 100 -> "red"
            r < 100 && g > 180 && b < 100 -> "green"
            r < 100 && g < 130 && b > 190 -> "blue"
            r > 190 && g > 180 && b < 100 -> "yellow"
            else -> null
        }
    }

    private fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (floatButton != null) windowManager?.removeView(floatButton)
    }
}
