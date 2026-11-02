package com.example.sdtech_android_native.data

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.example.sdtech_android_native.model.MediaType
import com.example.sdtech_android_native.model.SavedMediaItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "VideoRecordingEngine"
private const val SEGMENT_SECONDS = 20
private const val VIDEO_WIDTH = 1280
private const val VIDEO_HEIGHT = 720
private const val VIDEO_BIT_RATE = 4_000_000
private const val VIDEO_FRAME_RATE = 30
private const val VIDEO_I_FRAME_INTERVAL = 2 // seconds

// Raw scratch file record layout, one per encoded frame, written back-to-back:
// [8 bytes presentationTimeUs][1 byte isKeyFrame][4 bytes size][size bytes payload]
// Visible to StreamingEngine (same module, same package), which tails this
// file and must parse the exact same layout.
internal const val VIDEO_RECORD_HEADER_SIZE = 8 + 1 + 4

/**
 * Records video via a raw Camera2 + [MediaCodec] pipeline (not CameraX's
 * high-level `Recorder`), so the encoded H.264 bitstream — and its SPS/PPS —
 * are available the instant the encoder starts producing output, not only
 * once a finished segment file exists.
 *
 * The camera's only capture target is [CameraGlRenderer]'s own
 * camera-facing `SurfaceTexture` — not the live preview or the encoder
 * directly. [CameraGlRenderer] then renders each frame, via OpenGL, to both
 * the preview surface and the encoder's input surface, applying
 * `SurfaceTexture.getTransformMatrix()` explicitly for both. A raw Camera2
 * target surface (like the encoder's) gets no orientation/mirror correction
 * on its own — only a `TextureView` applies that automatically, as part of
 * its own rendering — so without this GL step the encoded video would come
 * out flipped relative to what the on-screen preview shows. CameraX's
 * `Preview` use case isn't used here since it only supports a single output
 * and doesn't expose a way to tap the raw encoded bitstream either.
 *
 * Every encoded frame is written to three places: a rotating [MediaMuxer]
 * producing the local 20s MP4 segments for the Recordings tab (rotation
 * happens inline on the drain thread, not a separate coroutine, so muxer
 * access is never touched from two threads at once); and [currentVideoFile],
 * one continuously-growing raw scratch file for the whole session (see
 * [VIDEO_RECORD_HEADER_SIZE]) that [com.example.sdtech_android_native.data.StreamingEngine]
 * tails byte-by-byte to push live, the same way it already tails
 * [AudioRecordingEngine]'s PCM file — so a dropped connection just delays the
 * stream instead of losing frames, with no in-memory frame queue/cap needed.
 */
class VideoRecordingEngine(private val context: Context) {

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _isStarting = MutableStateFlow(false)
    val isStarting: StateFlow<Boolean> = _isStarting.asStateFlow()

    private val _secondsRemaining = MutableStateFlow(SEGMENT_SECONDS)
    val secondsRemaining: StateFlow<Int> = _secondsRemaining.asStateFlow()

    private val _savedVideoFiles = MutableStateFlow<List<SavedMediaItem>>(emptyList())
    val savedVideoFiles: StateFlow<List<SavedMediaItem>> = _savedVideoFiles.asStateFlow()

    val storagePath: String get() = context.filesDir.path

    /** SPS/PPS, available the moment the encoder starts producing output — well before any segment file finishes. */
    private val _videoFormat = MutableStateFlow<MediaFormat?>(null)
    val videoFormat: StateFlow<MediaFormat?> = _videoFormat.asStateFlow()

    /** The current session's raw video scratch file — see the class kdoc. Null until [startCapturing] creates it. */
    var currentVideoFile: File? = null

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var codec: MediaCodec? = null
    private var drainThread: Thread? = null
    private val shouldDrain = AtomicBoolean(false)

    // Owns the EGL context that corrects orientation/mirroring before both
    // the preview and the encoder ever see a frame — see CameraGlRenderer's
    // kdoc. All GL/EGL calls for glRenderer happen on this thread.
    private var glThread: HandlerThread? = null
    private var glHandler: Handler? = null
    private var glRenderer: CameraGlRenderer? = null

    // Only ever touched from the drain thread — see kdoc above.
    private var muxer: MediaMuxer? = null
    private var muxerTrackIndex = -1
    private var muxerStarted = false
    private var currentSegmentFile: File? = null

    init {
        loadSavedVideoFiles()
    }

