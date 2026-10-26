package com.example.sdtech_android_native.pairing

import android.net.Network

sealed interface WifiConnectionState {
    data object Idle : WifiConnectionState
    data object Connecting : WifiConnectionState
    data class Connected(val network: Network) : WifiConnectionState
    data class Disconnected(val message: String) : WifiConnectionState
}
