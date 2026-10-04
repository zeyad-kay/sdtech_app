package com.example.sdtech_android_native

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicNone
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.zIndex
import com.example.sdtech_android_native.data.AudioRecorderViewModel
import com.example.sdtech_android_native.data.VideoRecorderViewModel
import com.example.sdtech_android_native.ui.screens.AudioCaptureScreen
import com.example.sdtech_android_native.ui.screens.RecordingsScreen
import com.example.sdtech_android_native.ui.screens.VideoCaptureScreen
import com.example.sdtech_android_native.ui.theme.DeepPurpleAccent
import com.example.sdtech_android_native.ui.theme.SdtechAndroidNativeTheme

class MainActivity : ComponentActivity() {

    private val audioRecorderViewModel: AudioRecorderViewModel by viewModels()
    private val videoRecorderViewModel: VideoRecorderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SdtechAndroidNativeTheme {
                MainNavigationScreen(
                    audioViewModel = audioRecorderViewModel,
                    videoViewModel = videoRecorderViewModel,
                )
            }
        }
    }
}

@Composable
fun MainNavigationScreen(
    audioViewModel: AudioRecorderViewModel,
    videoViewModel: VideoRecorderViewModel,
) {
    var currentIndex by remember { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentIndex == 0,
                    onClick = { currentIndex = 0 },
                    icon = {
                        Icon(
                            imageVector = if (currentIndex == 0) Icons.Filled.Mic else Icons.Filled.MicNone,
                            contentDescription = null,
                            tint = if (currentIndex == 0) DeepPurpleAccent else androidx.compose.material3.LocalContentColor.current,
                        )
                    },
                    label = { Text("Audio Capture") },
                )
                NavigationBarItem(
                    selected = currentIndex == 1,
                    onClick = { currentIndex = 1 },
                    icon = {
                        Icon(
                            imageVector = if (currentIndex == 1) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                            contentDescription = null,
                            tint = if (currentIndex == 1) DeepPurpleAccent else androidx.compose.material3.LocalContentColor.current,
                        )
                    },
                    label = { Text("Video Capture") },
                )
                NavigationBarItem(
                    selected = currentIndex == 2,
                    onClick = { currentIndex = 2 },
                    icon = {
                        Icon(
                            imageVector = if (currentIndex == 2) Icons.Filled.Folder else Icons.Filled.FolderOpen,
                            contentDescription = null,
                            tint = if (currentIndex == 2) DeepPurpleAccent else androidx.compose.material3.LocalContentColor.current,
                        )
                    },
                    label = { Text("Recordings") },
                )
            }
        },
    ) { paddingValues ->
        // Keep all three screens composed at once (mirroring Flutter's
        // IndexedStack) so audio/video recording keeps running when the user
        // switches tabs, only the selected one is visible/interactive.
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (currentIndex == 0) 1f else 0f)
                    .zIndex(if (currentIndex == 0) 1f else 0f),
            ) {
                AudioCaptureScreen(viewModel = audioViewModel)
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (currentIndex == 1) 1f else 0f)
                    .zIndex(if (currentIndex == 1) 1f else 0f),
            ) {
                VideoCaptureScreen(viewModel = videoViewModel)
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (currentIndex == 2) 1f else 0f)
                    .zIndex(if (currentIndex == 2) 1f else 0f),
            ) {
                RecordingsScreen(audioViewModel = audioViewModel, videoViewModel = videoViewModel)
            }
        }
    }
}
