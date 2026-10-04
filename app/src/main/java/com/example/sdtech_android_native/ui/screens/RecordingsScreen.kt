package com.example.sdtech_android_native.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sdtech_android_native.asr.TranscriptionState
import com.example.sdtech_android_native.data.AudioRecorderViewModel
import com.example.sdtech_android_native.data.VideoRecorderViewModel
import com.example.sdtech_android_native.model.SavedMediaItem
import com.example.sdtech_android_native.ui.theme.DeepPurple
import java.text.SimpleDateFormat
import java.util.Locale

private enum class RecordingsFilter(val label: String) {
    AUDIO("Audio"),
    VIDEO("Video"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingsScreen(
    audioViewModel: AudioRecorderViewModel,
    videoViewModel: VideoRecorderViewModel,
) {
    var filter by remember { mutableStateOf(RecordingsFilter.AUDIO) }
    var menuExpanded by remember { mutableStateOf(false) }

    val showAudio = filter == RecordingsFilter.AUDIO
    val audioFiles by audioViewModel.savedAudioFiles.collectAsState()
    val videoFiles by videoViewModel.savedVideoFiles.collectAsState()
    val isAudioRecording by audioViewModel.isRecording.collectAsState()
    val isVideoRecording by videoViewModel.isRecording.collectAsState()
    val transcriptions by audioViewModel.transcriptions.collectAsState()

    val savedFiles = if (showAudio) audioFiles else videoFiles
    val storagePath = if (showAudio) audioViewModel.storagePath else videoViewModel.storagePath
    val isRecording = if (showAudio) isAudioRecording else isVideoRecording

    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.US) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recordings") },
                actions = {
                    IconButton(onClick = {
                        if (showAudio) audioViewModel.loadSavedAudioFiles() else videoViewModel.loadSavedVideoFiles()
                    }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh list")
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            Row(
                modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Show:", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.width(12.dp))
                Box {
                    TextButton(onClick = { menuExpanded = true }) {
                        Text(filter.label)
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        RecordingsFilter.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = {
                                    filter = option
                                    menuExpanded = false
                                },
                            )
                        }
                    }
                }
            }

            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "${if (showAudio) "Saved Audio" else "Saved Videos"} (${savedFiles.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "20s Segments",
                        color = Color.Gray,
                        fontSize = 12.sp,
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Saved to: $storagePath",
                    color = Color.Gray,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            if (savedFiles.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (isRecording) {
                            "First 20s segment recording in progress..."
                        } else {
                            "No ${if (showAudio) "audio" else "video"} segments saved yet."
                        },
                        color = Color.Gray,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.padding(horizontal = 16.dp)) {
                    items(savedFiles, key = { it.path }) { item ->
                        RecordingRow(
                            item = item,
                            showAudio = showAudio,
                            formattedDate = dateFormat.format(item.timestamp),
                            transcriptionState = if (showAudio) transcriptions[item.path] else null,
                            onDelete = {
                                if (showAudio) audioViewModel.deleteAudioFile(item) else videoViewModel.deleteVideoFile(item)
                            },
                            onRetryTranscription = { audioViewModel.retryTranscription(item) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingRow(
    item: SavedMediaItem,
    showAudio: Boolean,
    formattedDate: String,
    transcriptionState: TranscriptionState?,
    onDelete: () -> Unit,
    onRetryTranscription: () -> Unit,
) {
    // Transcription now runs automatically as each segment is saved (see
    // AudioRecorderViewModel's transcriptionQueue); this button only reveals
    // the result, it never triggers the model itself.
    var showTranscript by remember(item.path) { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .height(40.dp)
                        .padding(end = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (showAudio) Icons.Filled.Audiotrack else Icons.Filled.Videocam,
                        contentDescription = null,
                        tint = DeepPurple,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "$formattedDate • ${item.formattedFileSize}",
                        fontSize = 12.sp,
                    )
                }
                if (showAudio) {
                    IconButton(
                        onClick = {
                            if (transcriptionState is TranscriptionState.Error) {
                                onRetryTranscription()
                            } else {
                                showTranscript = !showTranscript
                            }
                        },
                    ) {
                        if (transcriptionState is TranscriptionState.InProgress) {
                            CircularProgressIndicator(modifier = Modifier.height(20.dp).width(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Subtitles, contentDescription = "Show transcription", tint = DeepPurple)
                        }
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = Color(0xFFFF5252))
                }
            }

            if (showTranscript) {
                when (transcriptionState) {
                    is TranscriptionState.Done -> {
                        Text(
                            text = transcriptionState.text.ifBlank { "(no speech detected)" },
                            fontSize = 13.sp,
                            fontStyle = FontStyle.Italic,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }

                    is TranscriptionState.Error -> {
                        Text(
                            text = "Transcription failed: ${transcriptionState.message}",
                            fontSize = 12.sp,
                            color = Color(0xFFFF5252),
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }

                    is TranscriptionState.InProgress -> {
                        Text(
                            text = "Transcribing…",
                            fontSize = 12.sp,
                            color = Color.Gray,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }

                    null -> {
                        Text(
                            text = "Queued for transcription…",
                            fontSize = 12.sp,
                            color = Color.Gray,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}
