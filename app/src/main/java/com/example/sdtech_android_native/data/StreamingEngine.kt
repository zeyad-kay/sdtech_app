package com.example.sdtech_android_native.data

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import com.example.sdtech_android_native.pairing.WifiConnectionState
import com.pedro.common.ConnectChecker
import com.pedro.rtsp.rtsp.RtspClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

private const val TAG = "StreamingEngine"

// TEMPORARY: video push disabled for now — audio-only. Set back to true to
// resume sending video segments (collection/demuxing logic is left intact).
private const val VIDEO_ENABLED = true
private const val RECONNECT_BACKOFF_MS = 3_000L
private const val IDLE_POLL_MS = 200L

// If the video encoder hasn't produced its format (SPS/PPS) within this long
// of starting, give up on video for the rest of this session and connect
// audio-only instead — a stuck/failed camera pipeline (e.g. a HAL hiccup)
// must not block the RTSP connection, and the audio drain, forever.
private const val VIDEO_INFO_TIMEOUT_MS = 8_000L

private const val AUDIO_SAMPLE_RATE = 16000
private const val AUDIO_CHANNEL_COUNT = 1
private const val AUDIO_BIT_RATE = 128_000
private const val PCM_CHUNK_BYTES = 4096 // ~128ms at 16kHz mono 16-bit

/**
 * Pushes the in-progress recording to a server-provided RTSP URL (from the
 * session-start handshake — see [com.example.sdtech_android_native.session.AppSessionService.requestStartSession])
 * using RootEncoder's
 * low-level [RtspClient] (fed pre-encoded samples directly, since the camera/mic
 * are already owned by [AudioRecordingEngine]/[VideoRecordingEngine], not this class).
 *
 * Audio: tails [AudioRecordingEngine]'s raw PCM scratch file byte-by-byte as it
 * grows, re-encoding each chunk to AAC via a persistent [MediaCodec] encoder.
 * Video: tails [VideoRecordingEngine]'s raw scratch file the same way — one
 * [VIDEO_RECORD_HEADER_SIZE]-prefixed record per encoded frame, already
 * H.264, no re-encode needed — instead of consuming a live in-memory stream.
 * SPS/PPS come from [VideoRecordingEngine.videoFormat], known as soon as its
 * encoder starts.
 *
 * Both tails pause in place on a dropped connection — nothing is ever lost,
 * just delayed, since each is reading from a file that keeps growing on disk
 * regardless of whether anything is currently being sent. There's no
 * in-memory queue or cap on either side; an outage of any length just delays
 * the stream rather than dropping frames. Reconnecting is gated on
 * [wifiState] reporting [WifiConnectionState.Connected], piggybacking on
 * [com.example.sdtech_android_native.pairing.WifiConnector]'s own retry loop
 * rather than running an independent network probe.
 */
