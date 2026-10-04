package com.example.sdtech_android_native.asr

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decodes a recorded .m4a (AAC) file into mono 16kHz float PCM in [-1, 1], as required by [MelSpectrogramExtractor]. */
object PcmAudioDecoder {

    fun decodeToMono16k(filePath: String): FloatArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(filePath)
        try {
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                val mime = candidate.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = candidate
                    break
                }
            }
            require(trackIndex >= 0 && format != null) { "No audio track found in $filePath" }
            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val sourceSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            try {
                val pcmBytes = runDecodeLoop(extractor, codec)
                val mono = toMonoFloat(pcmBytes, channelCount)
                return if (sourceSampleRate == AsrConfig.SAMPLE_RATE) {
                    mono
                } else {
                    resampleLinear(mono, sourceSampleRate, AsrConfig.SAMPLE_RATE)
                }
            } finally {
                codec.stop()
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun runDecodeLoop(extractor: MediaExtractor, codec: MediaCodec): ByteArray {
        val output = ByteArrayOutputStream()
        val bufferInfo = MediaCodec.BufferInfo()
        var sawInputEos = false
        var sawOutputEos = false

        while (!sawOutputEos) {
            if (!sawInputEos) {
                val inputIndex = codec.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)!!
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEos = true
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            var outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            while (outputIndex >= 0) {
                if (bufferInfo.size > 0) {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)!!
                    val chunk = ByteArray(bufferInfo.size)
                    outputBuffer.position(bufferInfo.offset)
                    outputBuffer.get(chunk)
                    output.write(chunk)
                }
                val isEos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                codec.releaseOutputBuffer(outputIndex, false)
                if (isEos) {
                    sawOutputEos = true
                    break
                }
                outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            }
        }
        return output.toByteArray()
    }

    private fun toMonoFloat(pcmBytes: ByteArray, channelCount: Int): FloatArray {
        val shortBuffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = ShortArray(shortBuffer.remaining())
        shortBuffer.get(samples)

        val totalFrames = samples.size / channelCount
        val mono = FloatArray(totalFrames)
        for (i in 0 until totalFrames) {
            var sum = 0
            for (c in 0 until channelCount) sum += samples[i * channelCount + c]
            mono[i] = (sum / channelCount) / 32768f
        }
        return mono
    }

    private fun resampleLinear(input: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        if (input.isEmpty() || srcRate == dstRate) return input
        val ratio = dstRate.toDouble() / srcRate.toDouble()
        val outLength = (input.size * ratio).toInt()
        val output = FloatArray(outLength)
        for (i in output.indices) {
            val srcPos = i / ratio
            val idx0 = srcPos.toInt().coerceIn(0, input.size - 1)
            val idx1 = (idx0 + 1).coerceAtMost(input.size - 1)
            val frac = (srcPos - idx0).toFloat()
            output[i] = input[idx0] * (1 - frac) + input[idx1] * frac
        }
        return output
    }
}
