package com.example.sdtech_android_native.pairing

import org.json.JSONException
import org.json.JSONObject

/** The 4 fields carried by the pairing QR code's JSON payload. */
data class PairingInfo(
    val ssid: String,
    val passwd: String,
    val deviceId: String,
    val pubKey: String,
) {
    companion object {
        /** Parses [raw] (the QR code's text content) into a [PairingInfo], or null if it isn't a valid pairing payload. */
        fun parse(raw: String): PairingInfo? {
            return try {
                val json = JSONObject(raw)
                val ssid = json.getString("ssid")
                val passwd = json.getString("passwd")
                val deviceId = json.getString("deviceid")
                val pubKey = json.getString("pubkey")
                if (ssid.isBlank()) return null
                PairingInfo(ssid, passwd, deviceId, pubKey)
            } catch (_: JSONException) {
                null
            }
        }
    }
}
