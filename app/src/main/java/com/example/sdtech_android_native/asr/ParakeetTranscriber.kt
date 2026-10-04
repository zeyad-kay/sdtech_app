package com.example.sdtech_android_native.asr

import android.content.Context
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Loads the bundled Parakeet TDT LiteRT model + tokenizer once (lazily, on
 * first use) and transcribes 16kHz mono PCM audio with it. The model runs on
 * GPU where the delegate can compile it, falling back to CPU per-op
 * automatically (both accelerators are offered to [CompiledModel]).
 */
object ParakeetTranscriber {
    private val loadMutex = Mutex()
    private var model: CompiledModel? = null
    private var tokenizer: ParakeetTokenizer? = null

    /** Loads the model + tokenizer ahead of time, so the first [transcribeFile] call doesn't pay the load cost. */
    suspend fun preload(context: Context) {
        ensureLoaded(context)
    }

    /** Decodes [filePath] (an AAC/.m4a recording) and returns its transcript. */
    suspend fun transcribeFile(context: Context, filePath: String): String {
        val pcm16k = PcmAudioDecoder.decodeToMono16k(filePath)
        return transcribePcm(context, pcm16k)
    }

    suspend fun transcribePcm(context: Context, pcm16k: FloatArray): String {
        ensureLoaded(context)
        val compiledModel = checkNotNull(model)
        val tok = checkNotNull(tokenizer)

        val decoder = TdtGreedyDecoder(compiledModel)
        val extractor = MelSpectrogramExtractor()
        val encodeInputBuffers = compiledModel.createInputBuffers(AsrConfig.ENCODE_SIGNATURE)
        val encodeOutputBuffers = compiledModel.createOutputBuffers(AsrConfig.ENCODE_SIGNATURE)

        val parts = mutableListOf<String>()
        var offset = 0
        while (offset < pcm16k.size) {
            val validCount = minOf(pcm16k.size - offset, AsrConfig.SAMPLES_PER_CHUNK)
            val chunk = FloatArray(AsrConfig.SAMPLES_PER_CHUNK)
            System.arraycopy(pcm16k, offset, chunk, 0, validCount)

            val features = extractor.extract(chunk, validCount)
            encodeInputBuffers[0].writeFloat(features)
            compiledModel.run(encodeInputBuffers, encodeOutputBuffers, AsrConfig.ENCODE_SIGNATURE)

            val tokenIds = decoder.decode(encodeOutputBuffers)
            val text = tok.decode(tokenIds).trim()
            if (text.isNotEmpty()) parts.add(text)

            offset += AsrConfig.SAMPLES_PER_CHUNK
        }
        return parts.joinToString(" ")
    }

    private suspend fun ensureLoaded(context: Context) {
        if (model != null) return
        loadMutex.withLock {
            if (model != null) return
            val appContext = context.applicationContext
            val options = CompiledModel.Options(setOf(Accelerator.CPU, Accelerator.GPU))
            options.gpuOptions = CompiledModel.GpuOptions(
                precision = CompiledModel.GpuOptions.Precision.DEFAULT,
            )
            val env = Environment.create()
            model = CompiledModel.create(appContext.assets, AsrConfig.MODEL_ASSET_PATH, options, env)
            tokenizer = ParakeetTokenizer.loadFromAssets(appContext, AsrConfig.TOKENIZER_ASSET_PATH)
        }
    }
}
