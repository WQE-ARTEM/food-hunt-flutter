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
            text = "▶ СТАРТ v4"
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
                    text = "▶ СТАРТ v4"
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
                    handler.postDelayed({ runLoop() }, 2100)
                }
            }

            override fun onFailure(errorCode: Int) {
                if (isRunning) {
                    handler.postDelayed({ runLoop() }, 1500)
                }
            }
        })
    }

    private fun processScreen(bmp: Bitmap) {
        val w = bmp.width
        val h = bmp.height

        // 1. Точный подсчет пустых белых слотов ожидания
        // Проверяем верхнюю площадку каждого слота (Y = 67.2%), где у пустого слота чистый белый цвет,
        // а у занятого слота выступает цветная крышка муравьиного домика!
        val slotXPercentages = listOf(0.175, 0.338, 0.500, 0.662, 0.825)
        var emptySlots = 0
        for (px in slotXPercentages) {
            val sx = (w * px).toInt()
            val c1 = bmp.getPixel(sx, (h * 0.672).toInt())
            val c2 = bmp.getPixel(sx, (h * 0.688).toInt())
            if (isPureWhiteSlot(c1) && isPureWhiteSlot(c2)) {
                emptySlots++
            }
        }

        if (emptySlots == 0) {
            updateStatus("Слоты: 0/5")
            return
        }

        // 2. Сканируем ВНЕШНИЙ КОНТУР рисунка на доске (X: 12%..88%, Y: 17%..46%)
        val frontierPixels = mutableListOf<Int>()
        val allBoardPixels = mutableListOf<Int>()
        val minX = (w * 0.12).toInt()
        val maxX = (w * 0.88).toInt()
        val minY = (h * 0.17).toInt()
        val maxY = (h * 0.46).toInt()
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

        // 3. АВТО-ПОИСК КНОПОК (1, 2, 3 или 4 колонки в активном верхнем ряду Y = 77.5%)
        val detectedButtons = detectActiveRowButtons(bmp, w, h)
        if (detectedButtons.isEmpty()) {
            updateStatus("Нет кнопок")
            return
        }

        val activeRowTapY = (h * 0.775).toFloat()
        var bestBtnX = -1f
        var bestFrontierMatches = -1
        var bestTotalScore = -1

        for ((btnX, btnColor) in detectedButtons) {
            var frontierMatches = 0
            for (fp in frontierPixels) {
                if (colorDistance(btnColor, fp) < 62.0) {
                    frontierMatches++
                }
            }
            var boardMatches = 0
            for (ap in allBoardPixels) {
                if (colorDistance(btnColor, ap) < 58.0) {
                    boardMatches++
                }
            }

            val totalScore = frontierMatches * 25 + boardMatches
            if (totalScore > bestTotalScore) {
                bestTotalScore = totalScore
                bestFrontierMatches = frontierMatches
                bestBtnX = btnX.toFloat()
            }
        }

        // 4. Умное решение о клике:
        // - Если цвет есть прямо на внешнем контуре (bestFrontierMatches >= 2), бьем даже при 1 свободном слоте!
        // - Если цвет закрыт внутри, бьем только когда свободно >= 2 слотов.
        if (bestBtnX > 0 && (bestFrontierMatches >= 2 || (emptySlots >= 2 && bestTotalScore > 0))) {
            updateStatus("Клик! (Сл:$emptySlots Кн:${detectedButtons.size})")
            tap(bestBtnX, activeRowTapY)
        } else {
            updateStatus("Жду (Сл:$emptySlots)")
        }
    }

    // Автоматически находит центры X и цвета всех кнопок в верхнем ряду (независимо от того, 2 их или 3)
    private fun detectActiveRowButtons(bmp: Bitmap, w: Int, h: Int): List<Pair<Int, Int>> {
        val yTop = (h * 0.752).toInt()
        val yBot = (h * 0.798).toInt()
        val nonWoodColumns = BooleanArray(w)

        for (x in (w * 0.12).toInt() until (w * 0.88).toInt()) {
            val p1 = bmp.getPixel(x, yTop)
            val p2 = bmp.getPixel(x, yBot)
            if (!isWoodTable(p1) || !isWoodTable(p2)) {
                nonWoodColumns[x] = true
            }
        }

        // Группируем подряд идущие пиксели в отдельные кнопки
        val results = mutableListOf<Pair<Int, Int>>()
        var startX = -1
        for (x in (w * 0.12).toInt() until (w * 0.88).toInt()) {
            if (nonWoodColumns[x]) {
                if (startX == -1) startX = x
            } else {
                if (startX != -1) {
                    val width = x - startX
                    if (width > (w * 0.07).toInt()) { // Ширина настоящей кнопки > 7% экрана
                        val centerX = (startX + x) / 2
                        val color = sampleButtonBodyColor(bmp, centerX, yTop, yBot)
                        if (color != null) results.add(Pair(centerX, color))
                    }
                    startX = -1
                }
            }
        }
        return results
    }

    private fun sampleButtonBodyColor(bmp: Bitmap, centerX: Int, yTop: Int, yBot: Int): Int? {
        var rSum = 0
        var gSum = 0
        var bSum = 0
        var count = 0

        for (y in listOf(yTop, yBot)) {
            for (dx in listOf(-20, -10, 0, 10, 20)) {
                val p = bmp.getPixel(centerX + dx, y)
                if (isWoodTable(p)) continue
                rSum += Color.red(p)
                gSum += Color.green(p)
                bSum += Color.blue(p)
                count++
            }
        }
        if (count == 0) return null
        return Color.rgb(rSum / count, gSum / count, bSum / count)
    }

    private fun isPureWhiteSlot(pixel: Int): Boolean {
        return Color.red(pixel) > 240 && Color.green(pixel) > 240 && Color.blue(pixel) > 240
    }

    private fun isWoodTable(pixel: Int): Boolean {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        return r in 205..248 && g in 165..220 && b in 125..185 && (r - b) > 35
    }

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
