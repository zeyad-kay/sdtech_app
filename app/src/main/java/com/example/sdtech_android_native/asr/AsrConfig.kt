package com.example.sdtech_android_native.asr

/** Constants matching the nvidia/parakeet-tdt-0.6b-v3 conversion config used by litert-community. */
object AsrConfig {
    const val SAMPLE_RATE = 16000
    const val N_FFT = 512
    const val WIN_LENGTH = 400 // 25ms @ 16kHz
    const val HOP_LENGTH = 160 // 10ms @ 16kHz
    const val N_MELS = 128
    const val PREEMPHASIS = 0.97f

    const val CHUNK_SECONDS = 5
    val FRAMES_PER_CHUNK = SAMPLE_RATE * CHUNK_SECONDS / HOP_LENGTH // 500
    val SAMPLES_PER_CHUNK = SAMPLE_RATE * CHUNK_SECONDS // 80000

    const val BLANK_TOKEN_ID = 8192
    const val NUM_DURATIONS = 5
    const val ENCODER_HIDDEN_DIM = 1024

    const val MODEL_ASSET_PATH = "parakeet_tdt/model.tflite"
    const val TOKENIZER_ASSET_PATH = "parakeet_tdt/tokenizer.json"

    const val ENCODE_SIGNATURE = "encode"
    const val DECODE_SIGNATURE = "decode"
    const val DECODE_1_SIGNATURE = "decode_1"
}
