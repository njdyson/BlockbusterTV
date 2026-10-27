package com.njdyson.blockbustertv.server

import android.content.Context
import com.njdyson.blockbustertv.data.JustWatch
import com.njdyson.blockbustertv.data.ShelfFilm
import com.njdyson.blockbustertv.data.ShelfStore
import com.njdyson.blockbustertv.data.ShelvesFile
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

/**
 * A small web server on the stick that serves the phone editing page (assets/web) and
 * its API, on the home network only. Every API call must carry the secret key from the
 * QR code, so other devices on the Wi-Fi can't change the shelves.
 */
class EditServer(
    private val context: Context,
    private val store: ShelfStore,
    private val justWatch: JustWatch,
    /** Poster for a film from the TV's film data, for shelves saved before posters were stored. */
    private val posterFor: (jwId: String) -> String?,
) : NanoHTTPD(PORT) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Kept across restarts so a bookmarked page on the phone keeps working. */
    val key: String = context.getSharedPreferences("edit_server", Context.MODE_PRIVATE).let { prefs ->
        prefs.getString("key", null) ?: buildString {
            val chars = "abcdefghjkmnpqrstuvwxyz23456789"
            val random = SecureRandom()
            repeat(10) { append(chars[random.nextInt(chars.length)]) }
        }.also { prefs.edit().putString("key", it).apply() }
    }

    /** The page address for the QR code, or null when the stick has no network address. */
    val url: String? get() = localAddress()?.let { "http://$it:$PORT/?k=$key" }

    fun startSafely(): Boolean = runCatching {
        if (!isAlive) start(SOCKET_READ_TIMEOUT, false)
    }.isSuccess

    override fun serve(session: IHTTPSession): Response = try {
        when {
            session.uri == "/" || session.uri == "/index.html" -> asset("web/index.html", "text/html")
            !session.uri.startsWith("/api/") -> text(Response.Status.NOT_FOUND, "Not found")
            session.parms["k"] != key && session.headers["x-key"] != key -> text(Response.Status.FORBIDDEN, "Wrong or missing key: scan the QR code on the TV again")
            session.uri == "/api/shelves" -> shelves()
            session.uri == "/api/search" -> {
                val results = runBlocking { justWatch.search(session.parms["q"].orEmpty()) }
                jsonResponse(json.encodeToString(ListSerializer(ShelfFilm.serializer()), results))
            }
            session.uri == "/api/export" -> newFixedLengthResponse(Response.Status.OK, "application/json", store.export()).apply {
                addHeader("Content-Disposition", "attachment; filename=\"blockbustertv-shelves.json\"")
            }
            session.uri == "/api/op" && session.method == Method.POST -> {
                apply(json.parseToJsonElement(body(session)).jsonObject)
                shelves()
            }
            else -> text(Response.Status.NOT_FOUND, "Unknown API call")
        }
    } catch (e: Exception) {
        text(Response.Status.INTERNAL_ERROR, e.message ?: e.javaClass.simpleName)
    }

    /** Edits sent by the page: {"op": "...", ...}. */
    private fun apply(op: JsonObject) {
        fun s(name: String) = op[name]?.jsonPrimitive?.content.orEmpty()
        when (s("op")) {
            "addShelf" -> store.addShelf(s("name"))
            "renameShelf" -> store.renameShelf(s("shelf"), s("name"))
            "deleteShelf" -> store.deleteShelf(s("shelf"))
            "addFilm" -> store.addFilm(s("shelf"), json.decodeFromJsonElement(ShelfFilm.serializer(), op["film"]!!))
            "removeFilm" -> store.removeFilm(s("shelf"), s("jwId"))
            else -> error("Unknown op ${s("op")}")
        }
    }

    private fun shelves(): Response {
        val shelves = store.shelves.value.map { shelf ->
            shelf.copy(films = shelf.films.map { if (it.poster == null) it.copy(poster = posterFor(it.jwId)) else it })
        }
        return jsonResponse(json.encodeToString(ShelvesFile.serializer(), ShelvesFile(shelves = shelves, owned = store.owned.value.sorted())))
    }

    private fun body(session: IHTTPSession): String {
        val files = HashMap<String, String>()
        session.parseBody(files)
        return files["postData"].orEmpty()
    }

    private fun asset(path: String, mime: String): Response =
        newFixedLengthResponse(Response.Status.OK, "$mime; charset=utf-8", context.assets.open(path).bufferedReader().use { it.readText() })

    private fun jsonResponse(body: String): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", body).apply { addHeader("Cache-Control", "no-store") }

    private fun text(status: Response.Status, message: String): Response =
        newFixedLengthResponse(status, "text/plain; charset=utf-8", message)

    companion object {
        const val PORT = 8642

        /** The stick's IPv4 address on the home network (Wi-Fi or Ethernet). */
        fun localAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }
}
