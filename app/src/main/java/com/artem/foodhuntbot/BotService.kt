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
            text = "▶ СТАРТ v3"
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
                    handler.removeCallbacksAndMessages(null)
                    text = "▶ СТАРТ v3"
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
            y = 140
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
                }
                if (isRunning) {
                    // Интервал 2100 мс полностью убирает системную ошибку скр:3
                    handler.postDelayed({ runLoop() }, 2100)
                }
            }

            override fun onFailure(errorCode: Int) {
                // Если Android попросил подождать (код 3), тихо ждем 1.5 сек и пробуем снова
                if (isRunning) {
                    handler.postDelayed({ runLoop() }, 1500)
                }
            }
        })
    }

    private fun processScreen(bmp: Bitmap) {
        val w = bmp.width
        val h = bmp.height

        // 1. Точная проверка 5 белых слотов ожидания (высота ~69.2% экрана!)
        val slotXPercentages = listOf(0.18, 0.34, 0.50, 0.66, 0.82)
        val slotY = (h * 0.692).toInt()
        var emptySlots = 0
        for (px in slotXPercentages) {
            val sx = (w * px).toInt()
            // Проверяем точку чуть ниже центра слота (70.5%), где у занятого слота нет белых цифр
            val c = bmp.getPixel(sx, (h * 0.705).toInt())
            if (Color.red(c) > 235 && Color.green(c) > 235 && Color.blue(c) > 235) {
                emptySlots++
            }
        }

        // Оставляем минимум 1 слот про запас, чтобы не забить все 5 слотов
        if (emptySlots <= 1) {
            updateStatus("Слоты: $emptySlots/5")
            return
        }

        // 2. Собираем пиксели ВНЕШНЕГО КОНТУРА рисунка (X: 12%..88%, Y: 18%..47%)
        val frontierPixels = mutableListOf<Int>()
        val allBoardPixels = mutableListOf<Int>()
        val minX = (w * 0.12).toInt()
        val maxX = (w * 0.88).toInt()
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
            updateStatus("Поле чистое")
            return
        }

        // 3. Считываем цвета 3 верхних активных кнопок очереди (X: 34%, 50%, 66%, центр Y: 77.5%)
        val colXPercentages = listOf(0.34, 0.50, 0.66)
        val activeRowTapY = (h * 0.775).toFloat()

        var bestColIndex = -1
        var maxScore = -1

        for (i in colXPercentages.indices) {
            val colX = (w * colXPercentages[i]).toInt()
            val btnColor = sampleButtonColor(bmp, colX, h) ?: continue

            var score = 0
            // Главный приоритет — цвета на внешнем контуре рисунка, доступные муравьям прямо сейчас
            for (fp in frontierPixels) {
                if (colorDistance(btnColor, fp) < 68.0) {
                    score += 15
                }
            }
            // Вторичный приоритет — присутствие цвета на поле
            for (ap in allBoardPixels) {
                if (colorDistance(btnColor, ap) < 60.0) {
                    score += 1
                }
            }

            if (score > maxScore) {
                maxScore = score
                bestColIndex = i
            }
        }

        if (bestColIndex != -1 && maxScore > 0) {
            val tapX = (w * colXPercentages[bestColIndex]).toFloat()
            updateStatus("Бью Кол ${bestColIndex + 1} (Сл:$emptySlots)")
            tap(tapX, activeRowTapY)
        } else {
            updateStatus("Жду контур")
        }
    }

    // Берем пробу цвета в верхних и нижних уголках кнопки (мимо цифр в центре), включая БЕЛЫЕ кнопки!
    private fun sampleButtonColor(bmp: Bitmap, centerX: Int, h: Int): Int? {
        // Высота 75.2% и 79.8% — чистое тело кнопки выше и ниже цифр
        val sampleYs = listOf((h * 0.752).toInt(), (h * 0.798).toInt())
        var rSum = 0
        var gSum = 0
        var bSum = 0
        var count = 0

        for (y in sampleYs) {
            for (dx in listOf(-24, -12, 12, 24)) {
                val p = bmp.getPixel(centerX + dx, y)
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)

                // Отсекаем только бежевый деревянный стол (если колонка закончилась)
                if (r in 205..248 && g in 165..218 && b in 125..182 && (r - b) > 35) continue

                rSum += r
                gSum += g
                bSum += b
                count++
            }
        }
        if (count == 0) return null
        return Color.rgb(rSum / count, gSum / count, bSum / count)
    }

    // Фон доски — бежево-серый RGB(~235, 224, 216). Чисто белые пиксели птички (RGB > 248) НЕ считаются фоном!
    private fun isBoardBackground(pixel: Int): Boolean {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        return r in 220..244 && g in 210..234 && b in 200..226 && (r - b) in 8..28
    }

    private fun colorDistance(c1: Int, c2: Int): Double {
        val dr = (Color.red(c1) - Color.red(c2)).toDouble()
        val dg = (Color.green(c1) - Color.green(c2)).toDouble()
        val db = (Color.blue(c1) - Color.blue(c2)).toDouble()
        return sqrt(dr * dr + dg * dg + db * db)
    }

    private fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 85)
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
