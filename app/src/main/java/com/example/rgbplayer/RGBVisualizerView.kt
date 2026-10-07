package com.example.rgbplayer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.*

class RGBVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint()
    private var fftData: ByteArray = ByteArray(128)
    private val barCount = 32
    private val barSpacing = 4f
    private val smoothedBars = FloatArray(barCount)

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

    private fun frequencyToIndex(freq: Float, maxFreq: Float = 22050f, fftSize: Int = 64): Int {
        val normalizedFreq = log10(freq.toDouble()) / log10(maxFreq.toDouble())
        return (normalizedFreq * (fftSize - 1)).toInt().coerceIn(1, fftSize - 1)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

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

            // УСИЛЕНИЕ ВЫСОКИХ ЧАСТОТ (увеличили с 1.5 до 2.0)
            val frequencyBoost = 1.0 + (i.toDouble() / barCount) * 2.0
            avgMagnitude = (avgMagnitude * frequencyBoost).toFloat()

            // АГРЕССИВНОЕ логарифмическое масштабирование (уменьшили делитель с 1.8 до 1.2)
            val logMagnitude = if (avgMagnitude > 1f) log10(avgMagnitude.toDouble()) else 0.0
            val normalized = (logMagnitude / 1.2).coerceIn(0.0, 1.0).toFloat()

            // Сглаживание (чуть быстрее реакция)
            smoothedBars[i] = smoothedBars[i] * 0.6f + normalized * 0.4f

            // УВЕЛИЧЕННАЯ высота (умножаем на 1.5 вместо 1.2)
            val barHeight = max(smoothedBars[i] * height * 1.5f, 3f)
            val x = startX + i * totalBarWidth
            val y = height - barHeight

            // Цвет от красного (низкие) к синему (высокие)
            val hue = (i.toFloat() / barCount) * 240f
            val brightness = 0.4f + (smoothedBars[i] * 0.6f)
            paint.color = Color.HSVToColor(floatArrayOf(hue, 1f, brightness))

            val rect = RectF(x, y, x + barWidth, height)
            val radius = barWidth / 2f
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
    }
}