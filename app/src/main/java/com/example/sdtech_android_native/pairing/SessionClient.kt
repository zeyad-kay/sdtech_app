package com.example.sdtech_android_native.pairing

import android.net.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

private const val SESSION_PORT = 8000
private const val SOCKET_TIMEOUT_MS = 10_000

/** Result of a successful "start_session" request to the paired device's session server. */
data class StartSessionResult(val sessionId: String?, val streamingUrl: String)

/**
 * Sends one-shot JSON commands over a plain TCP socket (not HTTP) to the
 * paired network's session server on [SESSION_PORT]. Each socket is bound to
 * the given [Network] (the paired WiFi connection) so the request goes out
 * over that network specifically, not whatever the device's current default
 * network happens to be.
 */
object SessionClient {

    /** Sends `{"command": "start_session"}` and parses the server's JSON response. */
    suspend fun startSession(network: Network, hostIp: String): Result<StartSessionResult> =
        sendCommand(network, hostIp, "start_session") { response ->
            val json = JSONObject(response)
            if (!json.optBoolean("ok", false)) {
                return@sendCommand Result.failure(Exception("Server rejected start_session"))
            }
            val streamingUrl = json.optString("streaming_url").takeIf { url -> url.isNotEmpty() }
                ?: return@sendCommand Result.failure(Exception("Missing streaming_url in response"))
            val sessionId = json.optString("session_id").takeIf { id -> id.isNotEmpty() }
            Result.success(StartSessionResult(sessionId, streamingUrl))
        }

    /** Sends `{"command": "stop_session"}`. The response, if any, isn't parsed — this is a best-effort notification once streaming has fully drained. */
    suspend fun stopSession(network: Network, hostIp: String): Result<Unit> =
        sendCommand(network, hostIp, "stop_session") { Result.success(Unit) }

    private suspend fun <T> sendCommand(
        network: Network,
        hostIp: String,
        command: String,
        parseResponse: (String) -> Result<T>,
    ): Result<T> =
        withContext(Dispatchers.IO) {
            try {
                val socket: Socket = network.socketFactory.createSocket()
                socket.use {
                    it.connect(InetSocketAddress(hostIp, SESSION_PORT), SOCKET_TIMEOUT_MS)
                    it.soTimeout = SOCKET_TIMEOUT_MS

                    val writer = OutputStreamWriter(it.getOutputStream())
                    writer.write(JSONObject().put("command", command).toString())
                    writer.flush()
                    it.shutdownOutput()

                    val response = it.getInputStream().bufferedReader().readText()
                    parseResponse(response)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}
