package com.example.sdtech_android_native

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicNone
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import com.example.sdtech_android_native.pairing.PairingInfo
import com.example.sdtech_android_native.pairing.WifiConnectionState
import com.example.sdtech_android_native.session.AppSessionService
import com.example.sdtech_android_native.ui.screens.AudioCaptureScreen
import com.example.sdtech_android_native.ui.screens.QrScanScreen
import com.example.sdtech_android_native.ui.screens.RecordingsScreen
import com.example.sdtech_android_native.ui.screens.StreamingScreen
import com.example.sdtech_android_native.ui.screens.VideoCaptureScreen
import com.example.sdtech_android_native.ui.components.ConnectionStatusIndicator
import com.example.sdtech_android_native.ui.theme.DeepPurpleAccent
import com.example.sdtech_android_native.ui.theme.SdtechAndroidNativeTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SdtechAndroidNativeTheme {
                val context = LocalContext.current

                var permissionsGranted by remember {
                    mutableStateOf(
                        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    )
                }

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { result ->
                    permissionsGranted = result.values.all { it }
                }

                LaunchedEffect(Unit) {
                    if (!permissionsGranted) {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.CAMERA,
                                Manifest.permission.RECORD_AUDIO
                            )
                        )
                    }
                }

                if (!permissionsGranted) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Text("Camera and Microphone permissions are required to run this app.")
                            Spacer(modifier = Modifier.padding(8.dp))
                            Button(onClick = {
                                permissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.CAMERA,
                                        Manifest.permission.RECORD_AUDIO
                                    )
                                )
                            }) {
                                Text("Grant Permissions")
                            }
                        }
                    }
                } else {
                    // The WiFi connection and audio/video capture are all owned
                    // by one foreground service (not this Activity/Composable),
                    // so they survive the Activity backgrounding — e.g. on
                    // screen timeout — see AppSessionService's kdoc.
                    var session by remember { mutableStateOf<AppSessionService?>(null) }
                    DisposableEffect(Unit) {
                        val intent = Intent(context, AppSessionService::class.java)
                        val connection = object : ServiceConnection {
                            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                                session = (binder as AppSessionService.LocalBinder).getService()
                            }

                            override fun onServiceDisconnected(name: ComponentName?) {
                                session = null
                            }
                        }
                        ContextCompat.startForegroundService(context, intent)
                        context.bindService(intent, connection, BIND_AUTO_CREATE)
                        onDispose {
                            context.unbindService(connection)
                            context.stopService(intent)
                        }
                    }

                    val service = session
                    val fallbackWifiState = remember { MutableStateFlow<WifiConnectionState>(WifiConnectionState.Idle) }
                    val connectionState by (service?.wifiState ?: fallbackWifiState).collectAsState()

                    // Pairing is required on every launch (in-memory only, not
                    // persisted), so this is re-prompted each time the process
                    // starts rather than just once per install. Once paired, the
                    // app stays on the main screens even if the WiFi connection
                    // later drops — connectionState keeps updating live and is
                    // reflected by the connection indicator instead.
                    var pairedInfo by remember { mutableStateOf<PairingInfo?>(null) }

                    val paired = pairedInfo
                    if (service == null) {
                        // Binding is same-process and effectively instant; nothing to show meaningfully here.
                    } else if (paired == null) {
                        QrScanScreen(
                            connectionState = connectionState,
                            onConnect = { ssid, passphrase -> service.connectWifi(ssid, passphrase) },
                            onPaired = { info -> pairedInfo = info },
                        )
                    } else {
                        MainNavigationScreen(
                            pairing = paired,
                            connectionState = connectionState,
                            service = service,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MainNavigationScreen(
    pairing: PairingInfo,
    connectionState: WifiConnectionState,
    service: AppSessionService,
) {
    var currentIndex by remember { mutableIntStateOf(0) }

    // Shared across StreamingScreen (which can start video capture) and
    // VideoCaptureScreen (which displays it) — a TextureView can only be
    // attached to one composable, so only VideoCaptureScreen renders it.
    val context = LocalContext.current
    val textureView = remember { TextureView(context) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentIndex == 0,
                    onClick = { currentIndex = 0 },
                    icon = {
                        Icon(
                            imageVector = if (currentIndex == 0) Icons.Filled.Wifi else Icons.Filled.WifiOff,
                            contentDescription = null,
                            tint = if (currentIndex == 0) DeepPurpleAccent else LocalContentColor.current,
                        )
                    },
                    label = { Text("Streaming") },
                )
                NavigationBarItem(
                    selected = currentIndex == 1,
                    onClick = { currentIndex = 1 },
                    icon = {
                        Icon(
                            imageVector = if (currentIndex == 1) Icons.Filled.Mic else Icons.Filled.MicNone,
                            contentDescription = null,
                            tint = if (currentIndex == 1) DeepPurpleAccent else LocalContentColor.current,
                        )
                    },
                    label = { Text("Audio Capture") },
                )
                NavigationBarItem(
                    selected = currentIndex == 2,
                    onClick = { currentIndex = 2 },
                    icon = {
                        Icon(
                            imageVector = if (currentIndex == 2) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                            contentDescription = null,
                            tint = if (currentIndex == 2) DeepPurpleAccent else LocalContentColor.current,
                        )
                    },
                    label = { Text("Video Capture") },
                )
                NavigationBarItem(
                    selected = currentIndex == 3,
                    onClick = { currentIndex = 3 },
                    icon = {
                        Icon(
                            imageVector = if (currentIndex == 3) Icons.Filled.Folder else Icons.Filled.FolderOpen,
                            contentDescription = null,
                            tint = if (currentIndex == 3) DeepPurpleAccent else LocalContentColor.current,
                        )
                    },
                    label = { Text("Recordings") },
                )
            }
        },
    ) { paddingValues ->
        // Keep all four screens composed at once (mirroring Flutter's
        // IndexedStack) so audio/video recording keeps running when the user
        // switches tabs, only the selected one is visible/interactive.
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (currentIndex == 0) 1f else 0f)
                    .zIndex(if (currentIndex == 0) 1f else 0f),
            ) {
                StreamingScreen(pairing = pairing, service = service, textureView = textureView)
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (currentIndex == 1) 1f else 0f)
                    .zIndex(if (currentIndex == 1) 1f else 0f),
            ) {
                AudioCaptureScreen(service = service)
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (currentIndex == 2) 1f else 0f)
                    .zIndex(if (currentIndex == 2) 1f else 0f),
            ) {
                VideoCaptureScreen(service = service, textureView = textureView)
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (currentIndex == 3) 1f else 0f)
                    .zIndex(if (currentIndex == 3) 1f else 0f),
            ) {
                RecordingsScreen(service = service)
            }

            ConnectionStatusIndicator(
                state = connectionState,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .zIndex(2f)
                    .padding(top = 12.dp, end = 16.dp),
            )
        }
    }
}
