package com.example.sdtech_android_native.pairing

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress

private const val TAG = "WifiConnector"
private const val CONNECT_TIMEOUT_MS = 30_000
private const val RECONNECT_INTERVAL_MS = 10_000L

// The paired device's session server port (see SessionClient) — used only as
// a reachability probe here, not to actually talk to it.
private const val LIVENESS_PROBE_PORT = 8000
private const val LIVENESS_CHECK_INTERVAL_MS = 8_000L
private const val LIVENESS_CHECK_TIMEOUT_MS = 3_000

/**
 * Connects this app's traffic to a specific WiFi network by SSID/passphrase
 * using [WifiNetworkSpecifier] (Android 10+): the request is scoped to this
 * app and torn down when [release] is called, rather than changing the
 * device's saved/active network globally.
 *
 * Once connected, keeps watching the network two ways: passively via
 * [ConnectivityManager.NetworkCallback.onLost], and actively via a periodic
 * TCP-connect probe to the paired device's session port ([runLivenessChecks]).
 * The passive callback alone isn't enough here — this request deliberately
 * removes [NetworkCapabilities.NET_CAPABILITY_INTERNET] (the hotspot has no
 * internet access), and Android only runs its own background reachability
 * probing on networks that request internet capability. Without the active
 * check, a link that's still associated at the radio level but has otherwise
 * gone dead (hotspot software hung, device out of range without a clean
 * deauth) would never fire `onLost()`, leaving the UI stuck reporting
 * [WifiConnectionState.Connected] indefinitely.
 *
 * Either path reports [WifiConnectionState.Disconnected] and retries
 * immediately, then every [RECONNECT_INTERVAL_MS] until it reconnects or
 * [release] is called.
 */
class WifiConnector(context: Context, private val scope: CoroutineScope) {

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var retryJob: Job? = null
    private var livenessJob: Job? = null

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
        livenessJob?.cancel()
        livenessJob = null

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

        // Shared by the passive callback and the active liveness probe below
        // — whichever notices first wins; the other becomes a no-op via
        // `delivered`.
        fun reportLost(message: String) {
            if (!delivered) return
            delivered = false
            livenessJob?.cancel()
            livenessJob = null
            onStateChange?.invoke(WifiConnectionState.Disconnected(message))
            scheduleRetry(immediate = true)
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (delivered) return
                delivered = true
                retryJob?.cancel()
                retryJob = null
                connectivityManager.bindProcessToNetwork(network)
                onStateChange?.invoke(WifiConnectionState.Connected(network))
                livenessJob = scope.launch {
                    runLivenessChecks(network) { reportLost("Connection to \"$ssid\" appears unreachable") }
                }
            }

            override fun onLost(network: Network) {
                reportLost("Connection to \"$ssid\" was lost")
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

    /** Periodically TCP-connects to [network]'s gateway on [LIVENESS_PROBE_PORT] — see the class kdoc for why this exists. Calls [onUnreachable] once, on the first failed probe, and stops. */
    private suspend fun runLivenessChecks(network: Network, onUnreachable: () -> Unit) {
        while (true) {
            delay(LIVENESS_CHECK_INTERVAL_MS)
            val gatewayIp = gatewayIpOrNull(network)
            if (gatewayIp == null || !isReachable(network, gatewayIp)) {
                onUnreachable()
                return
            }
        }
    }

    private fun gatewayIpOrNull(network: Network): String? =
        connectivityManager.getLinkProperties(network)
            ?.routes
            ?.firstOrNull { it.isDefaultRoute }
            ?.gateway
            ?.hostAddress

    private suspend fun isReachable(network: Network, ip: String): Boolean = withContext(Dispatchers.IO) {
        try {
            network.socketFactory.createSocket().use { socket ->
                socket.connect(InetSocketAddress(ip, LIVENESS_PROBE_PORT), LIVENESS_CHECK_TIMEOUT_MS)
            }
            true
        } catch (e: Exception) {
            false
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

    /** Stops any in-flight connection, reconnect retries, liveness checks, and active network callback. */
    fun release() {
        retryJob?.cancel()
        retryJob = null
        livenessJob?.cancel()
        livenessJob = null
        onStateChange = null
        unregisterCallback()
    }
}
