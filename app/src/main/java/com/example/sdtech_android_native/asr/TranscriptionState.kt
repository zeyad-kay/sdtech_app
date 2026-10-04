package com.example.sdtech_android_native.asr

sealed class TranscriptionState {
    data object InProgress : TranscriptionState()
    data class Done(val text: String) : TranscriptionState()
    data class Error(val message: String) : TranscriptionState()
}