    fun loadSavedVideoFiles() {
        try {
            val dir = context.filesDir
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

    @SuppressLint("MissingPermission") // caller verifies CAMERA before calling
    fun startCapturing(previewSurfaceTexture: SurfaceTexture) {
        if (_isRecording.value || _isStarting.value) return
        _isStarting.value = true

        try {
            previewSurfaceTexture.setDefaultBufferSize(VIDEO_WIDTH, VIDEO_HEIGHT)
            val previewSurface = Surface(previewSurfaceTexture)

            val encoder = createEncoder()
            codec = encoder
            val encoderSurface = encoder.createInputSurface()
            encoder.start()

            startNewSegment()

            val scratchFile = generateVideoScratchFile()
            currentVideoFile = scratchFile

            shouldDrain.set(true)
            drainThread = Thread({ runDrainLoop(encoder, scratchFile) }, "VideoDrainThread").apply { start() }

            val cameraSurface = startGlRenderer(previewSurface, encoderSurface)

            cameraThread = HandlerThread("VideoCameraThread").apply { start() }
            cameraHandler = Handler(cameraThread!!.looper)

            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.first()

            manager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        openCaptureSession(camera, cameraSurface)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        cameraDevice = null
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        Log.e(TAG, "Camera error: $error")
                        camera.close()
                        cameraDevice = null
                        _isStarting.value = false
                    }
                },
                cameraHandler,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error starting video recording", e)
            stopCapturing()
        }
    }

    /**
     * Spins up [glThread]/[glRenderer] and returns the camera-facing
     * [Surface] the camera should actually target — see [CameraGlRenderer]'s
     * kdoc. Synchronous (blocks up to 2s) because the caller needs that
     * surface before it can open the camera.
     */
    private fun startGlRenderer(previewSurface: Surface, encoderSurface: Surface): Surface {
        val thread = HandlerThread("VideoGlThread").apply { start() }
        glThread = thread
        val handler = Handler(thread.looper)
        glHandler = handler

        val latch = CountDownLatch(1)
        var initError: Exception? = null
        var cameraSurface: Surface? = null
        handler.post {
            try {
                val renderer = CameraGlRenderer(previewSurface, encoderSurface, VIDEO_WIDTH, VIDEO_HEIGHT)
                glRenderer = renderer
                renderer.cameraSurfaceTexture.setOnFrameAvailableListener({ renderer.drawFrame() }, handler)
                cameraSurface = renderer.cameraInputSurface
            } catch (e: Exception) {
                initError = e
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(2, TimeUnit.SECONDS)) throw IllegalStateException("Timed out initializing GL renderer")
        initError?.let { throw it }
        return cameraSurface ?: throw IllegalStateException("GL renderer did not produce a camera surface")
    }

    private fun openCaptureSession(camera: CameraDevice, cameraSurface: Surface) {
        try {
            camera.createCaptureSession(
                listOf(cameraSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(cameraSurface)
                        }
                        session.setRepeatingRequest(request.build(), null, cameraHandler)
                        _isRecording.value = true
                        _isStarting.value = false
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Camera capture session configuration failed")
                        _isStarting.value = false
                    }
                },
                cameraHandler,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error creating capture session", e)
            _isStarting.value = false
        }
    }

    fun stopCapturing() {
        try {
            captureSession?.stopRepeating()
        } catch (_: Exception) {
        }
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null

        shouldDrain.set(false)
        drainThread?.join(2_000)
        drainThread = null

        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        try {
            codec?.release()
        } catch (_: Exception) {
        }
        codec = null

        stopGlRenderer()

        finishCurrentSegment()

        _isRecording.value = false
        _isStarting.value = false
        _secondsRemaining.value = SEGMENT_SECONDS
        _videoFormat.value = null
    }

    /**
     * Discards [currentVideoFile] — call once nothing else needs to read it
     * (i.e. once [com.example.sdtech_android_native.data.StreamingEngine] has
     * confirmed it finished draining it; see
     * [AudioRecordingEngine.finalizeRecording], which this mirrors). It's
     * purely a relay for live streaming, not a saved artifact — the muxer
     * segments are the actual Recordings-tab output — so there's nothing to
     * register here, just cleanup.
     */
    fun finalizeRecording() {
        currentVideoFile?.delete()
        currentVideoFile = null
    }

