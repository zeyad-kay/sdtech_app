package com.example.sdtech_android_native.data

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.example.sdtech_android_native.asr.ParakeetTranscriber
import com.example.sdtech_android_native.asr.TranscriptionState
import com.example.sdtech_android_native.model.MediaType
import com.example.sdtech_android_native.model.SavedMediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "AudioRecordingEngine"
private const val SAMPLE_RATE = 16000
private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
private const val CHANNEL_COUNT = 1
private const val AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT
private const val BITS_PER_SAMPLE = 16

// ASR is temporarily disabled; flip back to true to re-enable preloading/transcription.
private const val ASR_ENABLED = false

/**
 * Captures audio continuously to a single raw-PCM file for the whole
 * recording session (one [AudioRecord] stream, running uninterrupted from
 * start to stop). [stopCapturing] only stops the microphone — it leaves the
 * raw PCM scratch file on disk until [finalizeRecording] is called
 * separately, since [com.example.sdtech_android_native.data.StreamingEngine]
 * may still be draining unsent bytes from it after recording has stopped.
 * [finalizeRecording] wraps the PCM in a WAV header, producing one playable
 * .wav file, and discards the scratch file.
 *
 * Hosted by [com.example.sdtech_android_native.session.AppSessionService]
 * (a foreground service) rather than a ViewModel, so capture keeps running
 * independent of the Activity's lifecycle — background mic access is
 * otherwise revoked by the platform once the app leaves the foreground.
 */
class AudioRecordingEngine(private val context: Context, private val scope: CoroutineScope) {

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _savedAudioFiles = MutableStateFlow<List<SavedMediaItem>>(emptyList())
    val savedAudioFiles: StateFlow<List<SavedMediaItem>> = _savedAudioFiles.asStateFlow()

    private val _transcriptions = MutableStateFlow<Map<String, TranscriptionState>>(emptyMap())
    val transcriptions: StateFlow<Map<String, TranscriptionState>> = _transcriptions.asStateFlow()

    val storagePath: String get() = context.filesDir.path

    /** The current session's raw-PCM scratch file, set for the lifetime of one recording — e.g. for [com.example.sdtech_android_native.data.StreamingEngine] to tail. */
    var currentPcmFile: File? = null
        private set

    private var captureThread: Thread? = null
    private val shouldRecord = AtomicBoolean(false)

    /** Set by [runCaptureLoop] once the mic has stopped, for [finalizeRecording] to pick up. */
    private var pendingPcmFile: File? = null

    // Finished recordings are transcribed one at a time, in save order.
    private val transcriptionQueue = Channel<SavedMediaItem>(Channel.UNLIMITED)

    init {
        loadSavedAudioFiles()
        scope.launch(Dispatchers.Default) {
            for (item in transcriptionQueue) {
                runTranscription(item)
            }
        }
    }

