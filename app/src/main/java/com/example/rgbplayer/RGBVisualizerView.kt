package com.example.rgbplayer

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.*

class RGBVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Mode { BARS, LAMPS }

    private val paint = Paint()
    private var fftData: ByteArray = ByteArray(128)
    private val barCount = 32
    private val barSpacing = 4f
    private val smoothedBars = FloatArray(barCount)

    // Для режима ламп
    private val smoothedLampLevels = floatArrayOf(0f, 0f, 0f) // ФНЧ, ФСЧ, ФВЧ
    private val lampPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    var mode: Mode = Mode.BARS

    fun updateFFT(data: ByteArray) {
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

    // ✅ ОБНОВЛЁННЫЙ МЕТОД drawLamps (огромный диаметр, пороги, быстрая реакция)
    private fun drawLamps(canvas: Canvas) {
        val width = width.toFloat()
        val height = height.toFloat()

        val fftSize = fftData.size / 2
        val sampleRate = 44100f
        val frequencyResolution = sampleRate / (fftSize * 2)

        // Диапазоны частот в бинах
        val bassEnd = (300f / frequencyResolution).toInt().coerceIn(1, fftSize - 1)
        val midEnd = (3000f / frequencyResolution).toInt().coerceIn(1, fftSize - 1)

        // Вычисляем RMS для каждого диапазона
        var bassSum = 0f; var midSum = 0f; var trebleSum = 0f
        var bassCount = 0; var midCount = 0; var trebleCount = 0

        for (bin in 1 until fftSize) {
            val real = fftData[bin * 2].toInt()
            val imaginary = fftData[bin * 2 + 1].toInt()
            val magnitude = sqrt((real * real + imaginary * imaginary).toDouble()).toFloat()

            when {
                bin <= bassEnd -> { bassSum += magnitude * magnitude; bassCount++ }
                bin <= midEnd -> { midSum += magnitude * magnitude; midCount++ }
                else -> { trebleSum += magnitude * magnitude; trebleCount++ }
            }
        }

        val bassRMS = if (bassCount > 0) sqrt(bassSum / bassCount) else 0f
        val midRMS = if (midCount > 0) sqrt(midSum / midCount) else 0f
        val trebleRMS = if (trebleCount > 0) sqrt(trebleSum / trebleCount) else 0f

        // Нормализация с ПОРОГОМ и БАЛАНСОМ
        fun normalize(rms: Float, boost: Float, threshold: Float): Float {
            val boosted = rms * boost
            val logVal = if (boosted > threshold) log10(boosted.toDouble()).toFloat() else 0f
            return (logVal / 1.2f).coerceIn(0f, 1f)
        }

        // БАЛАНСИРОВКА: усиливаем бас и средние, ослабляем высокие
        val bassLevel = normalize(bassRMS, 0.07f, 5.5f)    // Усиливаем бас ещё больше, порог ниже
        val midLevel = normalize(midRMS, 0.12f, 6.5f)       // Средние тоже усиливаем
        val trebleLevel = normalize(trebleRMS, 0.4f, 5.5f) // Высокие ослабляем, порог выше

        // ОЧЕНЬ быстрое сглаживание (мгновенная реакция, резкие вспышки)
        smoothedLampLevels[0] = smoothedLampLevels[0] * 0.2f + bassLevel * 0.8f
        smoothedLampLevels[1] = smoothedLampLevels[1] * 0.2f + midLevel * 0.8f
        smoothedLampLevels[2] = smoothedLampLevels[2] * 0.2f + trebleLevel * 0.8f

        // МАКСИМАЛЬНЫЙ радиус - заполняют ВЕСЬ экран
        val baseRadius = max(width, height) / 1.5f
        val spacing = width / 4f
        val centerY = height / 2f

        // Создаём bitmap для аддитивного смешения (цвета складываются)
        val bitmap = Bitmap.createBitmap(width.toInt(), height.toInt(), Bitmap.Config.ARGB_8888)
        val bitmapCanvas = Canvas(bitmap)

        val lampData = listOf(
            Triple(Color.RED, smoothedLampLevels[0], spacing),
            Triple(Color.GREEN, smoothedLampLevels[1], spacing * 2),
            Triple(Color.BLUE, smoothedLampLevels[2], spacing * 3)
        )

        for ((color, level, centerX) in lampData) {
            // Радиус зависит от уровня (от 0 до максимума)
            val currentRadius = baseRadius * level

            if (level > 0.05f) {  // Рисуем только если уровень выше минимума
                // Огромное внешнее свечение (заполняет весь экран)
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

                // Яркое ядро лампы
                if (level > 0.2f) {
                    val coreRadius = currentRadius * 0.4f
                    val coreShader = RadialGradient(
                        centerX, centerY, coreRadius,
                        intArrayOf(
                            Color.WHITE,
                            Color.argb(255, Color.red(color), Color.green(color), Color.blue(color))
                        ),
                        floatArrayOf(0f, 1f),
                        Shader.TileMode.CLAMP
                    )
                    lampPaint.shader = coreShader
                    bitmapCanvas.drawCircle(centerX, centerY, coreRadius, lampPaint)
                }
            }
        }

        // Рисуем bitmap с аддитивным смешением (SCREEN)
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