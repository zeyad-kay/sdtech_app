package com.example.sdtech_android_native.asr

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Log-mel spectrogram front end matching Parakeet TDT's expected input: 128
 * mel bins, 512-point FFT, 25ms/10ms win/hop at 16kHz, pre-emphasis 0.97,
 * per-mel-bin mean/std normalization over the chunk's real (non-padded)
 * audio, fixed-length output per chunk, mel-major layout — mirroring NeMo's
 * AudioToMelSpectrogramPreprocessor / librosa defaults (Slaney mel scale,
 * centered/reflect-padded STFT).
 */
class MelSpectrogramExtractor(
    private val sampleRate: Int = AsrConfig.SAMPLE_RATE,
    private val nFft: Int = AsrConfig.N_FFT,
    private val winLength: Int = AsrConfig.WIN_LENGTH,
    private val hopLength: Int = AsrConfig.HOP_LENGTH,
    private val nMels: Int = AsrConfig.N_MELS,
    private val nFrames: Int = AsrConfig.FRAMES_PER_CHUNK,
    private val preemphasis: Float = AsrConfig.PREEMPHASIS,
) {
    private val window = hannWindow(winLength)
    private val melFilterbank = buildMelFilterbank(sampleRate, nFft, nMels)

    /**
     * [samples] must be exactly [AsrConfig.SAMPLES_PER_CHUNK] long (zero-padded
     * at the tail if needed); [validSampleCount] is how many of those are real
     * (non-padding) audio samples, used to compute normalization statistics.
     * Returns a [nMels] x [nFrames] log-mel spectrogram flattened mel-major
     * (index = mel * nFrames + frame).
     */
    fun extract(samples: FloatArray, validSampleCount: Int): FloatArray {
        val emphasized = applyPreemphasis(samples, preemphasis)
        val padded = reflectPad(emphasized, nFft / 2)

        val melSpec = Array(nMels) { FloatArray(nFrames) }
        val frameBuffer = FloatArray(nFft)
        val re = FloatArray(nFft)
        val im = FloatArray(nFft)
        val power = FloatArray(nFft / 2 + 1)

        for (f in 0 until nFrames) {
            val start = f * hopLength
            frameBuffer.fill(0f)
            for (i in 0 until winLength) {
                val sampleIndex = start + i
                frameBuffer[i] = if (sampleIndex < padded.size) padded[sampleIndex] * window[i] else 0f
            }
            System.arraycopy(frameBuffer, 0, re, 0, nFft)
            im.fill(0f)
            fft(re, im)
            for (k in power.indices) {
                power[k] = re[k] * re[k] + im[k] * im[k]
            }
            for (m in 0 until nMels) {
                var sum = 0f
                val weights = melFilterbank[m]
                for (k in weights.indices) sum += weights[k] * power[k]
                melSpec[m][f] = ln(sum + LOG_ZERO_GUARD)
            }
        }

        val validFrames = (validSampleCount / hopLength).coerceIn(1, nFrames)
        val output = FloatArray(nMels * nFrames)
        for (m in 0 until nMels) {
            var mean = 0f
            for (f in 0 until validFrames) mean += melSpec[m][f]
            mean /= validFrames
            var variance = 0f
            for (f in 0 until validFrames) {
                val d = melSpec[m][f] - mean
                variance += d * d
            }
            val std = sqrt(variance / validFrames)
            val denom = std + 1e-5f
            for (f in 0 until nFrames) {
                output[m * nFrames + f] = (melSpec[m][f] - mean) / denom
            }
        }
        return output
    }

    private fun applyPreemphasis(samples: FloatArray, coeff: Float): FloatArray {
        if (coeff <= 0f) return samples
        val result = FloatArray(samples.size)
        result[0] = samples[0]
        for (i in 1 until samples.size) {
            result[i] = samples[i] - coeff * samples[i - 1]
        }
        return result
    }

    private fun reflectPad(samples: FloatArray, padLength: Int): FloatArray {
        val result = FloatArray(samples.size + 2 * padLength)
        for (i in 0 until padLength) {
            result[i] = samples[(padLength - i).coerceIn(0, samples.size - 1)]
        }
        System.arraycopy(samples, 0, result, padLength, samples.size)
        for (i in 0 until padLength) {
            val srcIndex = samples.size - 2 - i
            result[padLength + samples.size + i] = samples[srcIndex.coerceIn(0, samples.size - 1)]
        }
        return result
    }

    private companion object {
        const val LOG_ZERO_GUARD = 1.1920929e-7f // 2^-23

        fun hannWindow(length: Int): FloatArray = FloatArray(length) { i ->
            (0.5 - 0.5 * cos(2.0 * PI * i / (length - 1))).toFloat()
        }

        /** Radix-2 iterative Cooley-Tukey FFT, in place on [re]/[im] (length must be a power of two). */
        fun fft(re: FloatArray, im: FloatArray) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) {
                    j = j xor bit
                    bit = bit shr 1
                }
                j = j or bit
                if (i < j) {
                    var tmp = re[i]; re[i] = re[j]; re[j] = tmp
                    tmp = im[i]; im[i] = im[j]; im[j] = tmp
                }
            }
            var len = 2
            while (len <= n) {
                val ang = -2.0 * PI / len
                val wReal = cos(ang).toFloat()
                val wImag = sin(ang).toFloat()
                var i = 0
                while (i < n) {
                    var curReal = 1f
                    var curImag = 0f
                    for (k in 0 until len / 2) {
                        val aIdx = i + k
                        val bIdx = aIdx + len / 2
                        val vRe = re[bIdx] * curReal - im[bIdx] * curImag
                        val vIm = re[bIdx] * curImag + im[bIdx] * curReal
                        val uRe = re[aIdx]
                        val uIm = im[aIdx]
                        re[aIdx] = uRe + vRe
                        im[aIdx] = uIm + vIm
                        re[bIdx] = uRe - vRe
                        im[bIdx] = uIm - vIm
                        val nextReal = curReal * wReal - curImag * wImag
                        val nextImag = curReal * wImag + curImag * wReal
                        curReal = nextReal
                        curImag = nextImag
                    }
                    i += len
                }
                len = len shl 1
            }
        }

        /** Librosa-compatible Slaney-scale triangular mel filterbank, area-normalized. */
        fun buildMelFilterbank(sampleRate: Int, nFft: Int, nMels: Int): Array<FloatArray> {
            fun hzToMel(hz: Double): Double {
                val minLogHz = 1000.0
                val minLogMel = 15.0
                val logstep = ln(6.4) / 27.0
                return if (hz < minLogHz) hz / (200.0 / 3.0) else minLogMel + ln(hz / minLogHz) / logstep
            }
            fun melToHz(mel: Double): Double {
                val minLogHz = 1000.0
                val minLogMel = 15.0
                val logstep = ln(6.4) / 27.0
                return if (mel < minLogMel) mel * (200.0 / 3.0) else minLogHz * exp(logstep * (mel - minLogMel))
            }

            val nFreqBins = nFft / 2 + 1
            val fftFreqs = DoubleArray(nFreqBins) { it * sampleRate.toDouble() / nFft }

            val melMin = hzToMel(0.0)
            val melMax = hzToMel(sampleRate / 2.0)
            val hzPoints = DoubleArray(nMels + 2) { melToHz(melMin + it * (melMax - melMin) / (nMels + 1)) }

            val weights = Array(nMels) { FloatArray(nFreqBins) }
            for (m in 0 until nMels) {
                val lower = hzPoints[m]
                val center = hzPoints[m + 1]
                val upper = hzPoints[m + 2]
                val fdiffLower = center - lower
                val fdiffUpper = upper - center
                val enorm = 2.0 / (upper - lower)
                for (k in 0 until nFreqBins) {
                    val freq = fftFreqs[k]
                    val lowerSlope = (freq - lower) / fdiffLower
                    val upperSlope = (upper - freq) / fdiffUpper
                    val w = maxOf(0.0, minOf(lowerSlope, upperSlope))
                    weights[m][k] = (w * enorm).toFloat()
                }
            }
            return weights
        }
    }
}
