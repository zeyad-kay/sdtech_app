package com.example.sdtech_android_native.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.sdtech_android_native.session.AppSessionService
import com.example.sdtech_android_native.ui.theme.DeepPurple
import com.example.sdtech_android_native.ui.theme.DeepPurple200
import com.example.sdtech_android_native.ui.theme.DeepPurple400
import com.example.sdtech_android_native.ui.theme.Red
import com.example.sdtech_android_native.ui.theme.Red900
import com.example.sdtech_android_native.ui.theme.RedAccent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoCaptureScreen(service: AppSessionService, textureView: TextureView) {
    val isRecording by service.isVideoRecording.collectAsState()
    val isStarting by service.isVideoStarting.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasCameraPermission = granted }

    // Camera permission can also be granted from StreamingScreen's own
    // launcher (both screens share this TextureView). Re-check on resume so
    // this screen's AndroidView actually mounts the texture in that case —
    // otherwise it stays stuck at whatever hasCameraPermission was on first
    // composition, the TextureView never attaches, and the preview never
    // appears/updates.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasCameraPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA,
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun toggleVideoCapture() {
        if (isRecording) {
            service.stopVideoCapturing()
        } else if (hasCameraPermission) {
            textureView.surfaceTexture?.let { service.startVideoCapturing(it) }
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Live Video Capture") })
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(6f)
                    .padding(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .aspectRatio(9f / 16f)
                        .fillMaxSize()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black)
                        .border(
                            width = if (isRecording) 3.dp else 1.5.dp,
                            color = if (isRecording) Red else DeepPurple400,
                            shape = RoundedCornerShape(16.dp),
                        ),
                ) {
                    // Camera preview sits at the bottom of the stack, bound as soon as
                    // permission is granted so startCapturing() has a use case ready to
                    // go. It's only visible to the user once recording is underway —
                    // the idle/permission overlays below fully cover it until then.
                    if (hasCameraPermission) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { textureView },
                        )
                    }

                    when {
                        !hasCameraPermission -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black)
                                    .align(Alignment.Center)
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.CameraAlt,
                                    contentDescription = null,
                                    tint = RedAccent,
                                    modifier = Modifier.height(48.dp).width(48.dp),
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Camera permission required for live video capture.",
                                    textAlign = TextAlign.Center,
                                    color = Color.White,
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                                    Text("Grant Permission")
                                }
                            }
                        }

                        isStarting -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black)
                                    .align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                CircularProgressIndicator()
                                Spacer(modifier = Modifier.height(12.dp))
                                Text("Starting camera preview...", color = Color.White)
                            }
                        }

                        !isRecording -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black)
                                    .align(Alignment.Center)
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Videocam,
                                    contentDescription = null,
                                    tint = DeepPurple200,
                                    modifier = Modifier.height(56.dp).width(56.dp),
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Live Video Capture Idle",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Tap \"Start Capturing Live Video\" below to start the live camera feed.",
                                    textAlign = TextAlign.Center,
                                    fontSize = 13.sp,
                                    color = Color.Gray,
                                )
                            }
                        }
                    }

                    if (isRecording) {
                        Row(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(12.dp)
                                .background(
                                    color = Red900.copy(alpha = 0.8f),
                                    shape = RoundedCornerShape(20.dp),
                                )
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.FiberManualRecord,
                                contentDescription = null,
                                tint = RedAccent,
                                modifier = Modifier.height(14.dp).width(14.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "REC",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                            )
                        }
                    }

                    FloatingActionButton(
                        onClick = { toggleVideoCapture() },
                        containerColor = if (isRecording) Red else DeepPurple,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp),
                    ) {
                        Icon(
                            imageVector = if (isRecording) Icons.Filled.Stop else Icons.Filled.Videocam,
                            contentDescription = if (isRecording) "Stop" else "Start",
                            tint = Color.White,
                        )
                    }
                }
            }
        }
    }
}