    /** Releases [glRenderer] on [glThread] itself — EGL/GL calls must happen on the thread that owns the context — then tears the thread down. */
    private fun stopGlRenderer() {
        val handler = glHandler
        if (handler != null) {
            val latch = CountDownLatch(1)
            handler.post {
                try {
                    glRenderer?.release()
                } catch (e: Exception) {
                    Log.e(TAG, "Error releasing GL renderer", e)
                } finally {
                    glRenderer = null
                    latch.countDown()
                }
            }
            latch.await(2, TimeUnit.SECONDS)
        }
        glThread?.quitSafely()
        glThread = null
        glHandler = null
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

    private fun createEncoder(): MediaCodec {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, VIDEO_WIDTH, VIDEO_HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VIDEO_I_FRAME_INTERVAL)
            // Requests the encoder not reorder frames (no B-frames) — needed so
            // decode order matches presentation order on the consuming side
            // (e.g. feeding frames to a neural net in order as they arrive),
            // not just for low latency.
            setInteger(MediaFormat.KEY_LATENCY, 1)
        }
        return MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
    }

    /**
     * Runs on [drainThread]: pulls encoded output from the encoder, writes it
     * to [scratchFile] for [StreamingEngine] to tail live, writes it to the
     * current segment's muxer, and rotates segments — all single-threaded, so
     * muxer access is never raced.
     */
    private fun runDrainLoop(encoder: MediaCodec, scratchFile: File) {
        val bufferInfo = MediaCodec.BufferInfo()
        var segmentStartUs = -1L

        try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(scratchFile))).use { rawOut ->
                while (shouldDrain.get()) {
                    val outputIndex = try {
                        encoder.dequeueOutputBuffer(bufferInfo, 10_000)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error dequeuing encoder output — stopping capture", e)
                        failCapture()
                        break
                    }

                    when {
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            _videoFormat.value = encoder.outputFormat
                            addMuxerTrackIfReady()
                        }

                        outputIndex >= 0 -> {
                            if (bufferInfo.size > 0 && bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                val outputBuffer = encoder.getOutputBuffer(outputIndex)
                                if (outputBuffer != null) {
                                    if (segmentStartUs < 0) segmentStartUs = bufferInfo.presentationTimeUs

                                    val isKeyFrame = bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                                    outputBuffer.position(bufferInfo.offset)
                                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                    val frameBytes = ByteArray(bufferInfo.size)
                                    outputBuffer.get(frameBytes)

                                    writeRawFrame(rawOut, frameBytes, bufferInfo.presentationTimeUs, isKeyFrame)

                                    outputBuffer.position(bufferInfo.offset)
                                    writeToMuxer(outputBuffer, bufferInfo)

                                    val elapsedUs = bufferInfo.presentationTimeUs - segmentStartUs
                                    _secondsRemaining.value = (SEGMENT_SECONDS - elapsedUs / 1_000_000).toInt().coerceAtLeast(0)
                                    // Only cut a new segment on a keyframe boundary, so every
                                    // segment file is independently decodable from its first sample.
                                    if (elapsedUs >= SEGMENT_SECONDS * 1_000_000L && isKeyFrame) {
                                        rotateSegment()
                                        segmentStartUs = bufferInfo.presentationTimeUs
                                        _secondsRemaining.value = SEGMENT_SECONDS
                                    }
                                }
                            }
                            encoder.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing video scratch file", e)
        }
    }

    /** Appends one [VIDEO_RECORD_HEADER_SIZE]-prefixed record to the scratch file — flushed immediately so [StreamingEngine]'s concurrent tailing reader sees it promptly. */
    private fun writeRawFrame(out: DataOutputStream, frameBytes: ByteArray, presentationTimeUs: Long, isKeyFrame: Boolean) {
        out.writeLong(presentationTimeUs)
        out.writeBoolean(isKeyFrame)
        out.writeInt(frameBytes.size)
        out.write(frameBytes)
        out.flush()
    }

    /**
     * Called from [drainThread] itself when the encoder output queue dies
     * unexpectedly. Without this, the drain thread would just exit while the
     * camera session, encoder, and [CameraGlRenderer] stay alive — the
     * encoder's now-undrained input surface fills up, which stalls
     * [CameraGlRenderer.drawFrame]'s `eglSwapBuffers` to it, which in turn
     * stops it calling `updateTexImage()` on the camera-facing
     * `SurfaceTexture` — and that backs up all the way to the camera's own
     * capture request, freezing the live preview too, with no way to recover
     * short of restarting the app. Runs [stopCapturing] on a fresh thread
     * since it joins [drainThread], which would deadlock if called from the
     * drain thread itself.
     */
    private fun failCapture() {
        if (!shouldDrain.compareAndSet(true, false)) return
        Thread({ stopCapturing() }, "VideoCaptureFailureThread").start()
    }

    private fun startNewSegment() {
        val file = generateVideoFile()
        currentSegmentFile = file
        muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxerStarted = false
        muxerTrackIndex = -1
        addMuxerTrackIfReady()
    }

    private fun addMuxerTrackIfReady() {
        val format = _videoFormat.value ?: return
        val m = muxer ?: return
        if (muxerStarted) return
        muxerTrackIndex = m.addTrack(format)
        m.start()
        muxerStarted = true
    }

    private fun writeToMuxer(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        val m = muxer ?: return
        if (!muxerStarted) return
        try {
            m.writeSampleData(muxerTrackIndex, buffer, info)
        } catch (e: Exception) {
            Log.e(TAG, "Error writing sample to muxer", e)
        }
    }

    private fun rotateSegment() {
        finishCurrentSegment()
        startNewSegment()
    }

    private fun finishCurrentSegment() {
        val file = currentSegmentFile
        currentSegmentFile = null
        try {
            if (muxerStarted) muxer?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping muxer", e)
        }
        muxer?.release()
        muxer = null
        muxerStarted = false
        if (file != null) registerSavedVideo(file)
    }

    private fun registerSavedVideo(file: File) {
        if (!file.exists() || file.length() == 0L) {
            file.delete()
            return
        }
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
        return File(context.filesDir, "video_$timestamp.mp4")
    }

    private fun generateVideoScratchFile(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(context.filesDir, "video_scratch_$timestamp.raw")
    }
}
