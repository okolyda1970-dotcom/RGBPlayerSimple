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
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.rgbplayer.adapter.TrackAdapter
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: TrackAdapter
    private val tracks = mutableListOf<Track>()
    private lateinit var player: ExoPlayer

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
    private lateinit var btnFullscreen: Button // <-- ОБЪЯВЛЕНИЕ КНОПКИ
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
        btnFullscreen = findViewById(R.id.btnFullscreen) // <-- ИНИЦИАЛИЗАЦИЯ КНОПКИ
        rgbVisualizer = findViewById(R.id.rgbVisualizer)

        btnRepeatOne = findViewById(R.id.btnRepeatOne)
        btnSequential = findViewById(R.id.btnSequential)
        btnRandom = findViewById(R.id.btnRandom)

        player = ExoPlayer.Builder(this).build()
        setupPlayerListener()

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        // ЯВНАЯ передача всех трёх параметров по порядку
        adapter = TrackAdapter(
            tracks,
            { clickedTrack ->
                val index = tracks.indexOf(clickedTrack)
                if (index >= 0) {
                    currentTrackIndex = index
                    playCurrentTrack()
                }
            },
            -1 // Начальный индекс (ни один трек не выбран)
        )

        recyclerView.adapter = adapter

        btnPlayPause.setOnClickListener {
            if (player.isPlaying) {
                player.pause()
            } else {
                // Если список треков пуст — ничего не делаем
                if (tracks.isEmpty()) {
                    Toast.makeText(this, "Сначала выберите папку с музыкой", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                // Если трек не выбран — начинаем с первого
                if (currentTrackIndex < 0 || currentTrackIndex >= tracks.size) {
                    currentTrackIndex = 0
                }

                // Если плеер ещё ничего не загружал — запускаем первый трек
                if (player.currentMediaItem == null) {
                    playCurrentTrack()
                } else {
                    // Иначе просто возобновляем воспроизведение
                    player.play()
                }
            }
        }

        btnPrev.setOnClickListener {
            if (currentTrackIndex > 0) {
                currentTrackIndex--
                playCurrentTrack()
            }
        }

        btnNext.setOnClickListener {
            when (playMode) {
                0 -> {
                    if (currentTrackIndex < tracks.size - 1) {
                        currentTrackIndex++
                        playCurrentTrack()
                    }
                }
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

        // <-- ОБРАБОТЧИК КНОПКИ ПОЛНОЭКРАННОГО РЕЖИМА
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
        try {
            // Если уже есть активный визуализатор, не создаём новый
            if (visualizer != null) return

            val audioSessionId = player.audioSessionId
            if (audioSessionId != 0) {
                visualizer = Visualizer(audioSessionId).apply {
                    captureSize = Visualizer.getCaptureSizeRange()[1]
                    setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
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
                                rgbVisualizer.updateFFT(it)
                            }
                        }
                    }, Visualizer.getMaxCaptureRate() / 2, false, true)
                    enabled = true
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
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
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    when (playMode) {
                        0 -> {
                            if (currentTrackIndex < tracks.size - 1) {
                                currentTrackIndex++
                                playCurrentTrack()
                            }
                        }
                        1 -> { playCurrentTrack() }
                        2 -> {
                            if (tracks.size > 1) {
                                var newIndex = random.nextInt(tracks.size)
                                while (newIndex == currentTrackIndex) { newIndex = random.nextInt(tracks.size) }
                                currentTrackIndex = newIndex
                                playCurrentTrack()
                            }
                        }
                    }
                }
            }
        })
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
        player.release()
    }

    override fun onPause() {
        super.onPause()
        // Освобождаем визуализатор при уходе с экрана
        try {
            visualizer?.release()
            visualizer = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onResume() {
        super.onResume()
        isFullscreenActive = false

        // Восстанавливаем визуализатор при возврате с задержкой
        // Плееру нужно время, чтобы возобновить воспроизведение
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (player.isPlaying) {
                try {
                    visualizer?.release()
                    visualizer = null
                    setupVisualizer()
                } catch (e: Exception) {
                    e.printStackTrace()
                    // Пробуем ещё раз через 1 секунду
                    handler.postDelayed({
                        if (player.isPlaying) {
                            try {
                                setupVisualizer()
                            } catch (e2: Exception) {
                                e2.printStackTrace()
                            }
                        }
                    }, 1000)
                }
            }
        }, 800)
    }

    private fun playCurrentTrack() {
        if (tracks.isEmpty()) return

        val track = tracks[currentTrackIndex]
        val mediaItem = MediaItem.fromUri(track.uri)
        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()

        tvCurrentTrack.text = track.title
        tvCurrentArtist.text = track.artist
        startSeekBarUpdate()

        adapter.setCurrentTrackIndex(currentTrackIndex)

        // Пересоздаём визуализатор при смене трека
        handler.postDelayed({
            try {
                visualizer?.release()
                visualizer = null
                setupVisualizer()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, 500)
    }
}

