package com.example.sdtech_android_native.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.sdtech_android_native.pairing.PairingInfo
import com.example.sdtech_android_native.pairing.QrCodeAnalyzer
import com.example.sdtech_android_native.pairing.WifiConnectionState
import com.example.sdtech_android_native.ui.theme.DeepPurple
import com.example.sdtech_android_native.ui.theme.DeepPurple200
import com.example.sdtech_android_native.ui.theme.DeepPurple400
import com.example.sdtech_android_native.ui.theme.Red
import com.example.sdtech_android_native.ui.theme.RedAccent
import kotlinx.coroutines.delay
import java.util.concurrent.Executors

private sealed interface PairingStatus {
    data object Scanning : PairingStatus
    data object Connecting : PairingStatus
    data class Error(val message: String) : PairingStatus
}

/**
 * Shown on every app launch before the main app: scans a pairing QR code
 * (JSON with ssid/passwd/deviceid/pubkey), asks [onConnect] to connect to
 * that WiFi network, then calls [onPaired] with the parsed info once
 * [connectionState] first reports [WifiConnectionState.Connected].
 *
 * The actual WiFi connection is owned by a foreground service (not this
 * screen) — see [WifiConnectionState] — so it survives this screen being
 * backgrounded/torn down.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScanScreen(
    connectionState: WifiConnectionState,
    onConnect: (ssid: String, passphrase: String) -> Unit,
    onPaired: (PairingInfo) -> Unit,
) {
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

    var status by remember { mutableStateOf<PairingStatus>(PairingStatus.Scanning) }
    var scannedInfo by remember { mutableStateOf<PairingInfo?>(null) }

    val previewView = remember { PreviewView(context) }
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
    val analyzer = remember {
        QrCodeAnalyzer { raw ->
            val info = PairingInfo.parse(raw)
            if (info == null) {
                status = PairingStatus.Error("QR code is not a valid pairing code.")
            } else {
                scannedInfo = info
                status = PairingStatus.Connecting
                onConnect(info.ssid, info.passwd)
            }
        }
    }

    // Reacts to the foreground service's connection outcome once a QR code
    // has been scanned (scannedInfo != null guards against the service's
    // initial Idle/stale state before any scan happened).
    LaunchedEffect(connectionState, scannedInfo) {
        val info = scannedInfo ?: return@LaunchedEffect
        when (val state = connectionState) {
            is WifiConnectionState.Connected -> onPaired(info)
            is WifiConnectionState.Disconnected -> status = PairingStatus.Error(state.message)
            else -> {}
        }
    }

    // Auto-retry a few seconds after a scan/connect failure so the user can
    // just re-present the QR code without tapping anything.
    LaunchedEffect(status) {
        if (status is PairingStatus.Error) {
            delay(2500)
            status = PairingStatus.Scanning
            scannedInfo = null
            analyzer.rearm()
        }
    }

    // This screen is swapped out for good once pairing succeeds (see
    // MainActivity.onPaired) — without explicitly unbinding here, CameraX
    // keeps thinking it owns the back camera. When VideoRecordingEngine later
    // opens that same camera via the raw Camera2 API, the camera service
    // force-closes CameraX's lingering session out from under it; CameraX
    // then spends 5s retrying/timing out trying to reconfigure a camera it no
    // longer has, and its QR ImageAnalysis spins forever waiting on frames
    // that never arrive. That churn on the single physical camera is what
    // was keeping the live video preview from ever receiving frames.
    DisposableEffect(Unit) {
        onDispose {
            analyzerExecutor.shutdown()
            ProcessCameraProvider.getInstance(context).get().unbindAll()
        }
    }

    LaunchedEffect(hasCameraPermission) {
        if (!hasCameraPermission) return@LaunchedEffect
        val cameraProvider = ProcessCameraProvider.getInstance(context).get()
        val preview = Preview.Builder().build().apply {
            setSurfaceProvider(previewView.surfaceProvider)
        }
        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .apply { setAnalyzer(analyzerExecutor, analyzer) }

        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            imageAnalysis,
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Pair Device") }) },
    ) { paddingValues ->
        Column(
            modifier = Modifier.padding(paddingValues).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .aspectRatio(1f)
                    .fillMaxSize()
                    .padding(24.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black)
                    .border(
                        width = if (status is PairingStatus.Error) 3.dp else 1.5.dp,
                        color = if (status is PairingStatus.Error) Red else DeepPurple400,
                        shape = RoundedCornerShape(16.dp),
                    ),
            ) {
                if (hasCameraPermission) {
                    AndroidView(modifier = Modifier.fillMaxSize(), factory = { previewView })
                }

                when {
                    !hasCameraPermission -> {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            androidx.compose.material3.Icon(
                                imageVector = Icons.Filled.CameraAlt,
                                contentDescription = null,
                                tint = RedAccent,
                                modifier = Modifier.height(48.dp).width(48.dp),
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Camera permission is required to scan the pairing QR code.",
                                textAlign = TextAlign.Center,
                                color = Color.White,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                                Text("Grant Permission")
                            }
                        }
                    }

                    status is PairingStatus.Connecting -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.6f)),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator(color = Color.White)
                            Spacer(modifier = Modifier.height(12.dp))
                            androidx.compose.material3.Icon(
                                imageVector = Icons.Filled.Wifi,
                                contentDescription = null,
                                tint = DeepPurple200,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Connecting to WiFi network...", color = Color.White)
                        }
                    }

                    status is PairingStatus.Error -> {
                        val message = (status as PairingStatus.Error).message
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.6f))
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = message,
                                textAlign = TextAlign.Center,
                                color = RedAccent,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Retrying...", color = Color.Gray, fontSize = 12.sp)
                        }
                    }

                    else -> {
                        Column(
                            modifier = Modifier.align(Alignment.TopCenter).padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            androidx.compose.material3.Icon(
                                imageVector = Icons.Filled.QrCodeScanner,
                                contentDescription = null,
                                tint = DeepPurple,
                            )
                        }
                    }
                }
            }

            Text(
                text = "Scan the QR code provided with your device to pair it and connect to its network.",
                textAlign = TextAlign.Center,
                color = Color.Gray,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
    }
}
