package com.example.rgbplayer

import android.net.Uri

data class Track(
    val title: String,
    val artist: String,
    val duration: String,
    val uri: Uri // Ссылка на аудиофайл
)