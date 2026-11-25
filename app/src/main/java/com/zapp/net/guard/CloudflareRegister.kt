package com.zapp.net.guard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to Cloudflare's WARP registration API the same way wgcf does:
 * create device -> get WireGuard peer config -> fresh /64.
 *
 * Endpoint observed live and probed end-to-end (2026-11-18):
 *   POST https://api.cloudflareclient.com/v0a2302/reg  → HTTP 200
 *   DELETE /v0a2302/reg/{id}
 * Headers: Content-Type application/json, User-Agent okhttp/3.12.1, Accept application/json.
 * Body key = BASE64 (RFC4648 std) of the raw 32-byte X25519 pubkey WITH '=' padding.
 *   (without padding -> 400 {"errors":[{"code":1051,"message":"Invalid public key"}]} )
 * Response carries config{interface{addresses{v4,v6}}, peers[{endpoint{v6,v4}, public_key}]}.
 */
object CloudflareRegister {

    private const val API = "https://api.cloudflareclient.com"
    private const val VER = "v0a2302"
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class Registration(
        val regId: String,
        val peerPubHex: String,
        val endpoint: String,
        val ourV6: String,
        val ourV4: String?,
    )

    /** Creates a new device registration for the given raw pubkey hex. */
    suspend fun register(pubHex: String): Registration = withContext(Dispatchers.IO) {
        val keyBytes = pubKeyBytes(pubHex)
        val tos = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"))
        val body = """
            {
              "key": "${java.util.Base64.getEncoder().encodeToString(keyBytes)}",
              "install_id": "",
              "fcm_token": "",
              "tos": "$tos",
              "model": "PC",
              "serial_number": "zapp-${System.currentTimeMillis()}",
              "locale": "en_US"
            }
        """.trimIndent()

        val conn = (URL("$API/$VER/reg").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "okhttp/3.12.1") // matches warp app traffic shape
            setRequestProperty("Accept", "application/json")
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 20_000
        }
        conn.outputStream.use { it.write(body.toByteArray()) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
        conn.disconnect()
        check(code in 200..299) { "register -> HTTP $code: ${text.take(160)}" }

        val root = json.parseToJsonElement(text).jsonObject
        val regId = root["id"]?.jsonPrimitive?.content
            ?: error("no id in register response")
        val cfg = root["config"]?.jsonObject
            ?: error("no config in response")
        val peers = cfg["peers"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: error("no peers[0]")
        val pub = peers["public_key"]?.jsonPrimitive?.content
            ?: peers["public_key_hex"]?.jsonPrimitive?.content
            ?: error("no peer public key")
        val peerPubHex = normalizePeerKey(pub)
        val endpoints = peers["endpoint"]?.jsonObject
            ?: error("no endpoint")
        // register contract: endpoint.v4 carries the edge IP with port 0; the real
        // ports live in endpoint.ports (2408, 500, 1701, 4500). Build v4:2408.
        val v4Edge = endpoints["v4"]?.jsonPrimitive?.content
            ?.substringBefore(":")
            ?.takeIf { it.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+")) }
        val v6 = when {
            v4Edge != null -> "$v4Edge:2408"
            endpoints["host"]?.jsonPrimitive?.content?.let { h -> !h.endsWith(":0") } == true ->
                endpoints["host"]?.jsonPrimitive?.content ?: "engage.cloudflareclient.com:2408"
            else -> "engage.cloudflareclient.com:2408"
        }
        val addr = cfg["interface"]?.jsonObject?.get("addresses")?.jsonObject
        val ourV6 = addr?.get("v6")?.jsonPrimitive?.content ?: error("no interface v6")
        val ourV4 = addr["v4"]?.jsonPrimitive?.content

        Registration(regId, peerPubHex, v6, ourV6, ourV4)
    }

    /** Server returns the peer key as base64; the tunnel wants hex. Accept both. */
    private fun normalizePeerKey(s: String): String {
        val t = s.trim()
        if (t.matches(Regex("^[0-9a-fA-F]{64}$"))) return t.lowercase()
        val bytes = java.util.Base64.getDecoder().decode(t)
        require(bytes.size == 32) { "peer key decoded to ${bytes.size} bytes" }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun pubKeyBytes(hex: String): ByteArray {
        val clean = hex.trim().lowercase().replace("0x", "")
        require(clean.length == 64) { "bad pub hex len ${clean.length}" }
        val out = ByteArray(32)
        for (i in 0 until 32) out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        return out
    }

    suspend fun delete(regId: String) = withContext(Dispatchers.IO) {
        val conn = (URL("$API/$VER/reg/$regId").openConnection() as HttpURLConnection).apply {
            requestMethod = "DELETE"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "okhttp/3.12.1")
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        val code = conn.responseCode
        conn.disconnect()
        // best effort: deleting our own regs keeps Cloudflare happy
        code in 200..299 || code == 404
    }
}
