package com.example.sdtech_android_native.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.graphics.SurfaceTexture
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.sdtech_android_native.R
import com.example.sdtech_android_native.asr.TranscriptionState
import com.example.sdtech_android_native.data.AudioRecordingEngine
import com.example.sdtech_android_native.data.StreamingEngine
import com.example.sdtech_android_native.data.VideoRecordingEngine
import com.example.sdtech_android_native.model.SavedMediaItem
import com.example.sdtech_android_native.pairing.SessionClient
import com.example.sdtech_android_native.pairing.StartSessionResult
import com.example.sdtech_android_native.pairing.WifiConnectionState
import com.example.sdtech_android_native.pairing.WifiConnector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val CHANNEL_ID = "app_session"
private const val NOTIFICATION_ID = 1

/**
 * One foreground service hosting everything that needs to keep running
 * independent of the Activity's lifecycle: the paired WiFi connection, and
 * audio/video capture. The platform revokes mic/camera access — and tears
 * down WifiNetworkSpecifier connections — once an app leaves the foreground
 * (e.g. on screen timeout), so all three live here behind one persistent
 * notification rather than tied to the Activity or split across several
 * services (which would mean several notifications for what is really one
 * ongoing session).
 */
class AppSessionService : LifecycleService() {

    private val binder = LocalBinder()

    private lateinit var wifiConnector: WifiConnector
    private lateinit var audioEngine: AudioRecordingEngine
    private lateinit var videoEngine: VideoRecordingEngine
    private lateinit var streamingEngine: StreamingEngine

    private val _wifiState = MutableStateFlow<WifiConnectionState>(WifiConnectionState.Idle)
    val wifiState: StateFlow<WifiConnectionState> = _wifiState.asStateFlow()

    val isAudioRecording: StateFlow<Boolean> get() = audioEngine.isRecording
    val savedAudioFiles: StateFlow<List<SavedMediaItem>> get() = audioEngine.savedAudioFiles
    val audioTranscriptions: StateFlow<Map<String, TranscriptionState>> get() = audioEngine.transcriptions
    val audioStoragePath: String get() = audioEngine.storagePath

    val isVideoRecording: StateFlow<Boolean> get() = videoEngine.isRecording
    val isVideoStarting: StateFlow<Boolean> get() = videoEngine.isStarting
    val savedVideoFiles: StateFlow<List<SavedMediaItem>> get() = videoEngine.savedVideoFiles
    val videoStoragePath: String get() = videoEngine.storagePath

    inner class LocalBinder : Binder() {
        fun getService(): AppSessionService = this@AppSessionService
    }

    override fun onCreate() {
        super.onCreate()
        wifiConnector = WifiConnector(this, lifecycleScope)
        audioEngine = AudioRecordingEngine(this, lifecycleScope)
        videoEngine = VideoRecordingEngine(this)
        streamingEngine = StreamingEngine(lifecycleScope, wifiState)
        startForeground(NOTIFICATION_ID, buildNotification("Waiting to pair..."))
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    fun connectWifi(ssid: String, passphrase: String) {
        wifiConnector.connect(ssid, passphrase) { state ->
            _wifiState.value = state
            updateNotification(
                when (state) {
                    is WifiConnectionState.Idle -> "Waiting to pair..."
                    is WifiConnectionState.Connecting -> "Connecting to \"$ssid\"..."
                    is WifiConnectionState.Connected -> "Connected to \"$ssid\""
                    is WifiConnectionState.Disconnected -> "Disconnected — retrying..."
                },
            )
        }
    }

    fun startAudioCapturing(): Boolean = audioEngine.startCapturing()
    fun stopAudioCapturing() = audioEngine.stopCapturing()
    fun deleteAudioFile(item: SavedMediaItem) = audioEngine.deleteAudioFile(item)
    fun retryAudioTranscription(item: SavedMediaItem) = audioEngine.retryTranscription(item)
    fun loadSavedAudioFiles() = audioEngine.loadSavedAudioFiles()

    fun startVideoCapturing(surfaceTexture: SurfaceTexture) = videoEngine.startCapturing(surfaceTexture)
    fun stopVideoCapturing() = videoEngine.stopCapturing()
    fun deleteVideoFile(item: SavedMediaItem) = videoEngine.deleteVideoFile(item)
    fun loadSavedVideoFiles() = videoEngine.loadSavedVideoFiles()

    /**
     * Sends `{"command": "start_session"}` over TCP to port 8000 of the
     * paired network's own gateway (the hotspot's IP), and returns the
     * server's response only if it reports `ok: true` — null otherwise
     * (not connected, unreachable, or the server declined). Callers must not
     * start recording/streaming unless this returns non-null.
     */
    suspend fun requestStartSession(): StartSessionResult? {
        val network = connectedNetworkOrNull() ?: return null
        val hotspotIp = hotspotIpOrNull(network) ?: return null
        return SessionClient.startSession(network, hotspotIp).getOrNull()
    }

    /** Starts pushing the current recording session to [rtspUrl]. Call only once audio capture has actually started (needs its PCM file) — video capture, by contrast, is allowed to start later, since [StreamingEngine] polls for its scratch file. */
    fun startRtspStream(rtspUrl: String) {
        val pcmFile = audioEngine.currentPcmFile ?: return
        streamingEngine.start(pcmFile, videoEngine.videoFormat, { videoEngine.currentVideoFile }, rtspUrl)
    }

    /**
     * Stops recording immediately (the mic/camera stop right away), then lets
     * the already-queued audio/video finish streaming to the server before
     * disconnecting — so nothing captured gets lost even if the connection
     * was mid-outage when the user pressed stop. [onDraining] fires as soon
     * as recording has stopped (e.g. to warn the user not to close the app
     * yet), and [onFinished] once the server has been notified the session is
     * over and it's safe to leave.
     */
    fun stopRecordingAndStream(onDraining: () -> Unit, onFinished: () -> Unit) {
        videoEngine.stopCapturing()
        audioEngine.stopCapturing()
        onDraining()
        streamingEngine.finishAndAwaitDrain {
            streamingEngine.stop()
            audioEngine.finalizeRecording()
            videoEngine.finalizeRecording()
            lifecycleScope.launch {
                val network = connectedNetworkOrNull()
                val hotspotIp = network?.let { hotspotIpOrNull(it) }
                if (network != null && hotspotIp != null) {
                    SessionClient.stopSession(network, hotspotIp)
                }
                onFinished()
            }
        }
    }

    private fun connectedNetworkOrNull(): Network? =
        (wifiState.value as? WifiConnectionState.Connected)?.network

    private fun hotspotIpOrNull(network: Network): String? {
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        return connectivityManager.getLinkProperties(network)
            ?.routes
            ?.firstOrNull { it.isDefaultRoute }
            ?.gateway
            ?.hostAddress
    }

    override fun onDestroy() {
        streamingEngine.stop()
        wifiConnector.release()
        audioEngine.stopCapturing()
        audioEngine.finalizeRecording()
        videoEngine.stopCapturing()
        videoEngine.finalizeRecording()
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Device Session", NotificationManager.IMPORTANCE_LOW),
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("sdtech session active")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }
}
