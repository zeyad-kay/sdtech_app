package com.example.sdtech_android_native.data

import android.annotation.SuppressLint
import android.app.Application
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.sdtech_android_native.asr.ParakeetTranscriber
import com.example.sdtech_android_native.asr.TranscriptionState
import com.example.sdtech_android_native.model.MediaType
import com.example.sdtech_android_native.model.SavedMediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
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
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "AudioRecorderViewModel"
private const val SEGMENT_SECONDS = 20
private const val SAMPLE_RATE = 44100
private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
private const val CHANNEL_COUNT = 1
private const val AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT

/**
 * Captures audio into rolling 20s AAC segments without ever stopping the
 * microphone between segments: a single [AudioRecord] stream runs
 * continuously on a dedicated thread, and only the downstream
 * [AacSegmentEncoder]/output file is swapped at each boundary. Unlike a
 * MediaRecorder-stop/restart cycle, this introduces no gap in the captured
 * audio — important for transcription, where a dropped ~100-300ms at a
 * segment boundary can clip a word.
 */
class AudioRecorderViewModel(application: Application) : AndroidViewModel(application) {

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _secondsRemaining = MutableStateFlow(SEGMENT_SECONDS)
    val secondsRemaining: StateFlow<Int> = _secondsRemaining.asStateFlow()

    private val _savedAudioFiles = MutableStateFlow<List<SavedMediaItem>>(emptyList())
    val savedAudioFiles: StateFlow<List<SavedMediaItem>> = _savedAudioFiles.asStateFlow()

    private val _transcriptions = MutableStateFlow<Map<String, TranscriptionState>>(emptyMap())
    val transcriptions: StateFlow<Map<String, TranscriptionState>> = _transcriptions.asStateFlow()

    val storagePath: String get() = getApplication<Application>().filesDir.path

    private var captureThread: Thread? = null
    private val shouldRecord = AtomicBoolean(false)
    private var countdownJob: Job? = null

    // Segments are transcribed one at a time, in save order: a segment is
    // queued the moment it's saved, and the consumer below processes them
    // sequentially, so transcription of segment N finishes (or is still
    // running) by the time segment N+1 is queued ~20s later.
    private val transcriptionQueue = Channel<SavedMediaItem>(Channel.UNLIMITED)

    init {
        loadSavedAudioFiles()
        viewModelScope.launch(Dispatchers.Default) {
            for (item in transcriptionQueue) {
                runTranscription(item)
            }
        }
    }

    fun loadSavedAudioFiles() {
        try {
            val dir = getApplication<Application>().filesDir
            val loaded = dir.listFiles()
                ?.filter { it.isFile && (it.extension == "m4a" || it.extension == "aac" || it.extension == "wav") }
                ?.map {
                    SavedMediaItem(
                        path = it.path,
                        name = it.name,
                        timestamp = Date(it.lastModified()),
                        sizeInBytes = it.length(),
                        mediaType = MediaType.AUDIO,
                    )
                }
                ?.sortedByDescending { it.timestamp }
                ?: emptyList()
            _savedAudioFiles.value = loaded
        } catch (e: Exception) {
            Log.e(TAG, "Error loading saved audio files", e)
        }
    }

