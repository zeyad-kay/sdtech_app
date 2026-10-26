package com.example.sdtech_android_native.pairing

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "WifiConnector"
private const val CONNECT_TIMEOUT_MS = 30_000
private const val RECONNECT_INTERVAL_MS = 60_000L

/**
 * Connects this app's traffic to a specific WiFi network by SSID/passphrase
 * using [WifiNetworkSpecifier] (Android 10+): the request is scoped to this
 * app and torn down when [release] is called, rather than changing the
 * device's saved/active network globally.
 *
 * Once connected, keeps watching the network: if it's lost, it reports
 * [WifiConnectionState.Disconnected] and retries immediately, then every
 * [RECONNECT_INTERVAL_MS] until it reconnects or [release] is called.
 */
class WifiConnector(context: Context, private val scope: CoroutineScope) {

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var retryJob: Job? = null

    private var ssid: String? = null
    private var passphrase: String? = null
    private var onStateChange: ((WifiConnectionState) -> Unit)? = null

    /** Starts connecting to [ssid]/[passphrase], reporting every state change via [onStateChange] (called repeatedly, not just once). */
    fun connect(ssid: String, passphrase: String, onStateChange: (WifiConnectionState) -> Unit) {
        this.ssid = ssid
        this.passphrase = passphrase
        this.onStateChange = onStateChange
        retryJob?.cancel()
        retryJob = null
        attemptConnect()
    }

    private fun attemptConnect() {
        val ssid = ssid ?: return
        val passphrase = passphrase.orEmpty()
        unregisterCallback()

        onStateChange?.invoke(WifiConnectionState.Connecting)

        val specifierBuilder = WifiNetworkSpecifier.Builder().setSsid(ssid)
        if (passphrase.isNotEmpty()) specifierBuilder.setWpa2Passphrase(passphrase)
        val specifier = specifierBuilder.build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        var delivered = false
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (delivered) return
                delivered = true
                retryJob?.cancel()
                retryJob = null
                connectivityManager.bindProcessToNetwork(network)
                onStateChange?.invoke(WifiConnectionState.Connected(network))
            }

            override fun onLost(network: Network) {
                if (!delivered) return
                delivered = false
                onStateChange?.invoke(WifiConnectionState.Disconnected("Connection to \"$ssid\" was lost"))
                scheduleRetry(immediate = true)
            }

            override fun onUnavailable() {
                if (delivered) return
                delivered = true
                onStateChange?.invoke(WifiConnectionState.Disconnected("Could not connect to \"$ssid\""))
                scheduleRetry(immediate = false)
            }
        }

        networkCallback = callback
        try {
            connectivityManager.requestNetwork(request, callback, CONNECT_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting network for $ssid", e)
            onStateChange?.invoke(WifiConnectionState.Disconnected(e.message ?: "Failed to connect"))
            scheduleRetry(immediate = false)
        }
    }

    private fun scheduleRetry(immediate: Boolean) {
        retryJob?.cancel()
        retryJob = scope.launch {
            if (!immediate) delay(RECONNECT_INTERVAL_MS)
            attemptConnect()
        }
    }

    private fun unregisterCallback() {
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (_: IllegalArgumentException) {
                // Already unregistered (e.g. request already timed out).
            }
        }
        networkCallback = null
    }

    /** Stops any in-flight connection, reconnect retries, and active network callback. */
    fun release() {
        retryJob?.cancel()
        retryJob = null
        onStateChange = null
        unregisterCallback()
    }
}
