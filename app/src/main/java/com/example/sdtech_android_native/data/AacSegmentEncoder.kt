package com.example.sdtech_android_native.data

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

/**
 * Encodes a single contiguous run of 16-bit PCM into one AAC/.m4a file via
 * [MediaCodec] + [MediaMuxer]. A fresh instance is created per output segment,
 * but the microphone itself ([android.media.AudioRecord] in
 * [AudioRecorderViewModel]) is never stopped between instances — only the
 * encode/mux destination changes — so segment boundaries introduce no gap in
 * the captured audio.
 */
class AacSegmentEncoder(
    private val outputFile: File,
    private val sampleRate: Int,
    private val channelCount: Int,
    bitRate: Int = 128000,
) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val bufferInfo = MediaCodec.BufferInfo()

    private var trackIndex = -1
    private var muxerStarted = false
    private var totalSamplesFed = 0L

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            // Must be >= the largest raw PCM chunk ever passed to encode() — the
            // codec's own default assumes compressed-output-sized buffers and is
            // too small for a full AudioRecord read, which silently throws
            // BufferOverflowException on the very first feed otherwise.
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    /** Feeds one chunk of raw PCM (first [length] bytes of [pcm]) into the encoder. */
    fun encode(pcm: ByteArray, length: Int) {
        feedInput(pcm, length, endOfStream = false)
        drainOutput(waitForEndOfStream = false)
    }

    /** Signals end of input, flushes remaining encoded output, and closes the file. */
    fun finish(): File {
        feedInput(ByteArray(0), 0, endOfStream = true)
        drainOutput(waitForEndOfStream = true)
        try {
            muxer.stop()
        } catch (_: Exception) {
        }
        muxer.release()
        codec.stop()
        codec.release()
        return outputFile
    }

    private fun feedInput(pcm: ByteArray, length: Int, endOfStream: Boolean) {
        var inputIndex = -1
        for (attempt in 0 until 10) {
            inputIndex = codec.dequeueInputBuffer(10_000)
            if (inputIndex >= 0) break
        }
        if (inputIndex < 0) return

        val inputBuffer = codec.getInputBuffer(inputIndex) ?: return
        inputBuffer.clear()
        if (length > 0) inputBuffer.put(pcm, 0, length)

        val ptsUs = totalSamplesFed * 1_000_000L / sampleRate
        val flags = if (endOfStream) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
        codec.queueInputBuffer(inputIndex, 0, length, ptsUs, flags)
        if (length > 0) totalSamplesFed += length / (2 * channelCount)
    }

    private fun drainOutput(waitForEndOfStream: Boolean) {
        var attempts = 0
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }

                outputIndex >= 0 -> {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && bufferInfo.size > 0 && muxerStarted) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }

                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    attempts++
                    if (!waitForEndOfStream || attempts > 50) return
                }

                else -> return
            }
        }
    }
}