    @SuppressLint("MissingPermission") // caller verifies RECORD_AUDIO before calling
    fun startCapturing(): Boolean {
        if (_isRecording.value) return true

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_ENCODING)
        if (minBufferSize <= 0) {
            Log.e(TAG, "AudioRecord.getMinBufferSize failed: $minBufferSize")
            return false
        }

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_ENCODING,
            minBufferSize * 4,
        )
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            audioRecord.release()
            return false
        }

        shouldRecord.set(true)
        audioRecord.startRecording()

        // Warm up the ASR model/tokenizer now so it's already loaded by the
        // time the first 20s segment is ready, instead of paying that load
        // cost on the first transcription.
        viewModelScope.launch(Dispatchers.Default) {
            try {
                ParakeetTranscriber.preload(getApplication())
            } catch (e: Exception) {
                Log.e(TAG, "Error preloading ASR model", e)
            }
        }

        captureThread = Thread({ runCaptureLoop(audioRecord, minBufferSize) }, "AudioCaptureThread").apply {
            start()
        }

        _isRecording.value = true
        _secondsRemaining.value = SEGMENT_SECONDS
        startCountdown()
        return true
    }

    fun stopCapturing() {
        countdownJob?.cancel()
        countdownJob = null
        shouldRecord.set(false)
        captureThread?.join(2_000)
        captureThread = null
        _isRecording.value = false
        _secondsRemaining.value = SEGMENT_SECONDS
    }

    fun deleteAudioFile(item: SavedMediaItem) {
        try {
            val file = File(item.path)
            if (file.exists()) file.delete()
            _savedAudioFiles.update { list -> list.filterNot { it.path == item.path } }
            _transcriptions.update { it - item.path }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting audio file", e)
        }
    }

    /** Re-queues [item] for transcription (e.g. to retry after an error) — still processed in order by the single consumer. */
    fun retryTranscription(item: SavedMediaItem) {
        if (_transcriptions.value[item.path] is TranscriptionState.InProgress) return
        transcriptionQueue.trySend(item)
    }

    private suspend fun runTranscription(item: SavedMediaItem) {
        _transcriptions.update { it + (item.path to TranscriptionState.InProgress) }
        val result = try {
            val text = ParakeetTranscriber.transcribeFile(getApplication(), item.path)
            TranscriptionState.Done(text)
        } catch (e: Exception) {
            Log.e(TAG, "Error transcribing ${item.path}", e)
            TranscriptionState.Error(e.message ?: "Transcription failed")
        }
        _transcriptions.update { it + (item.path to result) }
    }

    /** Cosmetic 1s UI countdown; decoupled from the sample-accurate segment boundary in [runCaptureLoop]. */
    private fun startCountdown() {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            while (isActive) {
                for (remaining in SEGMENT_SECONDS downTo 1) {
                    _secondsRemaining.value = remaining
                    kotlinx.coroutines.delay(1000)
                }
            }
        }
    }

    /** Runs on [captureThread]: owns the single continuous [AudioRecord] stream for the whole session. */
    private fun runCaptureLoop(audioRecord: AudioRecord, minBufferSize: Int) {
        val readBuffer = ByteArray(minBufferSize)
        val segmentSampleLimit = SAMPLE_RATE.toLong() * SEGMENT_SECONDS
        var samplesInSegment = 0L
        var encoder = createSegmentEncoder()

        try {
            while (shouldRecord.get()) {
                val read = audioRecord.read(readBuffer, 0, readBuffer.size)
                if (read <= 0) {
                    if (read < 0) Log.e(TAG, "AudioRecord.read error code: $read")
                    continue
                }

                encoder.encode(readBuffer, read)
                samplesInSegment += read / 2 // 16-bit mono: 2 bytes per sample

                if (samplesInSegment >= segmentSampleLimit) {
                    finishAndRegister(encoder)
                    encoder = createSegmentEncoder()
                    samplesInSegment = 0
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in audio capture loop", e)
        } finally {
            finishAndRegister(encoder)
            try {
                audioRecord.stop()
            } catch (_: Exception) {
            }
            audioRecord.release()
        }
    }

    private fun createSegmentEncoder(): AacSegmentEncoder {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(getApplication<Application>().filesDir, "audio_$timestamp.m4a")
        return AacSegmentEncoder(file, SAMPLE_RATE, CHANNEL_COUNT)
    }

    private fun finishAndRegister(encoder: AacSegmentEncoder) {
        try {
            val file = encoder.finish()
            if (file.exists() && file.length() > 0) {
                val item = SavedMediaItem(
                    path = file.path,
                    name = file.name,
                    timestamp = Date(),
                    sizeInBytes = file.length(),
                    mediaType = MediaType.AUDIO,
                )
                _savedAudioFiles.update { listOf(item) + it }
                transcriptionQueue.trySend(item)
            } else {
                file.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finishing audio segment", e)
        }
    }

    override fun onCleared() {
        stopCapturing()
        super.onCleared()
    }
}
