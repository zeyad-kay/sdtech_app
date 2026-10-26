package com.example.sdtech_android_native.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.view.TextureView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.sdtech_android_native.pairing.PairingInfo
import com.example.sdtech_android_native.session.AppSessionService
import com.example.sdtech_android_native.ui.theme.DeepPurple
import com.example.sdtech_android_native.ui.theme.RedAccent
import kotlinx.coroutines.launch

/** Shown after pairing: a "Start Streaming" button that requests a session from the paired device, then — only once it confirms `ok: true` — kicks off local audio/video capture and the RTSP push. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamingScreen(pairing: PairingInfo, service: AppSessionService, textureView: TextureView) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isAudioRecording by service.isAudioRecording.collectAsState()
    val isVideoRecording by service.isVideoRecording.collectAsState()
    val isStreaming = isAudioRecording && isVideoRecording

    var isRequestingSession by remember { mutableStateOf(false) }
    var isDraining by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    // Held between the session approval and whichever permission grant comes last.
    var pendingStreamingUrl by remember { mutableStateOf<String?>(null) }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            service.startAudioCapturing()
            pendingStreamingUrl?.let { service.startRtspStream(it) }
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) textureView.surfaceTexture?.let { service.startVideoCapturing(it) } }

    fun beginCapturing(streamingUrl: String) {
        val hasAudioPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        val hasCameraPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED

        if (hasAudioPermission) {
            service.startAudioCapturing()
            service.startRtspStream(streamingUrl)
        } else {
            pendingStreamingUrl = streamingUrl
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        if (hasCameraPermission) {
            textureView.surfaceTexture?.let { service.startVideoCapturing(it) }
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    fun startStreaming() {
        errorMessage = null
        isRequestingSession = true
        scope.launch {
            val result = service.requestStartSession()
            isRequestingSession = false
            if (result == null) {
                errorMessage = "Could not start session — the device didn't accept the request."
            } else {
                beginCapturing(result.streamingUrl)
            }
        }
    }

    fun stopStreaming() {
        service.stopRecordingAndStream(
            onDraining = {
                isDraining = true
                Toast.makeText(
                    context,
                    "Don't close the app yet — finishing upload to the server.",
                    Toast.LENGTH_LONG,
                ).show()
            },
            onFinished = {
                isDraining = false
                Toast.makeText(context, "Upload finished — safe to close the app.", Toast.LENGTH_LONG).show()
            },
        )
    }

    fun toggleStreaming() {
        if (isStreaming) stopStreaming() else startStreaming()
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Streaming") }) },
    ) { paddingValues ->
        Column(
            modifier = Modifier.padding(paddingValues).fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Button(
                onClick = { toggleStreaming() },
                enabled = !isRequestingSession && !isDraining,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isStreaming) RedAccent else DeepPurple,
                ),
            ) {
                if (isRequestingSession) {
                    CircularProgressIndicator(modifier = Modifier.height(18.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Text(if (isDraining) "Finishing..." else if (isStreaming) "Stop Streaming" else "Start Streaming")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            when {
                isDraining -> Text(
                    text = "Finishing upload to the server — don't close the app.",
                    color = DeepPurple,
                    textAlign = TextAlign.Center,
                )

                isStreaming -> Text(
                    text = "Audio and video capture started.",
                    color = DeepPurple,
                    textAlign = TextAlign.Center,
                )

                errorMessage != null -> Text(
                    text = errorMessage!!,
                    color = RedAccent,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
