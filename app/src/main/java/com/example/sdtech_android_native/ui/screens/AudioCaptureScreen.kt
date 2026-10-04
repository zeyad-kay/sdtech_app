package com.example.sdtech_android_native.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicNone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.sdtech_android_native.data.AudioRecorderViewModel
import com.example.sdtech_android_native.ui.theme.DeepPurple
import com.example.sdtech_android_native.ui.theme.DeepPurple200
import com.example.sdtech_android_native.ui.theme.DeepPurple300
import com.example.sdtech_android_native.ui.theme.DeepPurple900
import com.example.sdtech_android_native.ui.theme.Red
import com.example.sdtech_android_native.ui.theme.Red900
import com.example.sdtech_android_native.ui.theme.RedAccent
import com.example.sdtech_android_native.ui.theme.SurfaceContainerHigh

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioCaptureScreen(viewModel: AudioRecorderViewModel) {
    val isRecording by viewModel.isRecording.collectAsState()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.startCapturing()
    }

    fun toggleAudioCapture() {
        if (isRecording) {
            viewModel.stopCapturing()
        } else {
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            if (hasPermission) {
                viewModel.startCapturing()
            } else {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Live Audio Capture") })
        },
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .background(
                        color = if (isRecording) Red.copy(alpha = 0.15f) else SurfaceContainerHigh,
                        shape = RoundedCornerShape(20.dp),
                    )
                    .border(
                        width = if (isRecording) 2.5.dp else 1.dp,
                        color = if (isRecording) Red else DeepPurple300,
                        shape = RoundedCornerShape(20.dp),
                    )
                    .padding(vertical = 24.dp, horizontal = 16.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Box(
                            modifier = Modifier
                                .size(90.dp)
                                .background(
                                    color = if (isRecording) Red900.copy(alpha = 0.4f) else DeepPurple900.copy(alpha = 0.3f),
                                    shape = CircleShape,
                                ),
                        )
                        Icon(
                            imageVector = if (isRecording) Icons.Filled.Mic else Icons.Filled.MicNone,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = if (isRecording) RedAccent else DeepPurple200,
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = if (isRecording) "Capturing Audio Live" else "Audio Capture Ready",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    if (isRecording) {
                        Row(
                            modifier = Modifier
                                .background(
                                    color = Red900.copy(alpha = 0.6f),
                                    shape = RoundedCornerShape(20.dp),
                                )
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.FiberManualRecord,
                                contentDescription = null,
                                tint = RedAccent,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "REC",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                            )
                        }
                    } else {
                        Text(
                            text = "Press the play button to start 20s interval recording",
                            color = Color.Gray,
                            fontSize = 13.sp,
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    FloatingActionButton(
                        onClick = { toggleAudioCapture() },
                        containerColor = if (isRecording) Red else DeepPurple,
                    ) {
                        Icon(
                            imageVector = if (isRecording) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                            contentDescription = if (isRecording) "Stop" else "Start",
                            tint = Color.White,
                        )
                    }
                }
            }
        }
    }
}
