package com.example.sdtech_android_native.model

import java.util.Date

enum class MediaType { AUDIO, VIDEO }

data class SavedMediaItem(
    val path: String,
    val name: String,
    val timestamp: Date,
    val sizeInBytes: Long,
    val mediaType: MediaType,
) {
    val formattedFileSize: String
        get() = when {
            sizeInBytes < 1024 -> "$sizeInBytes B"
            sizeInBytes < 1024 * 1024 -> "${"%.1f".format(sizeInBytes / 1024.0)} KB"
            else -> "${"%.1f".format(sizeInBytes / (1024.0 * 1024.0))} MB"
        }
}
