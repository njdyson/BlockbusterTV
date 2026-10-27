package com.njdyson.blockbustertv.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** The key this TV got when it was linked to an account on the BlockbusterTV server. */
class AccountStore(context: Context) {
    private val prefs = context.getSharedPreferences("account", Context.MODE_PRIVATE)

    var deviceKey: String?
        get() = prefs.getString("device_key", null)
        set(value) = prefs.edit().apply { if (value == null) remove("device_key") else putString("device_key", value) }.apply()
}

/** Thrown when the server no longer accepts this TV's key (it was unlinked from the phone app). */
class UnlinkedException : Exception("This TV was unlinked from your account")

private val json = Json { ignoreUnknownKeys = true }

/** Blocking HTTP calls to the server's /api/bbtv routes; call from a background thread. */
private fun request(baseUrl: String, path: String, body: JsonElement? = null, deviceKey: String? = null): String {
    val conn = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
    conn.connectTimeout = 15_000
    conn.readTimeout = 30_000
    conn.setRequestProperty("Content-Type", "application/json")
    deviceKey?.let { conn.setRequestProperty("X-Device-Key", it) }
    if (body != null) {
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
    }
    val code = conn.responseCode
    if (code == 401) throw UnlinkedException()
    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
    val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    if (code == 410) return text // pairing expired: the caller reads the status
    if (code !in 200..299) error("Server error $code: $text")
    return text
}

/** This TV's view of its account's library. */
class RemoteLibrary(private val baseUrl: String, private val deviceKey: String) {
    fun library(): ShelvesFile = parse(request(baseUrl, "/api/bbtv/library", deviceKey = deviceKey))

    fun op(op: JsonObject): ShelvesFile = parse(request(baseUrl, "/api/bbtv/op", op, deviceKey))

    /** Uploads this TV's shelves if the account has none yet; returns the account's library either way. */
    fun importIfEmpty(file: ShelvesFile): ShelvesFile {
        val body = buildJsonObject { put("data", json.encodeToJsonElement(ShelvesFile.serializer(), file)) }
        return parse(request(baseUrl, "/api/bbtv/import", body, deviceKey))
    }

    fun forget() {
        request(baseUrl, "/api/bbtv/devices/forget", JsonObject(emptyMap()), deviceKey)
    }

    private fun parse(text: String) = json.decodeFromString(ShelvesFile.serializer(), text)
}

/** Linking a TV: show a code, the user approves it in the phone app, the TV receives its key. */
object Pairing {
    @Serializable
    data class Started(val code: String, val secret: String, val expiresInSeconds: Int = 600)

    @Serializable
    data class Poll(val status: String, val deviceKey: String? = null)

    fun start(baseUrl: String, deviceName: String): Started =
        json.decodeFromString(request(baseUrl, "/api/bbtv/pair/start", buildJsonObject { put("name", deviceName) }))

    fun poll(baseUrl: String, started: Started): Poll {
        val query = "code=${URLEncoder.encode(started.code, "UTF-8")}&secret=${URLEncoder.encode(started.secret, "UTF-8")}"
        return json.decodeFromString(request(baseUrl, "/api/bbtv/pair/poll?$query"))
    }

    /** The address the TV's QR code opens: the phone app, ready to approve this code. */
    fun approveUrl(baseUrl: String, code: String) = "${baseUrl.trimEnd('/')}/#pair=$code"
}