    fun loadSavedAudioFiles() {
        try {
            val dir = context.filesDir
            val loaded = dir.listFiles()
                ?.filter { it.isFile && it.extension == "wav" }
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

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val pcmFile = File(context.filesDir, "audio_$timestamp.pcm")

        currentPcmFile = pcmFile
        shouldRecord.set(true)
        audioRecord.startRecording()

        // Warm up the ASR model/tokenizer now so it's already loaded by the
        // time recording stops, instead of paying that load cost afterwards.
        if (ASR_ENABLED) {
            scope.launch(Dispatchers.Default) {
                try {
                    ParakeetTranscriber.preload(context)
                } catch (e: Exception) {
                    Log.e(TAG, "Error preloading ASR model", e)
                }
            }
        }

        captureThread = Thread({ runCaptureLoop(audioRecord, minBufferSize, pcmFile) }, "AudioCaptureThread").apply {
            start()
        }

        _isRecording.value = true
        return true
    }

    /** Stops the microphone. The raw PCM scratch file stays on disk — call [finalizeRecording] once nothing else needs to read it. */
    fun stopCapturing() {
        shouldRecord.set(false)
        captureThread?.join(2_000)
        captureThread = null
        _isRecording.value = false
    }

    /** Wraps the most recently stopped recording's raw PCM into a .wav and discards the scratch file. No-op if [stopCapturing] hasn't finished yet or was already finalized. */
    fun finalizeRecording() {
        val pcmFile = pendingPcmFile ?: return
        pendingPcmFile = null
        finishAndRegister(pcmFile)
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
        if (!ASR_ENABLED) return
        if (_transcriptions.value[item.path] is TranscriptionState.InProgress) return
        transcriptionQueue.trySend(item)
    }

    private suspend fun runTranscription(item: SavedMediaItem) {
        _transcriptions.update { it + (item.path to TranscriptionState.InProgress) }
        val result = try {
            val text = ParakeetTranscriber.transcribeFile(context, item.path)
            TranscriptionState.Done(text)
        } catch (e: Exception) {
            Log.e(TAG, "Error transcribing ${item.path}", e)
            TranscriptionState.Error(e.message ?: "Transcription failed")
        }
        _transcriptions.update { it + (item.path to result) }
    }

    /** Runs on [captureThread]: owns the single continuous [AudioRecord] stream for the whole session, streaming raw PCM straight to [pcmFile]. */
    private fun runCaptureLoop(audioRecord: AudioRecord, minBufferSize: Int, pcmFile: File) {
        val readBuffer = ByteArray(minBufferSize)

        try {
            BufferedOutputStream(FileOutputStream(pcmFile)).use { out ->
                while (shouldRecord.get()) {
                    val read = audioRecord.read(readBuffer, 0, readBuffer.size)
                    if (read <= 0) {
                        if (read < 0) Log.e(TAG, "AudioRecord.read error code: $read")
                        continue
                    }
                    out.write(readBuffer, 0, read)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in audio capture loop", e)
        } finally {
            try {
                audioRecord.stop()
            } catch (_: Exception) {
            }
            audioRecord.release()
            pendingPcmFile = pcmFile
        }
    }

    /** Wraps the raw PCM captured this session in a WAV header, producing one playable .wav file, then discards the temporary PCM file. */
    private fun finishAndRegister(pcmFile: File) {
        try {
            if (!pcmFile.exists() || pcmFile.length() == 0L) {
                pcmFile.delete()
                return
            }
            val wavFile = File(pcmFile.parentFile, pcmFile.nameWithoutExtension + ".wav")
            writeWavFile(pcmFile, wavFile)
            pcmFile.delete()

            val item = SavedMediaItem(
                path = wavFile.path,
                name = wavFile.name,
                timestamp = Date(),
                sizeInBytes = wavFile.length(),
                mediaType = MediaType.AUDIO,
            )
            _savedAudioFiles.update { listOf(item) + it }
            if (ASR_ENABLED) transcriptionQueue.trySend(item)
        } catch (e: Exception) {
            Log.e(TAG, "Error converting recording to WAV", e)
        } finally {
            currentPcmFile = null
        }
    }

    /** Writes a standard 44-byte PCM WAV header followed by the contents of [pcmFile] into [wavFile]. */
    private fun writeWavFile(pcmFile: File, wavFile: File) {
        val pcmLength = pcmFile.length()
        val byteRate = SAMPLE_RATE * CHANNEL_COUNT * BITS_PER_SAMPLE / 8
        val blockAlign = CHANNEL_COUNT * BITS_PER_SAMPLE / 8

        RandomAccessFile(wavFile, "rw").use { out ->
            out.writeAsciiLE("RIFF")
            out.writeIntLE((36 + pcmLength).toInt())
            out.writeAsciiLE("WAVE")
            out.writeAsciiLE("fmt ")
            out.writeIntLE(16)
            out.writeShortLE(1) // PCM
            out.writeShortLE(CHANNEL_COUNT)
            out.writeIntLE(SAMPLE_RATE)
            out.writeIntLE(byteRate)
            out.writeShortLE(blockAlign)
            out.writeShortLE(BITS_PER_SAMPLE)
            out.writeAsciiLE("data")
            out.writeIntLE(pcmLength.toInt())

            FileInputStream(pcmFile).use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                }
            }
        }
    }

    private fun RandomAccessFile.writeAsciiLE(chars: String) = write(chars.toByteArray(Charsets.US_ASCII))

    private fun RandomAccessFile.writeIntLE(value: Int) = write(
        byteArrayOf(
            (value and 0xff).toByte(),
            ((value shr 8) and 0xff).toByte(),
            ((value shr 16) and 0xff).toByte(),
            ((value shr 24) and 0xff).toByte(),
        ),
    )

    private fun RandomAccessFile.writeShortLE(value: Int) = write(
        byteArrayOf(
            (value and 0xff).toByte(),
            ((value shr 8) and 0xff).toByte(),
        ),
    )
}
