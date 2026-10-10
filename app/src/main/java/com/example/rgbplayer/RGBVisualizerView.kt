package com.example.rgbplayer

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.View
import kotlin.math.*

class RGBVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Mode { BARS, LAMPS }

    private val paint = Paint()
    private var fftData: ByteArray = ByteArray(1024) // Увеличенный размер для точности
    private val barCount = 32
    private val barSpacing = 4f
    private val smoothedBars = FloatArray(barCount)

    // Для режима ламп
    private val smoothedLampLevels = floatArrayOf(0f, 0f, 0f)
    private val lampPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    var mode: Mode = Mode.BARS
    private var logCounter = 0

    fun updateFFT(data: ByteArray) {
        if (fftData.size != data.size) {
            Log.d("RGB_Filters", "FFT size changed: ${fftData.size} → ${data.size}")
            fftData = ByteArray(data.size)
        }
        fftData = data
        invalidate()
    }

    private fun getMagnitude(fft: ByteArray, index: Int): Float {
        val realIndex = index * 2
        val imaginaryIndex = realIndex + 1
        if (imaginaryIndex >= fft.size) return 0f

        val real = fft[realIndex].toInt()
        val imaginary = fft[imaginaryIndex].toInt()
        return sqrt((real * real + imaginary * imaginary).toDouble()).toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        when (mode) {
            Mode.BARS -> drawBars(canvas)
            Mode.LAMPS -> drawLamps(canvas)
        }
    }

    // ✅ МЕТОД drawBars (чтобы не было ошибки Unresolved reference)
    private fun drawBars(canvas: Canvas) {
        val height = height.toFloat()
        val width = width.toFloat()

        val totalSpacing = barSpacing * (barCount - 1)
        val barWidth = (width - totalSpacing) / barCount
        val totalBarWidth = barWidth + barSpacing
        val startX = (width - (barCount * totalBarWidth) + barSpacing) / 2

        val fftSize = fftData.size / 2
        val minFreq = 20f
        val maxFreq = 22050f
        val ratio = maxFreq / minFreq

        for (i in 0 until barCount) {
            val freqStart = (minFreq * Math.pow(ratio.toDouble(), i.toDouble() / barCount)).toFloat()
            val freqEnd = (minFreq * Math.pow(ratio.toDouble(), (i + 1).toDouble() / barCount)).toFloat()

            val indexStart = frequencyToIndex(freqStart, maxFreq, fftSize)
            val indexEnd = frequencyToIndex(freqEnd, maxFreq, fftSize)

            var sum = 0f
            var count = 0
            for (idx in indexStart until indexEnd.coerceAtMost(fftSize - 1)) {
                sum += getMagnitude(fftData, idx)
                count++
            }

            var avgMagnitude = if (count > 0) sum / count else 0f
            val frequencyBoost = 1.0 + (i.toDouble() / barCount) * 2.0
            avgMagnitude = (avgMagnitude * frequencyBoost).toFloat()

            val logMagnitude = if (avgMagnitude > 1f) log10(avgMagnitude.toDouble()) else 0.0
            val normalized = (logMagnitude / 1.2).coerceIn(0.0, 1.0).toFloat()

            smoothedBars[i] = smoothedBars[i] * 0.6f + normalized * 0.4f

            val barHeight = max(smoothedBars[i] * height * 0.8f, 3f)
            val x = startX + i * totalBarWidth
            val y = height - barHeight

            val hue = (i.toFloat() / barCount) * 240f
            val brightness = 0.4f + (smoothedBars[i] * 0.6f)
            paint.color = Color.HSVToColor(floatArrayOf(hue, 1f, brightness))

            val rect = RectF(x, y, x + barWidth, height)
            val radius = barWidth / 2f
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
    }

    // ✅ ПОСЛЕДНЯЯ ВЕРСИЯ drawLamps с отладкой FilterDebug и честной энергией
    private fun drawLamps(canvas: Canvas) {
        val width = width.toFloat()
        val height = height.toFloat()

        val fftSize = fftData.size / 2
        val sampleRate = 44100f
        val frequencyResolution = sampleRate / (fftSize * 2)

        // 1. Вычисляем границы фильтров
        val bassEnd = (200f / frequencyResolution).toInt().coerceIn(1, fftSize - 1)
        val midStart = (400f / frequencyResolution).toInt().coerceIn(1, fftSize - 1)
        val midEnd = (2000f / frequencyResolution).toInt().coerceIn(1, fftSize - 1)
     //   val trebleStart = (5000f / frequencyResolution).toInt().coerceIn(1, fftSize - 1)
        val trebleStart = (3000f / frequencyResolution).toInt().coerceIn(1, fftSize - 1)

        // 2. ДЕТАЛЬНАЯ ОТЛАДКА: печатаем границы и значения конкретных бинов каждые 30 кадров
        if (logCounter % 30 == 0) {
            Log.d("FilterDebug", "=== ГРАНИЦЫ ФИЛЬТРОВ ===")
            Log.d("FilterDebug", "fftSize=$fftSize, Разрешение=${String.format("%.2f", frequencyResolution)} Гц/бин")
            Log.d("FilterDebug", "БАС: бины 1..$bassEnd (до ${bassEnd * frequencyResolution} Гц)")
            Log.d("FilterDebug", "СРЕДНИЕ: бины $midStart..$midEnd (${midStart * frequencyResolution} - ${midEnd * frequencyResolution} Гц)")
            Log.d("FilterDebug", "ВЫСОКИЕ: бины $trebleStart..${fftSize-1} (от ${trebleStart * frequencyResolution} Гц)")

            Log.d("FilterDebug", "=== АМПЛИТУДЫ КОНКРЕТНЫХ БИНОВ ===")
            Log.d("FilterDebug", "Бин 1 (~43 Гц): ${getMagnitude(fftData, 1)}")
            Log.d("FilterDebug", "Бин 5 (~215 Гц): ${getMagnitude(fftData, 5)}")
            Log.d("FilterDebug", "Бин 50 (~2150 Гц): ${getMagnitude(fftData, 50)}")
            Log.d("FilterDebug", "Бин 70 (~3015 Гц): ${getMagnitude(fftData, 70)}")  // НОВОЕ!
            Log.d("FilterDebug", "Бин 100 (~4307 Гц): ${getMagnitude(fftData, 100)}") // НОВОЕ!
            Log.d("FilterDebug", "Бин 150 (~6450 Гц): ${getMagnitude(fftData, 150)}")
        }

        // 3. Ищем МАКСИМАЛЬНУЮ амплитуду (ПИК) в каждом диапазоне, а не сумму!
        // Это полностью устраняет проблему накопления шума от сотен пустых бинов.
        var bassPeak = 0f
        var midPeak = 0f
        var treblePeak = 0f

        for (bin in 1 until fftSize) {
            val magnitude = getMagnitude(fftData, bin)

            when {
                bin <= bassEnd -> {
                    if (magnitude > bassPeak) bassPeak = magnitude
                }
                bin in midStart..midEnd -> {
                    if (magnitude > midPeak) midPeak = magnitude
                }
                bin >= trebleStart -> {
                    if (magnitude > treblePeak) treblePeak = magnitude
                }
            }
        }

        // 4. Функция нормализации для пиковых значений
        fun normalize(peak: Float, boost: Float, threshold: Float): Float {
            val boosted = peak * boost
            val logVal = if (boosted > threshold) log10(boosted.toDouble()).toFloat() else 0f
            return (logVal / 1.0f).coerceIn(0f, 1f)
        }

        // 5. Применяем нормализацию (пороги подобраны под реальные пики из твоего лога: 10, 87, 6, 1)
        val bassLevel = normalize(bassPeak, 0.05f, 2.0f)      // Если пик 80: 80*0.05=4.0 > 2.0 (горит)
        val midLevel = normalize(midPeak, 0.15f, 2.0f)        // Если пик 10: 10*0.15=1.5 < 2.0 (не горит)
        val trebleLevel = normalize(treblePeak, 1.0f, 1.2f)   // Если пик 1 (шум): 1*0.5=0.5 < 2.0 (НЕ ГОРИТ!)

        // 6. Быстрое сглаживание
        smoothedLampLevels[0] = smoothedLampLevels[0] * 0.2f + bassLevel * 0.8f
        smoothedLampLevels[1] = smoothedLampLevels[1] * 0.2f + midLevel * 0.8f
        smoothedLampLevels[2] = smoothedLampLevels[2] * 0.2f + trebleLevel * 0.8f

        // 7. Лог для проверки (теперь показывает пики, а не ложную сумму)
        logCounter++
        if (logCounter % 15 == 0) {
            Log.d("LampTuning",
                String.format("Peak: B=%.1f, M=%.1f, T=%.1f | Уровни: B=%.2f, M=%.2f, T=%.2f",
                    bassPeak, midPeak, treblePeak,
                    smoothedLampLevels[0], smoothedLampLevels[1], smoothedLampLevels[2])
            )
        }

        // 8. МАКСИМАЛЬНЫЙ радиус - заполняют ВЕСЬ экран
        val baseRadius = max(width, height) / 1.5f
        val spacing = width / 4f
        val centerY = height / 2f

        // 9. Создаём bitmap для аддитивного смешения
        val bitmap = Bitmap.createBitmap(width.toInt(), height.toInt(), Bitmap.Config.ARGB_8888)
        val bitmapCanvas = Canvas(bitmap)

        val lampData = listOf(
            Triple(Color.RED, smoothedLampLevels[0], spacing),
            Triple(Color.GREEN, smoothedLampLevels[1], spacing * 2),
            Triple(Color.BLUE, smoothedLampLevels[2], spacing * 3)
        )

        for ((color, level, centerX) in lampData) {
            val currentRadius = baseRadius * level

            if (level > 0.05f) {
                // Огромное внешнее свечение
                val glowRadius = currentRadius * 5f
                val glowShader = RadialGradient(
                    centerX, centerY, glowRadius,
                    intArrayOf(
                        Color.argb((level * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color)),
                        Color.argb((level * 100).toInt(), Color.red(color), Color.green(color), Color.blue(color)),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 0.2f, 1f),
                    Shader.TileMode.CLAMP
                )
                glowPaint.shader = glowShader
                bitmapCanvas.drawCircle(centerX, centerY, glowRadius, glowPaint)

                // Яркое ядро лампы БЕЗ БЕЛОГО (чистый насыщенный цвет)
                if (level > 0.2f) {
                    val coreRadius = currentRadius * 0.4f
                    val pureColor = Color.argb(255, Color.red(color), Color.green(color), Color.blue(color))

                    val coreShader = RadialGradient(
                        centerX, centerY, coreRadius,
                        intArrayOf(pureColor, pureColor),
                        floatArrayOf(0f, 1f),
                        Shader.TileMode.CLAMP
                    )
                    lampPaint.shader = coreShader
                    bitmapCanvas.drawCircle(centerX, centerY, coreRadius, lampPaint)
                }
            }
        }

        // 10. Рисуем bitmap с аддитивным смешением (SCREEN)
        val blendPaint = Paint()
        blendPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
        canvas.drawBitmap(bitmap, 0f, 0f, blendPaint)
        bitmap.recycle()
    }

    private fun frequencyToIndex(freq: Float, maxFreq: Float = 22050f, fftSize: Int = 64): Int {
        val normalizedFreq = log10(freq.toDouble()) / log10(maxFreq.toDouble())
        return (normalizedFreq * (fftSize - 1)).toInt().coerceIn(1, fftSize - 1)
    }

    fun clearBars() {
        for (i in smoothedBars.indices) smoothedBars[i] = 0f
        for (i in smoothedLampLevels.indices) smoothedLampLevels[i] = 0f
        invalidate()
    }
}