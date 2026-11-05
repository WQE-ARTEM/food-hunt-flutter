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
import kotlin.math.abs
import kotlin.math.sqrt

class BotService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var floatButton: Button? = null
    private var isRunning = false
    private val handler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { command -> handler.post(command) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        showFloatingButton()
    }

    private fun showFloatingButton() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        floatButton = Button(this).apply {
            text = "▶ СТАРТ v2"
            textSize = 13f
            setBackgroundColor(Color.parseColor("#2E7D32"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                isRunning = !isRunning
                if (isRunning) {
                    text = "⏹ СКАН..."
                    setBackgroundColor(Color.parseColor("#C62828"))
                    runLoop()
                } else {
                    text = "▶ СТАРТ v2"
                    setBackgroundColor(Color.parseColor("#2E7D32"))
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
            y = 140 // Подняли выше рамки поля!
        }

        windowManager?.addView(floatButton, params)
    }

    private fun updateStatus(msg: String) {
        handler.post {
            if (isRunning) floatButton?.text = "⏹ $msg"
        }
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
                } else {
                    updateStatus("Ошб буфера")
                }
                if (isRunning) {
                    handler.postDelayed({ runLoop() }, 1300)
                }
            }

            override fun onFailure(errorCode: Int) {
                updateStatus("Ошб скр:$errorCode")
                if (isRunning) {
                    handler.postDelayed({ runLoop() }, 1800)
                }
            }
        })
    }

    private fun processScreen(bmp: Bitmap) {
        val w = bmp.width
        val h = bmp.height

        // 1. Проверяем свободные белые слоты ожидания (на высоте 65%)
        val slotXPercentages = listOf(0.18, 0.34, 0.50, 0.66, 0.82)
        val slotY = (h * 0.65).toInt()
        var emptySlots = 0
        for (px in slotXPercentages) {
            val c = bmp.getPixel((w * px).toInt(), slotY)
            if (Color.red(c) > 225 && Color.green(c) > 225 && Color.blue(c) > 225) {
                emptySlots++
            }
        }

        if (emptySlots == 0) {
            updateStatus("Жду слоты")
            return
        }

        // 2. Собираем пиксели ВНЕШНЕГО КОНТУРА рисунка (X: 10%..90%, Y: 18%..47%)
        val frontierPixels = mutableListOf<Int>()
        val allBoardPixels = mutableListOf<Int>()
        val minX = (w * 0.10).toInt()
        val maxX = (w * 0.90).toInt()
        val minY = (h * 0.18).toInt()
        val maxY = (h * 0.47).toInt()
        val step = 5

        for (y in minY until maxY step step) {
            for (x in minX until maxX step step) {
                val pixel = bmp.getPixel(x, y)
                if (!isBoardBackground(pixel)) {
                    allBoardPixels.add(pixel)
                    val top = bmp.getPixel(x, (y - step).coerceAtLeast(minY))
                    val bottom = bmp.getPixel(x, (y + step).coerceAtMost(maxY))
                    val left = bmp.getPixel((x - step).coerceAtLeast(minX), y)
                    val right = bmp.getPixel((x + step).coerceAtMost(maxX), y)

                    if (isBoardBackground(top) || isBoardBackground(bottom) || isBoardBackground(left) || isBoardBackground(right)) {
                        frontierPixels.add(pixel)
                    }
                }
            }
        }

        if (allBoardPixels.isEmpty()) {
            updateStatus("Поле пусто")
            return
        }

        // 3. Считываем цвета ТОЛЬКО 3 верхних кнопок очереди (колонки: 34%, 50%, 66% ширины, высота центра ~76%)
        val colXPercentages = listOf(0.34, 0.50, 0.66)
        val activeRowTapY = (h * 0.76).toFloat()

        var bestColIndex = -1
        var maxScore = -1

        for (i in colXPercentages.indices) {
            val colX = (w * colXPercentages[i]).toInt()
            val btnColor = sampleButtonColor(bmp, colX, h) ?: continue

            // Считаем совпадения с внешним контуром (умножаем на 10 для приоритета)
            var score = 0
            for (fp in frontierPixels) {
                if (colorDistance(btnColor, fp) < 75.0) {
                    score += 10
                }
            }
            // Плюс совпадения со всем полем (на случай если контур тонкий)
            for (ap in allBoardPixels) {
                if (colorDistance(btnColor, ap) < 75.0) {
                    score += 1
                }
            }

            if (score > maxScore) {
                maxScore = score
                bestColIndex = i
            }
        }

        // Если нашли лучшую колонку — бьем по ней!
        if (bestColIndex != -1) {
            val tapX = (w * colXPercentages[bestColIndex]).toFloat()
            updateStatus("Бью Кол ${bestColIndex + 1}")
            tap(tapX, activeRowTapY)
        } else {
            // Запасной удар по первой доступной колонке, если цвета нестандартные
            val fallbackX = (w * 0.34).toFloat()
            updateStatus("Авто Кол 1")
            tap(fallbackX, activeRowTapY)
        }
    }

    // Берем пробу цвета кнопки по верхнему и нижнему краю (мимо белых цифр в центре)
    private fun sampleButtonColor(bmp: Bitmap, centerX: Int, h: Int): Int? {
        val sampleYs = listOf((h * 0.728).toInt(), (h * 0.785).toInt())
        var rSum = 0
        var gSum = 0
        var bSum = 0
        var count = 0

        for (y in sampleYs) {
            for (dx in -28..28 step 7) {
                val p = bmp.getPixel(centerX + dx, y)
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)
                // Отсекаем белые цифры и обводку
                if (r > 215 && g > 215 && b > 215) continue
                // Отсекаем бежевый деревянный стол (строго: высокий зеленый И красный больше синего)
                if (r in 205..250 && g in 165..220 && b in 125..185 && (r > g) && (g > b)) continue

                rSum += r
                gSum += g
                bSum += b
                count++
            }
        }
        if (count == 0) return null
        return Color.rgb(rSum / count, gSum / count, bSum / count)
    }

    private fun isBoardBackground(pixel: Int): Boolean {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        val maxDiff = maxOf(abs(r - g), abs(g - b), abs(r - b))
        return r > 200 && g > 190 && b > 180 && maxDiff < 28
    }

    private fun colorDistance(c1: Int, c2: Int): Double {
        val dr = (Color.red(c1) - Color.red(c2)).toDouble()
        val dg = (Color.green(c1) - Color.green(c2)).toDouble()
        val db = (Color.blue(c1) - Color.blue(c2)).toDouble()
        return sqrt(dr * dr + dg * dg + db * db)
    }

    private fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 80)
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
