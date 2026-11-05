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
            text = "▶ СТАРТ"
            setBackgroundColor(Color.parseColor("#4CAF50"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                isRunning = !isRunning
                if (isRunning) {
                    text = "⏹ СТОП"
                    setBackgroundColor(Color.parseColor("#F44336"))
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
            y = 320
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
                    handler.postDelayed({ runLoop() }, 1400)
                }
            }

            override fun onFailure(errorCode: Int) {
                if (isRunning) {
                    handler.postDelayed({ runLoop() }, 1800)
                }
            }
        })
    }

    private fun processScreen(bmp: Bitmap) {
        val w = bmp.width
        val h = bmp.height

        // 1. Проверяем, сколько свободных белых слотов ожидания осталось (ряд на высоте ~65%)
        val slotXPercentages = listOf(0.18, 0.34, 0.50, 0.66, 0.82)
        val slotY = (h * 0.65).toInt()
        var emptySlots = 0
        for (px in slotXPercentages) {
            val c = bmp.getPixel((w * px).toInt(), slotY)
            if (Color.red(c) > 235 && Color.green(c) > 235 && Color.blue(c) > 235) {
                emptySlots++
            }
        }
        // Если все слоты забиты (или остался 1 критический), ждем пока муравьи добегут и освободят место
        if (emptySlots == 0) return

        // 2. Находим пиксели ВНЕШНЕГО КОНТУРА на поле (только те, до которых муравьи могут доползти)
        // Игровое поле находится в пределах X: 10%..90%, Y: 18%..47%
        val frontierPixels = mutableListOf<Int>()
        val minX = (w * 0.10).toInt()
        val maxX = (w * 0.90).toInt()
        val minY = (h * 0.18).toInt()
        val maxY = (h * 0.47).toInt()
        val step = 5

        for (y in minY until maxY step step) {
            for (x in minX until maxX step step) {
                val pixel = bmp.getPixel(x, y)
                if (!isBoardBackground(pixel)) {
                    // Проверяем 4 соседние точки: если хотя бы одна — фон доски, значит пиксель с краю!
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

        if (frontierPixels.isEmpty()) return

        // 3. Считываем ТОЛЬКО 3 верхние РАЗБЛОКИРОВАННЫЕ кнопки в очереди (высота ~75.5%)
        // Координаты X трех колонок: ~34%, ~50%, ~66% ширины экрана
        val colXPercentages = listOf(0.34, 0.50, 0.66)
        val activeRowTapY = (h * 0.76).toFloat()
        // Берем пробу цвета чуть выше белых цифр (на верхнем краешке кнопки ~73.2% высоты)
        val sampleColorY = (h * 0.732).toInt()

        var bestColIndex = -1
        var maxScore = -1

        for (i in colXPercentages.indices) {
            val colX = (w * colXPercentages[i]).toInt()
            val btnColor = sampleButtonColor(bmp, colX, sampleColorY) ?: continue

            // Считаем, сколько доступных пикселей на внешнем контуре совпадает с цветом этой кнопки
            var matchCount = 0
            for (fp in frontierPixels) {
                if (colorDistance(btnColor, fp) < 65.0) {
                    matchCount++
                }
            }

            if (matchCount > maxScore) {
                maxScore = matchCount
                bestColIndex = i
            }
        }

        // 4. Нажимаем на лучшую из 3 верхних кнопок:
        // Если на контуре есть пиксели этого цвета (или если свободно >= 3 слотов и нужно пробить очередь)
        if (bestColIndex != -1 && (maxScore >= 2 || emptySlots >= 3)) {
            val tapX = (w * colXPercentages[bestColIndex]).toFloat()
            tap(tapX, activeRowTapY)
        }
    }

    // Определяет цвет кнопки, игнорируя белые цифры и деревянный фон
    private fun sampleButtonColor(bmp: Bitmap, centerX: Int, y: Int): Int? {
        for (dx in -25..25 step 5) {
            val p = bmp.getPixel(centerX + dx, y)
            // Пропускаем белый текст цифр и белую обводку
            if (Color.red(p) > 230 && Color.green(p) > 230 && Color.blue(p) > 230) continue
            // Пропускаем бежевый деревянный фон (если колонка пустая)
            if (isWoodBackground(p)) continue
            return p
        }
        return null
    }

    // Проверка на светло-бежевый фон самой доски с рисунком
    private fun isBoardBackground(pixel: Int): Boolean {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        // Фон доски очень светлый и малонасыщенный (разница между каналами минимальна)
        val maxDiff = maxOf(abs(r - g), abs(g - b), abs(r - b))
        return r > 205 && g > 195 && b > 185 && maxDiff < 25
    }

    // Проверка на цвет деревянного стола вокруг кнопок
    private fun isWoodBackground(pixel: Int): Boolean {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        return r in 210..248 && g in 170..215 && b in 135..185 && (r - b) > 30
    }

    private fun colorDistance(c1: Int, c2: Int): Double {
        val dr = (Color.red(c1) - Color.red(c2)).toDouble()
        val dg = (Color.green(c1) - Color.green(c2)).toDouble()
        val db = (Color.blue(c1) - Color.blue(c2)).toDouble()
        return sqrt(dr * dr + dg * dg + db * db)
    }

    private fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 65)
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
