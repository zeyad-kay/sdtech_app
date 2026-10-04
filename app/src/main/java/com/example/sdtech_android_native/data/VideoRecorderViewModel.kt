package com.example.sdtech_android_native.data

import android.app.Application
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.example.sdtech_android_native.model.MediaType
import com.example.sdtech_android_native.model.SavedMediaItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "VideoRecorderViewModel"
private const val SEGMENT_SECONDS = 20

class VideoRecorderViewModel(application: Application) : AndroidViewModel(application) {

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _isStarting = MutableStateFlow(false)
    val isStarting: StateFlow<Boolean> = _isStarting.asStateFlow()

    private val _secondsRemaining = MutableStateFlow(SEGMENT_SECONDS)
    val secondsRemaining: StateFlow<Int> = _secondsRemaining.asStateFlow()

    private val _savedVideoFiles = MutableStateFlow<List<SavedMediaItem>>(emptyList())
    val savedVideoFiles: StateFlow<List<SavedMediaItem>> = _savedVideoFiles.asStateFlow()

    val storagePath: String get() = getApplication<Application>().filesDir.path

    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var cycleJob: Job? = null

    init {
        loadSavedVideoFiles()
    }

    fun loadSavedVideoFiles() {
        try {
            val dir = getApplication<Application>().filesDir
            val loaded = dir.listFiles()
                ?.filter { it.isFile && it.extension == "mp4" }
                ?.map {
                    SavedMediaItem(
                        path = it.path,
                        name = it.name,
                        timestamp = Date(it.lastModified()),
                        sizeInBytes = it.length(),
                        mediaType = MediaType.VIDEO,
                    )
                }
                ?.sortedByDescending { it.timestamp }
                ?: emptyList()
            _savedVideoFiles.value = loaded
        } catch (e: Exception) {
            Log.e(TAG, "Error loading saved video files", e)
        }
    }

    /**
     * Binds the camera and starts recording in one step, only once the user
     * actually taps Start. The camera is intentionally left completely
     * unbound while idle (matching the original app's behavior of not
     * touching the camera until recording begins) — otherwise CameraX's
     * Preview+VideoCapture use cases sit bound in the background on every
     * tab, contending with [AudioRecorderViewModel]'s AAC encoder for the
     * device's shared hardware codec and starving it of buffers.
     */
    fun startCapturing(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        if (_isRecording.value || _isStarting.value) return
        _isStarting.value = true

        val context = getApplication<Application>()
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                    .build()
                val capture = VideoCapture.withOutput(recorder)

                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    capture,
                )
                cameraProvider = provider
                videoCapture = capture

                beginRecording(capture)
                _isRecording.value = true
                _secondsRemaining.value = SEGMENT_SECONDS
                startCycleLoop(capture)
            } catch (e: Exception) {
                Log.e(TAG, "Error starting video recording", e)
                cameraProvider?.unbindAll()
                cameraProvider = null
                videoCapture = null
            } finally {
                _isStarting.value = false
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stopCapturing() {
        cycleJob?.cancel()
        cycleJob = null
        activeRecording?.stop()
        activeRecording = null
        cameraProvider?.unbindAll()
        cameraProvider = null
        videoCapture = null
        _isRecording.value = false
        _secondsRemaining.value = SEGMENT_SECONDS
    }

    fun deleteVideoFile(item: SavedMediaItem) {
        try {
            val file = File(item.path)
            if (file.exists()) file.delete()
            _savedVideoFiles.update { list -> list.filterNot { it.path == item.path } }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting video file", e)
        }
    }

    private fun startCycleLoop(capture: VideoCapture<Recorder>) {
        cycleJob?.cancel()
        cycleJob = viewModelScope.launch {
            while (isActive) {
                for (remaining in SEGMENT_SECONDS downTo 1) {
                    _secondsRemaining.value = remaining
                    kotlinx.coroutines.delay(1000)
                }
                if (_isRecording.value) {
                    activeRecording?.stop()
                    activeRecording = null
                    beginRecording(capture)
                }
            }
        }
    }

    private fun beginRecording(capture: VideoCapture<Recorder>) {
        val context = getApplication<Application>()
        val outputFile = generateVideoFile()
        val outputOptions = FileOutputOptions.Builder(outputFile).build()

        activeRecording = capture.output
            .prepareRecording(context, outputOptions)
            .start(ContextCompat.getMainExecutor(context)) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    if (event.hasError()) {
                        Log.e(TAG, "Video recording error: ${event.error}")
                    } else {
                        registerSavedVideo(outputFile)
                    }
                }
            }
    }

    private fun registerSavedVideo(file: File) {
        if (!file.exists()) return
        val item = SavedMediaItem(
            path = file.path,
            name = file.name,
            timestamp = Date(),
            sizeInBytes = file.length(),
            mediaType = MediaType.VIDEO,
        )
        _savedVideoFiles.update { listOf(item) + it }
    }

    private fun generateVideoFile(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(getApplication<Application>().filesDir, "video_$timestamp.mp4")
    }

    override fun onCleared() {
        cycleJob?.cancel()
        activeRecording?.stop()
        activeRecording = null
        cameraProvider?.unbindAll()
        super.onCleared()
    }
}