class StreamingEngine(
    private val scope: CoroutineScope,
    private val wifiState: StateFlow<WifiConnectionState>,
) : ConnectChecker {

    // A fresh instance is created for every connection attempt (see
    // connectWhenReady) rather than reused — RtspClient owns a persistent
    // internal CoroutineScope/Job pair, and disconnect() tears it down
    // asynchronously in a throwaway scope of its own. Reusing one instance
    // across reconnects means a slow-to-finish teardown from attempt N can
    // race past attempt N+1's connect() and cancel its job instead. A new
    // instance per attempt has no shared state for that race to land on.
    @Volatile private var rtspClient = RtspClient(this)

    @Volatile private var running = false
    @Volatile private var videoInfoSet = false
    // Flips true if video never produces its SPS/PPS within VIDEO_INFO_TIMEOUT_MS
    // of starting — e.g. a stuck/failed camera pipeline. Once given up, this
    // session connects (and stays) audio-only; it doesn't retry video.
    @Volatile private var videoGivenUp = false
    private var savedSps: ByteBuffer? = null
    private var savedPps: ByteBuffer? = null

    private var reconnectJob: Job? = null
    private var audioJob: Job? = null
    private var videoFormatJob: Job? = null
    private var videoDrainJob: Job? = null

    private var audioEncoder: MediaCodec? = null
    @Volatile private var rtspUrl: String = ""

    // Set once recording has stopped (via finishAndAwaitDrain) — tailAudio/
    // tailVideo use this to know there's no more input coming, flush
    // whatever's left (including a final partial audio chunk), and exit
    // instead of waiting forever for more.
    @Volatile private var recordingStopped = false
    @Volatile private var audioFullyDrained = false
    @Volatile private var videoFullyDrained = false
    private var onFullyDrained: (() -> Unit)? = null

    /**
     * Starts streaming the session whose audio is tailed from [pcmFile] and
     * video from whatever [getVideoFile] returns, to [rtspUrl]. [getVideoFile]
     * is a provider rather than a plain [File] since [VideoRecordingEngine]
     * may not have created its scratch file yet at the moment this is called
     * (video and audio/streaming start independently) — [tailVideo] polls it
     * the same way [tailAudio] already polls for [pcmFile] to exist.
     */
    fun start(pcmFile: File, videoFormat: StateFlow<MediaFormat?>, getVideoFile: () -> File?, rtspUrl: String) {
        if (running) return
        running = true
        videoInfoSet = false
        videoGivenUp = false
        recordingStopped = false
        audioFullyDrained = false
        videoFullyDrained = !VIDEO_ENABLED
        this.rtspUrl = rtspUrl

        if (VIDEO_ENABLED) {
            videoFormatJob = scope.launch(Dispatchers.IO) {
                videoFormat.collect { format ->
                    if (format != null && !videoInfoSet) {
                        val sps = format.getByteBuffer("csd-0") ?: return@collect
                        savedSps = sps.duplicate()
                        savedPps = format.getByteBuffer("csd-1")?.duplicate()
                        videoInfoSet = true
                    }
                }
            }
            videoDrainJob = scope.launch(Dispatchers.IO) { tailVideo(getVideoFile) }
        }
        audioJob = scope.launch(Dispatchers.IO) { tailAudio(pcmFile) }

        connectWhenReady()
    }

    /** Stops streaming and disconnects. Does not affect local recording, which is owned elsewhere. */
    fun stop() {
        running = false
        reconnectJob?.cancel()
        audioJob?.cancel()
        videoFormatJob?.cancel()
        videoDrainJob?.cancel()
        reconnectJob = null
        audioJob = null
        videoFormatJob = null
        videoDrainJob = null
        onFullyDrained = null
        releaseAudioEncoder()
        if (rtspClient.isStreaming) {
            try {
                rtspClient.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Error disconnecting RTSP client", e)
            }
        }
    }

    /**
     * Signals that recording has stopped — no more audio/video will ever
     * arrive. Lets [tailAudio]/[tailVideo] flush whatever is still queued
     * (including a final partial audio chunk) instead of waiting forever for
     * more, then invokes [onComplete] once everything has actually been sent
     * — or immediately, if there was nothing left to send. Does not
     * disconnect by itself; call [stop] from [onComplete].
     */
    fun finishAndAwaitDrain(onComplete: () -> Unit) {
        onFullyDrained = onComplete
        recordingStopped = true
        maybeCompleteDrain()
    }

    private fun maybeCompleteDrain() {
        if (audioFullyDrained && videoFullyDrained) {
            onFullyDrained?.invoke()
            onFullyDrained = null
        }
    }

    /** Waits for WiFi + (if video is enabled) codec info — SPS/PPS, known once the encoder starts — before (re)connecting. Gives up on video (see [videoGivenUp]) rather than waiting past [VIDEO_INFO_TIMEOUT_MS]. */
    private fun connectWhenReady() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch(Dispatchers.IO) {
            var waitedMs = 0L
            while (isActive && running && VIDEO_ENABLED && !videoInfoSet && !videoGivenUp) {
                if (waitedMs >= VIDEO_INFO_TIMEOUT_MS) {
                    Log.e(TAG, "Video info not available after ${VIDEO_INFO_TIMEOUT_MS}ms — giving up on video, continuing audio-only")
                    giveUpOnVideo()
                    break
                }
                delay(IDLE_POLL_MS)
                waitedMs += IDLE_POLL_MS
            }
            if (!running) return@launch
            wifiState.first { it is WifiConnectionState.Connected }
            if (!running) return@launch
            try {
                val client = RtspClient(this@StreamingEngine)
                if (VIDEO_ENABLED && !videoGivenUp) {
                    client.setVideoInfo(savedSps!!.duplicate(), savedPps?.duplicate(), null)
                } else {
                    client.setOnlyAudio(true)
                }
                client.setAudioInfo(AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_COUNT > 1)
                rtspClient = client
                client.connect(rtspUrl)
            } catch (e: Exception) {
                Log.e(TAG, "Error connecting RTSP client", e)
                scheduleReconnect()
            }
        }
    }

    /** A stuck/failed video pipeline must not block the RTSP connection (or the audio drain) forever — stop waiting on it and treat it as already drained for the rest of this session. */
    private fun giveUpOnVideo() {
        videoGivenUp = true
        videoFormatJob?.cancel()
        videoDrainJob?.cancel()
        videoFullyDrained = true
        maybeCompleteDrain()
    }

    private fun scheduleReconnect() {
        if (!running) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch(Dispatchers.IO) {
            delay(RECONNECT_BACKOFF_MS)
            if (running) connectWhenReady()
        }
    }

    // --- Audio: tail the raw PCM scratch file and re-encode to AAC ---

    private suspend fun tailAudio(pcmFile: File) {
        while (scope.isActive && running && !pcmFile.exists()) delay(IDLE_POLL_MS)
        if (!running) return

        val encoder = createAudioEncoder() ?: return
        audioEncoder = encoder
        val bufferInfo = MediaCodec.BufferInfo()
        var offset = 0L
        var samplesFed = 0L

        try {
            RandomAccessFile(pcmFile, "r").use { raf ->
                while (scope.isActive && running) {
                    if (!rtspClient.isStreaming) {
                        delay(IDLE_POLL_MS)
                        continue
                    }
                    val available = raf.length() - offset
                    val chunkSize = when {
                        available >= PCM_CHUNK_BYTES -> PCM_CHUNK_BYTES
                        recordingStopped && available > 0 -> available.toInt()
                        recordingStopped -> break // nothing left, ever — fully drained
                        else -> {
                            delay(IDLE_POLL_MS)
                            continue
                        }
                    }
                    val readBuffer = ByteArray(chunkSize)
                    raf.seek(offset)
                    raf.readFully(readBuffer)

                    val ptsUs = samplesFed * 1_000_000L / AUDIO_SAMPLE_RATE
                    // Only advance past this chunk once it's actually been handed to the
                    // RTSP client successfully — a failed send (e.g. the connection just
                    // dropped) must not be skipped over, or it's lost for good once we
                    // reconnect and resume tailing from a later offset.
                    if (!encodeAndSendAudio(encoder, bufferInfo, readBuffer, ptsUs)) {
                        delay(IDLE_POLL_MS)
                        continue
                    }
                    offset += chunkSize
                    samplesFed += chunkSize / (2 * AUDIO_CHANNEL_COUNT)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error tailing audio", e)
        } finally {
            releaseAudioEncoder()
            audioFullyDrained = true
            maybeCompleteDrain()
        }
    }

    private fun createAudioEncoder(): MediaCodec? = try {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_COUNT).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, PCM_CHUNK_BYTES * 2)
        }
        MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
    } catch (e: Exception) {
        Log.e(TAG, "Error creating audio encoder", e)
        null
    }

    /**
     * Returns false if this chunk wasn't fully handed off to the RTSP client —
     * either the encoder had no input buffer free (transient backpressure) or
     * a [RtspClient.sendAudio] call threw (e.g. the connection just dropped) —
     * so the caller knows not to advance past it, and retries the same PCM on
     * the next call.
     */
    private fun encodeAndSendAudio(encoder: MediaCodec, bufferInfo: MediaCodec.BufferInfo, pcm: ByteArray, ptsUs: Long): Boolean {
        val inputIndex = encoder.dequeueInputBuffer(10_000)
        if (inputIndex < 0) return false
        val inputBuffer = encoder.getInputBuffer(inputIndex)
        inputBuffer?.clear()
        inputBuffer?.put(pcm)
        encoder.queueInputBuffer(inputIndex, 0, pcm.size, ptsUs, 0)

        var allSent = true
        while (true) {
            val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
            if (outputIndex < 0) return allSent
            val outputBuffer = encoder.getOutputBuffer(outputIndex)
            if (outputBuffer != null && bufferInfo.size > 0 && rtspClient.isStreaming) {
                outputBuffer.position(bufferInfo.offset)
                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                // Independent copies — RtspClient.sendAudio() may process this
                // asynchronously on its own sender thread/queue, and we release
                // this buffer back to the codec's pool immediately after
                // (reused by future encode calls), and reuse the same mutable
                // bufferInfo for the next frame right after this loop iterates.
                // Passing either directly risked the sender reading corrupted
                // or already-overwritten data/timestamps for this frame.
                val copy = ByteBuffer.allocateDirect(bufferInfo.size).apply {
                    put(outputBuffer)
                    flip()
                }
                val copyInfo = MediaCodec.BufferInfo().apply {
                    offset = 0
                    size = bufferInfo.size
                    presentationTimeUs = bufferInfo.presentationTimeUs
                    flags = bufferInfo.flags
                }
                try {
                    rtspClient.sendAudio(copy, copyInfo)
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending audio frame", e)
                    allSent = false
                }
            }
            encoder.releaseOutputBuffer(outputIndex, false)
        }
    }

    private fun releaseAudioEncoder() {
        audioEncoder?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            try {
                it.release()
            } catch (_: Exception) {
            }
        }
        audioEncoder = null
    }

    // --- Video: tail VideoRecordingEngine's raw scratch file, same approach as audio ---

    /**
     * Mirrors [tailAudio] almost exactly, reading [VideoRecordingEngine]'s
     * [VIDEO_RECORD_HEADER_SIZE]-prefixed records instead of fixed-size PCM
     * chunks: wait for the file (via [getVideoFile], polled since it may not
     * exist yet), then tail it by byte offset, re-sending each complete
     * record as-is (already H.264, no re-encode).
     */
    private suspend fun tailVideo(getVideoFile: () -> File?) {
        var videoFile: File?
        while (true) {
            if (!scope.isActive || !running) return
            videoFile = getVideoFile()
            if (videoFile != null && videoFile.exists()) break
            delay(IDLE_POLL_MS)
        }

        var offset = 0L
        try {
            RandomAccessFile(videoFile, "r").use { raf ->
                while (scope.isActive && running) {
                    if (!rtspClient.isStreaming) {
                        delay(IDLE_POLL_MS)
                        continue
                    }
                    val available = raf.length() - offset
                    if (available < VIDEO_RECORD_HEADER_SIZE) {
                        if (recordingStopped && available <= 0) break // nothing left, ever — fully drained
                        delay(IDLE_POLL_MS)
                        continue
                    }

                    raf.seek(offset)
                    val presentationTimeUs = raf.readLong()
                    val isKeyFrame = raf.readBoolean()
                    val size = raf.readInt()
                    val recordSize = VIDEO_RECORD_HEADER_SIZE + size
                    if (available < recordSize) {
                        // Header landed but the payload hasn't fully flushed
                        // yet — don't advance offset, re-read this same
                        // record once more data is on disk.
                        delay(IDLE_POLL_MS)
                        continue
                    }

                    val frameBytes = ByteArray(size)
                    raf.readFully(frameBytes)

                    val bufferInfo = MediaCodec.BufferInfo().apply {
                        this.offset = 0
                        this.size = size
                        this.presentationTimeUs = presentationTimeUs
                        flags = if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    }
                    // Only advance past this record once it's actually been sent —
                    // a failed send (e.g. the connection just dropped) must not be
                    // skipped over, or it's lost for good once we reconnect and
                    // resume tailing from a later offset.
                    val sent = try {
                        rtspClient.sendVideo(ByteBuffer.wrap(frameBytes), bufferInfo)
                        true
                    } catch (e: Exception) {
                        Log.e(TAG, "Error sending video frame", e)
                        false
                    }
                    if (!sent) {
                        delay(IDLE_POLL_MS)
                        continue
                    }
                    offset += recordSize
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error tailing video", e)
        } finally {
            videoFullyDrained = true
            maybeCompleteDrain()
        }
    }

    // --- ConnectChecker ---

    override fun onConnectionStarted(url: String) {
        Log.i(TAG, "RTSP connection started: $url")
    }

    override fun onConnectionSuccess() {
        Log.i(TAG, "RTSP connected")
    }

    override fun onConnectionFailed(reason: String) {
        Log.e(TAG, "RTSP connection failed: $reason")
        scheduleReconnect()
    }

    override fun onDisconnect() {
        Log.i(TAG, "RTSP disconnected")
        scheduleReconnect()
    }

    override fun onAuthError() {
        Log.e(TAG, "RTSP auth error")
    }

    override fun onAuthSuccess() {
        Log.i(TAG, "RTSP auth success")
    }

    override fun onNewBitrate(bitrate: Long) {
        // Not used — no adaptive bitrate control needed for this use case.
    }
}
