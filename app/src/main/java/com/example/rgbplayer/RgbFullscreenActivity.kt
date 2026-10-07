package com.example.rgbplayer

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.media.audiofx.Visualizer
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.*

@Suppress("DEPRECATION")
class RgbFullscreenActivity : AppCompatActivity() {

    private lateinit var visualizerView: VisualizerCanvasView
    private var visualizer: Visualizer? = null
    private var audioSessionId = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)

        visualizerView = VisualizerCanvasView(this)
        setContentView(visualizerView)

        audioSessionId = intent.getIntExtra("AUDIO_SESSION_ID", 0)

        if (audioSessionId != 0) {
            setupVisualizer()
        } else {
            Toast.makeText(this, "Audio session ID не получен", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (audioSessionId != 0 && visualizer == null) {
            setupVisualizer()
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            visualizer?.release()
            visualizer = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupVisualizer() {
        try {
            visualizer?.release()
            visualizer = null

            visualizer = Visualizer(audioSessionId)
            val captureSize = Visualizer.getCaptureSizeRange()[1]
            visualizer?.captureSize = captureSize

            visualizer?.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        visualizer: Visualizer?,
                        waveform: ByteArray?,
                        samplingRate: Int
                    ) {}

                    override fun onFftDataCapture(
                        visualizer: Visualizer?,
                        fft: ByteArray?,
                        samplingRate: Int
                    ) {
                        fft?.let {
                            visualizerView.updateFFT(it)
                        }
                    }
                },
                Visualizer.getMaxCaptureRate() / 2,
                false,
                true
            )

            visualizer?.enabled = true

        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка визуализатора: ${e.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
        }
    }

    // Кастомный View для отрисовки
    inner class VisualizerCanvasView(context: android.content.Context) : View(context) {
        private var fftData: ByteArray = ByteArray(128)
        private val paint = Paint()

        // Сглаженные значения для трёх частот
        private var smoothed100Hz = 0f
        private var smoothed1000Hz = 0f
        private var smoothed5000Hz = 0f

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

            val width = width.toFloat()
            val height = height.toFloat()
            val fftSize = fftData.size / 2

            val sampleRate = 44100f
            val frequencyResolution = sampleRate / fftData.size

            val index100Hz = (100f / frequencyResolution).toInt().coerceIn(0, fftSize - 1)
            val index1000Hz = (1000f / frequencyResolution).toInt().coerceIn(0, fftSize - 1)
            val index5000Hz = (5000f / frequencyResolution).toInt().coerceIn(0, fftSize - 1)

            val magnitude100Hz = getMagnitude(fftData, index100Hz)
            val magnitude1000Hz = getMagnitude(fftData, index1000Hz)
            val magnitude5000Hz = getMagnitude(fftData, index5000Hz)

            // ЕЩЁ БОЛЕЕ АГРЕССИВНОЕ усиление
            val normalized100Hz = (magnitude100Hz / 30f).coerceIn(0f, 1f)
            val normalized1000Hz = (magnitude1000Hz / 25f).coerceIn(0f, 1f)
            val normalized5000Hz = (magnitude5000Hz / 20f).coerceIn(0f, 1f)

            // Сглаживание
            smoothed100Hz = smoothed100Hz * 0.7f + normalized100Hz * 0.3f
            smoothed1000Hz = smoothed1000Hz * 0.7f + normalized1000Hz * 0.3f
            smoothed5000Hz = smoothed5000Hz * 0.7f + normalized5000Hz * 0.3f

            // МАКСИМАЛЬНАЯ ЯРКОСТЬ: базовая 130, максимум 255
            val redIntensity = (130 + smoothed100Hz * 125).toInt().coerceIn(0, 255)
            val greenIntensity = (130 + smoothed1000Hz * 125).toInt().coerceIn(0, 255)
            val blueIntensity = (130 + smoothed5000Hz * 125).toInt().coerceIn(0, 255)

            // Создаём градиент с плавными переходами
            val gradient = LinearGradient(
                0f, 0f,
                0f, height,
                intArrayOf(
                    Color.rgb(redIntensity, 0, 0),
                    Color.rgb(redIntensity, greenIntensity, 0),
                    Color.rgb(0, greenIntensity, 0),
                    Color.rgb(0, greenIntensity, blueIntensity),
                    Color.rgb(0, 0, blueIntensity)
                ),
                floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f),
                Shader.TileMode.CLAMP
            )

            paint.shader = gradient
            canvas.drawRect(0f, 0f, width, height, paint)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            visualizer?.release()
            visualizer = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}