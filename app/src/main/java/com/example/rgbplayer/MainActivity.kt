@file:OptIn(UnstableApi::class)

package com.example.rgbplayer

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.audiofx.Visualizer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.edit
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale
import com.example.rgbplayer.adapter.TrackAdapter

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: TrackAdapter
    private val tracks = mutableListOf<Track>()
    private lateinit var player: ExoPlayer
    private var mediaSession: MediaSession? = null

    private lateinit var tvCurrentTrack: TextView
    private lateinit var tvCurrentArtist: TextView
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalTime: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var btnPlayPause: Button
    private lateinit var btnPrev: Button
    private lateinit var btnNext: Button
    private lateinit var btnSelectFolder: Button
    private lateinit var btnToggleVisualizer: Button
    private lateinit var btnFullscreen: Button
    private lateinit var rgbVisualizer: RGBVisualizerView

    private lateinit var btnRepeatOne: Button
    private lateinit var btnSequential: Button
    private lateinit var btnRandom: Button

    private var currentTrackIndex = 0
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private var savedTreeUri: Uri? = null
    private var visualizer: Visualizer? = null

    private var playMode = 0
    private val random = java.util.Random()
    private var isVisualizerVisible = true
    private var isFullscreenActive = false
    private lateinit var btnVisualMode: Button
    private var visualMode = 0 // 0 = полоски, 1 = лампы

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        var currentAudioSessionId: Int = 0
    }

    private val openDocumentTreeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            saveTreeUri(it)
            loadTracksFromUri(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 1. ЗАПРОС РАЗРЕШЕНИЯ НА МИКРОФОН (обязательно для Visualizer)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                1001 // Код запроса
            )
        }

        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("RGBPlayerPrefs", MODE_PRIVATE)

        tvCurrentTrack = findViewById(R.id.tvCurrentTrack)
        tvCurrentArtist = findViewById(R.id.tvCurrentArtist)
        tvCurrentTime = findViewById(R.id.tvCurrentTime)
        tvTotalTime = findViewById(R.id.tvTotalTime)
        seekBar = findViewById(R.id.seekBar)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnPrev = findViewById(R.id.btnPrev)
        btnNext = findViewById(R.id.btnNext)
        btnSelectFolder = findViewById(R.id.btnSelectFolder)
        btnToggleVisualizer = findViewById(R.id.btnToggleVisualizer)
        btnFullscreen = findViewById(R.id.btnFullscreen)
        rgbVisualizer = findViewById(R.id.rgbVisualizer)

        btnRepeatOne = findViewById(R.id.btnRepeatOne)
        btnSequential = findViewById(R.id.btnSequential)
        btnRandom = findViewById(R.id.btnRandom)

        player = ExoPlayer.Builder(this).build()
        mediaSession = MediaSession.Builder(this, player).build()
        setupPlayerListener()

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        btnVisualMode = findViewById(R.id.btnVisualMode)
        visualMode = prefs.getInt("visual_mode", 0)
        updateVisualMode()

        btnVisualMode.setOnClickListener {
            visualMode = (visualMode + 1) % 2
            prefs.edit().putInt("visual_mode", visualMode).apply()
            updateVisualMode()
        }

        adapter = TrackAdapter(tracks, { clickedTrack ->
            val index = tracks.indexOf(clickedTrack)
            if (index >= 0) {
                currentTrackIndex = index
                playCurrentTrack()
            }
        }, -1)

        recyclerView.adapter = adapter

        btnPlayPause.setOnClickListener {
            if (player.isPlaying) {
                // === ПАУЗА ===
                player.pause()
                visualizer?.enabled = false
                android.util.Log.d("VisualizerDebug", "⏸️ Пауза. Visualizer выключен.")
            } else {
                // === PLAY ===
                if (tracks.isEmpty()) {
                    Toast.makeText(this, "Сначала выберите папку с музыкой", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (currentTrackIndex < 0 || currentTrackIndex >= tracks.size) {
                    currentTrackIndex = 0
                }

                if (player.currentMediaItem == null) {
                    playCurrentTrack() // Внутри этой функции должен быть player.play()
                } else {
                    player.play()
                    updateCurrentTrackUI()
                }

                // 🎯 МАГИЯ ЗДЕСЬ:
                // 1. Сначала убеждаемся, что визуализатор привязан к текущей (уже активной!) сессии
                setupVisualizer()
                // 2. Принудительно включаем его, теперь, когда музыка ТОЧНО играет
                visualizer?.enabled = true

                val actualState = visualizer?.enabled
                android.util.Log.d("VisualizerDebug", "▶️ Play нажат. Попытка включения. Реальный статус enabled: $actualState")
            }
        }

        btnPrev.setOnClickListener {
            player.seekToPreviousMediaItem()
        }

        btnNext.setOnClickListener {
            when (playMode) {
                0 -> player.seekToNextMediaItem()
                1 -> {
                    player.seekTo(0)
                    player.play()
                }
                2 -> {
                    if (tracks.size > 1) {
                        var newIndex = random.nextInt(tracks.size)
                        while (newIndex == currentTrackIndex) {
                            newIndex = random.nextInt(tracks.size)
                        }
                        currentTrackIndex = newIndex
                        playCurrentTrack()
                    }
                }
            }
        }

        btnSelectFolder.setOnClickListener {
            openDocumentTreeLauncher.launch(null)
        }

        btnToggleVisualizer.setOnClickListener {
            if (isVisualizerVisible) {
                rgbVisualizer.visibility = android.view.View.GONE
                btnToggleVisualizer.text = "🎨 Показать визуализацию"
            } else {
                rgbVisualizer.visibility = android.view.View.VISIBLE
                btnToggleVisualizer.text = "🎨 Скрыть визуализацию"
            }
            isVisualizerVisible = !isVisualizerVisible
        }

        btnRepeatOne.setOnClickListener {
            playMode = 1
            updateModeButtons()
        }

        btnSequential.setOnClickListener {
            playMode = 0
            updateModeButtons()
        }

        btnRandom.setOnClickListener {
            playMode = 2
            updateModeButtons()
        }

        btnFullscreen.setOnClickListener {
            if (tracks.isNotEmpty()) {
                if (!player.isPlaying && player.currentMediaItem == null) {
                    playCurrentTrack()
                }
                currentAudioSessionId = player.audioSessionId
                val intent = Intent(this, RgbFullscreenActivity::class.java)
                intent.putExtra("AUDIO_SESSION_ID", currentAudioSessionId)
                startActivity(intent)
            } else {
                Toast.makeText(this, "Сначала выберите папку с музыкой", Toast.LENGTH_SHORT).show()
            }
        }

        updateModeButtons()

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    player.seekTo(progress.toLong())
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        loadSavedTreeUri()
        checkAndRequestRecordAudioPermission()
    }

    private fun updateModeButtons() {
        val inactiveColor = ColorStateList.valueOf(0xFF555555.toInt())
        val activeColor = ColorStateList.valueOf(0xFF6B4C9A.toInt())

        btnRepeatOne.backgroundTintList = inactiveColor
        btnSequential.backgroundTintList = inactiveColor
        btnRandom.backgroundTintList = inactiveColor

        when (playMode) {
            0 -> btnSequential.backgroundTintList = activeColor
            1 -> btnRepeatOne.backgroundTintList = activeColor
            2 -> btnRandom.backgroundTintList = activeColor
        }
    }

    private fun checkAndRequestRecordAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Разрешение получено! Визуализация включена.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Без этого разрешения визуализация не будет работать", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setupVisualizer() {
        val sessionId = player.audioSessionId
        android.util.Log.d("VisualizerDebug", "Попытка настройки. Session ID: $sessionId")

        if (sessionId == 0) {
            android.util.Log.e("VisualizerDebug", "ОШИБКА: Session ID = 0. Плеер ещё не инициализировал аудио.")
            return
        }

        // Освобождаем старый, если был, чтобы не было конфликтов
        visualizer?.release()
        visualizer = null

        // ПРОВЕРКА РАЗРЕШЕНИЯ ПРЯМО ЗДЕСЬ
        val hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        android.util.Log.d("VisualizerDebug", "Разрешение на микрофон есть? -> $hasPermission")

        if (!hasPermission) {
            android.util.Log.e("VisualizerDebug", "КРИТИЧЕСКАЯ ОШИБКА: Нет разрешения RECORD_AUDIO! Визуализатор НЕ БУДЕТ работать.")
            // Принудительно запрашиваем снова
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1001)
            return
        }

        try {
            visualizer = android.media.audiofx.Visualizer(sessionId)
            visualizer?.setCaptureSize(1024)

            visualizer?.setDataCaptureListener(
                object : android.media.audiofx.Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: android.media.audiofx.Visualizer?, w: ByteArray?, s: Int) {}

                    override fun onFftDataCapture(v: android.media.audiofx.Visualizer?, fft: ByteArray?, s: Int) {
                        android.util.Log.d("VisualizerDebug", "🔥 CALLBACK FIRED! Size: ${fft?.size}")
                        fft?.let { data -> rgbVisualizer.updateFFT(data) }
                    }
                },
                android.media.audiofx.Visualizer.getMaxCaptureRate() / 2,
                false,
                true
            )

            android.util.Log.d("VisualizerDebug", "Visualizer успешно создан и настроен.")

        } catch (e: Exception) {
            android.util.Log.e("VisualizerDebug", "Исключение при создании: ${e.message}", e)
        }
    }

    private fun loadSavedTreeUri() {
        val uriString = prefs.getString("saved_tree_uri", null)
        if (uriString != null) {
            savedTreeUri = uriString.toUri()
            try {
                contentResolver.takePersistableUriPermission(savedTreeUri!!, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                loadTracksFromUri(savedTreeUri!!)
            } catch (_: Exception) {
                Toast.makeText(this, "Доступ к папке потерян. Выберите папку снова.", Toast.LENGTH_LONG).show()
                openDocumentTreeLauncher.launch(null)
            }
        } else {
            Toast.makeText(this, "Выберите папку с музыкой", Toast.LENGTH_LONG).show()
            openDocumentTreeLauncher.launch(null)
        }
    }

    private fun saveTreeUri(uri: Uri) {
        savedTreeUri = uri
        prefs.edit { putString("saved_tree_uri", uri.toString()) }
        val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION
        contentResolver.takePersistableUriPermission(uri, takeFlags)
    }

    private fun setupPlayerListener() {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                btnPlayPause.text = if (isPlaying) "⏸" else "▶"
                if (isPlaying) {
                    startSeekBarUpdate()
                } else {
                    handler.removeCallbacksAndMessages(null)
                    // Сбрасываем визуализатор при паузе
                    rgbVisualizer.clearBars()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                super.onMediaItemTransition(mediaItem, reason)
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
                    currentTrackIndex = player.currentMediaItemIndex
                    updateCurrentTrackUI()
                    handler.postDelayed({
                        if (!isFullscreenActive) {
                            visualizer?.release()
                            visualizer = null
                            setupVisualizer()
                        }
                    }, 500)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    when (playMode) {
                        0 -> player.seekToNextMediaItem()
                        1 -> {
                            player.seekTo(0)
                            player.play()
                        }
                        2 -> {
                            if (tracks.size > 1) {
                                val randomIndex = random.nextInt(tracks.size)
                                player.seekTo(randomIndex, 0)
                                player.play()
                            }
                        }
                    }
                }
            }
        })
    }

    private fun updateCurrentTrackUI() {
        if (currentTrackIndex in tracks.indices) {
            val track = tracks[currentTrackIndex]
            tvCurrentTrack.text = track.title
            tvCurrentArtist.text = track.artist
            adapter.setCurrentTrackIndex(currentTrackIndex)
            startSeekBarUpdate()
        }
    }

    private fun startSeekBarUpdate() {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (player.isPlaying) {
                    val currentPosition = player.currentPosition.toInt()
                    val duration = player.duration.toInt()
                    if (duration > 0) {
                        seekBar.max = duration
                        seekBar.progress = currentPosition
                        tvCurrentTime.text = formatTime(currentPosition.toLong())
                        tvTotalTime.text = formatTime(duration.toLong())
                    }
                    handler.postDelayed(this, 500)
                }
            }
        }, 500)
    }

    private fun loadTracksFromUri(treeUri: Uri) {
        tracks.clear()
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DOCUMENT_ID
        )
        val cursor = contentResolver.query(childrenUri, projection, null, null, null)
        cursor?.use {
            val nameColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val idColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            while (it.moveToNext()) {
                val name = it.getString(nameColumn)
                val mimeType = it.getString(mimeColumn)
                val id = it.getString(idColumn)
                if (mimeType.startsWith("audio/")) {
                    val title = name.substringBeforeLast(".")
                    val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                    tracks.add(Track(title, "Локальный файл", "—", fileUri))
                }
            }
        }
        adapter.notifyDataSetChanged()
        if (tracks.isEmpty()) {
            Toast.makeText(this, "Аудиофайлы не найдены", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Найдено треков: ${tracks.size}", Toast.LENGTH_SHORT).show()
        }

        // Загружаем ВЕСЬ плейлист в ExoPlayer для корректной работы кнопок Next/Prev
        val mediaItems = tracks.map { track ->
            MediaItem.Builder()
                .setUri(track.uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(track.title)
                        .setArtist(track.artist)
                        .build()
                )
                .build()
        }
        player.setMediaItems(mediaItems)
        player.prepare()
    }

    private fun formatTime(ms: Long): String {
        val seconds = ms / 1000
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return String.format(Locale.getDefault(), "%d:%02d", minutes, remainingSeconds)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        visualizer?.release()
        mediaSession?.release()
        player.release()
    }

    override fun onPause() {
        super.onPause()
        isFullscreenActive = true
        // НЕ освобождаем визуализатор при паузе!
    }

    override fun onResume() {
        super.onResume()
        isFullscreenActive = false

        // Просто запускаем обновление ползунка, визуализатор не трогаем
        if (player.isPlaying) {
            startSeekBarUpdate()
        }
    }

    private fun playCurrentTrack() {
        if (tracks.isEmpty()) return
        player.seekTo(currentTrackIndex, 0)
        player.play()
        visualizer?.enabled = true
        android.util.Log.d("Visualizer", "▶️ Visualizer включен при запуске нового трека")

        updateCurrentTrackUI()

        // Создаём визуализатор с задержкой, чтобы плеер точно начал играть

    }

    private fun setupVisualizerWithRetry(attempt: Int) {
        if (attempt > 10) return // Максимум 10 попыток

        handler.postDelayed({
            if (player.audioSessionId != 0) {
                // Аудио сессия готова — создаём визуализатор
                visualizer?.release()
                visualizer = null
                setupVisualizer()
            } else {
                // Ещё не готова — пробуем через 200мс
                setupVisualizerWithRetry(attempt + 1)
            }
        }, 200)
    }
    private fun updateVisualMode() {
        when (visualMode) {
            0 -> {
                rgbVisualizer.mode = RGBVisualizerView.Mode.BARS
                btnVisualMode.text = " Режим: Полоски"
            }
            1 -> {
                rgbVisualizer.mode = RGBVisualizerView.Mode.LAMPS
                btnVisualMode.text = "💡 Режим: RGB лампы"
            }
        }
    }
}