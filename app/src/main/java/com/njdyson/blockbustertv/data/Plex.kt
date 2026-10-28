package com.njdyson.blockbustertv.data

import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/** A film in the user's own Plex library. [plexId] is Plex's own ID for the film (from plex://movie/...), when it has one. */
@Serializable
data class PlexFilm(
    val ratingKey: String,
    val title: String,
    val year: Int? = null,
    val imdbId: String? = null,
    val plexId: String? = null,
)

@Serializable
data class PlexLibrary(val serverName: String, val machineId: String, val films: List<PlexFilm>, val refreshedAt: Long)

/** A server this TV can reach: where it answered, and the token it takes. */
data class PlexServer(val name: String, val machineId: String, val baseUrl: String, val token: String)

/**
 * The user's Plex Media Server (their own, or one a friend shares with them): which films it
 * holds, so the app can show which films are "On Plex". Kept on this TV only.
 */
class PlexStore(context: Context) {
    private val prefs = context.getSharedPreferences("plex", Context.MODE_PRIVATE)
    private val file = File(context.filesDir, "plex.json")
    private val json = Json { ignoreUnknownKeys = true }

    /** Identifies this TV to Plex; it's how plex.tv lists the app under Authorized Devices. */
    val clientId: String = prefs.getString("client_id", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("client_id", it).apply()
    }

    var library by mutableStateOf(runCatching { json.decodeFromString(PlexLibrary.serializer(), file.readText()) }.getOrNull())
        private set

    val connected: Boolean get() = prefs.getString("base_url", null) != null

    /** The account token from signing in at plex.tv/link; null when the address and token were typed in. */
    private val accountToken: String? get() = prefs.getString("account_token", null)

    private val server: PlexServer?
        get() {
            val base = prefs.getString("base_url", null) ?: return null
            val token = prefs.getString("server_token", null) ?: return null
            return PlexServer(prefs.getString("server_name", null).orEmpty(), prefs.getString("machine_id", null).orEmpty(), base, token)
        }

    /** Older than [maxAgeMs], or never read. */
    fun isStale(maxAgeMs: Long = 6 * 60 * 60 * 1000L) =
        connected && System.currentTimeMillis() - (library?.refreshedAt ?: 0) > maxAgeMs

    /** Signed in at plex.tv/link: finds a server the account can see (its own or one shared with it) and reads its films. Blocking. */
    fun connectWithAccount(accountToken: String): PlexLibrary {
        val server = Plex.findServer(clientId, accountToken) ?: error("Couldn't reach a Plex server on your account. Is it switched on, with Remote Access on if it's away from home?")
        save(server, accountToken)
        return refresh()
    }

    /** The address and token typed in by hand. Blocking. */
    fun connectManually(address: String, token: String): PlexLibrary {
        val server = Plex.candidateUrls(address).firstNotNullOfOrNull { Plex.probe(clientId, it, token.trim()) }
            ?: error("Couldn't reach a Plex server at $address with that token")
        save(server, null)
        return refresh()
    }

    /** Reads the film list again. If the server has moved (a new IP address), finds it again through the account. Blocking. */
    fun refresh(): PlexLibrary {
        val saved = server ?: error("Plex isn't connected")
        val reachable = Plex.probe(clientId, saved.baseUrl, saved.token)
            ?: accountToken?.let { Plex.findServer(clientId, it)?.also { found -> save(found, it) } }
            ?: error("Couldn't reach your Plex server at ${saved.baseUrl}")
        val films = Plex.films(clientId, reachable)
        return PlexLibrary(reachable.name, reachable.machineId, films, System.currentTimeMillis()).also {
            file.writeText(json.encodeToString(PlexLibrary.serializer(), it))
            library = it
        }
    }

    fun disconnect() {
        prefs.edit().remove("base_url").remove("server_token").remove("server_name").remove("machine_id").remove("account_token").apply()
        file.delete()
        library = null
    }

    private fun save(server: PlexServer, accountToken: String?) {
        prefs.edit()
            .putString("base_url", server.baseUrl)
            .putString("server_token", server.token)
            .putString("server_name", server.name)
            .putString("machine_id", server.machineId)
            .apply { if (accountToken != null) putString("account_token", accountToken) else remove("account_token") }
            .apply()
    }
}

/** Plex's web APIs: plex.tv for signing in and finding servers, the server itself for its films. Blocking calls. */
object Plex {
    const val PACKAGE = "com.plexapp.android"
    const val ICON = "https://images.justwatch.com/icon/301832745/s100/plex.png"
    const val LINK_URL = "https://plex.tv/link"

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class Pin(val id: Long, val code: String, val expiresIn: Int = 900)

    /** A 4-character code to enter at plex.tv/link. */
    fun startPin(clientId: String): Pin =
        json.decodeFromString(Pin.serializer(), get("https://plex.tv/api/v2/pins?strong=false", clientId, method = "POST"))

    /** The account token once the code has been entered at plex.tv/link, else null. */
    fun checkPin(clientId: String, pin: Pin): String? =
        json.parseToJsonElement(get("https://plex.tv/api/v2/pins/${pin.id}", clientId)).jsonObject.str("authToken")?.ifBlank { null }

    /**
     * A server the account can see, its own first, then ones friends share with it, at the first
     * address that answers: home network first, then over the internet, then Plex's relay.
     */
    fun findServer(clientId: String, accountToken: String): PlexServer? {
        val resources = json.parseToJsonElement(
            get("https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=1", clientId, accountToken),
        ).jsonArray.map { it.jsonObject }
        val servers = resources
            .filter { it.str("provides").orEmpty().split(",").contains("server") }
            .sortedBy { it["owned"]?.jsonPrimitive?.booleanOrNull != true }
        for (server in servers) {
            val token = server.str("accessToken") ?: accountToken
            val connections = server["connections"]?.jsonArray.orEmpty().map { it.jsonObject }
                .sortedBy { c -> if (c.bool("relay")) 2 else if (c.bool("local")) 0 else 1 }
            val urls = connections.flatMap { c ->
                // A local connection's https address (*.plex.direct) can be blocked by the router's DNS: try plain http too.
                listOfNotNull(c.str("uri"), if (c.bool("local")) "http://${c.str("address")}:${c.str("port") ?: "32400"}" else null)
            }.distinct()
            urls.firstNotNullOfOrNull { probe(clientId, it, token) }?.let { return it }
        }
        return null
    }

    /** Addresses to try for one typed in: "192.168.1.20", "192.168.1.20:32400" or a full URL. */
    fun candidateUrls(address: String): List<String> {
        val clean = address.trim().trimEnd('/')
        if (clean.startsWith("http://") || clean.startsWith("https://")) return listOf(clean)
        val withPort = if (Regex(":\\d+$").containsMatchIn(clean)) clean else "$clean:32400"
        return listOf("http://$withPort", "https://$withPort")
    }

    /** The server at [baseUrl], if it answers with this token. */
    fun probe(clientId: String, baseUrl: String, token: String): PlexServer? = runCatching {
        val container = json.parseToJsonElement(get("$baseUrl/", clientId, token, timeoutMs = 5_000)).jsonObject.container()
        PlexServer(container.str("friendlyName").orEmpty(), container.str("machineIdentifier") ?: return null, baseUrl, token)
    }.getOrNull()

    /** Every film in every film library on the server, with the IMDb ID Plex matched it to. */
    fun films(clientId: String, server: PlexServer): List<PlexFilm> {
        val sections = json.parseToJsonElement(get("${server.baseUrl}/library/sections", clientId, server.token))
            .jsonObject.container()["Directory"]?.jsonArray.orEmpty().map { it.jsonObject }
            .filter { it.str("type") == "movie" }
        return sections.flatMap { section ->
            val text = get("${server.baseUrl}/library/sections/${section.str("key")}/all?type=1&includeGuids=1", clientId, server.token, timeoutMs = 60_000)
            json.parseToJsonElement(text).jsonObject.container()["Metadata"]?.jsonArray.orEmpty().mapNotNull { parseFilm(it.jsonObject) }
        }.distinctBy { it.ratingKey }
    }

    internal fun parseFilm(m: JsonObject): PlexFilm? {
        val ratingKey = m.str("ratingKey") ?: return null
        val guid = m.str("guid").orEmpty()
        val ids = m["Guid"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject.str("id") } + guid
        return PlexFilm(
            ratingKey = ratingKey,
            title = m.str("title").orEmpty(),
            year = m["year"]?.jsonPrimitive?.intOrNull,
            // Current agent: Guid ids like "imdb://tt0119116". Legacy agent: the guid itself, "com.plexapp.agents.imdb://tt0119116?lang=en".
            imdbId = ids.firstNotNullOfOrNull { Regex("imdb://(tt\\d+)").find(it)?.groupValues?.get(1) },
            plexId = guid.removePrefix("plex://movie/").takeIf { guid.startsWith("plex://movie/") },
        )
    }

    /** The film's page on watch.plex.tv (which the Plex app opens), looked up from Plex's own ID. No sign-in needed. */
    fun pageUrl(plexId: String): String? = runCatching {
        val text = get("https://discover.provider.plex.tv/library/metadata/${URLEncoder.encode(plexId, "UTF-8")}", null)
        val film = json.parseToJsonElement(text).jsonObject.container()["Metadata"]?.jsonArray?.firstOrNull()?.jsonObject
        film?.str("publicPagesURL") ?: film?.str("slug")?.let { "https://watch.plex.tv/movie/$it" }
    }.getOrNull()

    /**
     * The film's watch.plex.tv page found by title and year, for films the server has no Plex ID
     * for (older Plex agents). No sign-in needed.
     */
    fun searchPageUrl(title: String, year: Int?): String? = runCatching {
        val query = URLEncoder.encode(title, "UTF-8")
        val text = get("https://discover.provider.plex.tv/library/search?query=$query&limit=10&searchTypes=movies&searchProviders=discover", null)
        json.parseToJsonElement(text).jsonObject.container()["SearchResults"]?.jsonArray.orEmpty()
            .flatMap { it.jsonObject["SearchResult"]?.jsonArray.orEmpty() }
            .mapNotNull { it.jsonObject["Metadata"]?.jsonObject }
            .filter { it.str("type") == null || it.str("type") == "movie" }
            .firstOrNull { m ->
                val y = m["year"]?.jsonPrimitive?.intOrNull
                m.str("title").equals(title, ignoreCase = true) && (year == null || y == null || kotlin.math.abs(y - year) <= 1)
            }
            ?.let { it.str("publicPagesURL") ?: it.str("slug")?.let { slug -> "https://watch.plex.tv/movie/$slug" } }
    }.getOrNull()

    /** The film's watch.plex.tv page: from Plex's own ID when the server has one, else by title and year. */
    fun pageUrlFor(copy: PlexFilm, title: String, year: Int?): String? =
        copy.plexId?.let { pageUrl(it) } ?: searchPageUrl(title, year)

    private fun get(url: String, clientId: String?, token: String? = null, method: String = "GET", timeoutMs: Int = 15_000): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = minOf(timeoutMs, 15_000)
        conn.readTimeout = timeoutMs
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("X-Plex-Product", "Blockbuster TV")
        conn.setRequestProperty("X-Plex-Device-Name", "${Build.MANUFACTURER} ${Build.MODEL}")
        clientId?.let { conn.setRequestProperty("X-Plex-Client-Identifier", it) }
        token?.let { conn.setRequestProperty("X-Plex-Token", it) }
        val code = conn.responseCode
        if (code == 401) error("Plex didn't accept the sign-in")
        if (code !in 200..299) error("Plex error $code")
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    private fun JsonObject.container(): JsonObject = this["MediaContainer"]?.jsonObject ?: this
    private fun JsonObject.str(key: String): String? = this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
    private fun JsonObject.bool(key: String): Boolean = this[key]?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() } == true
}

/** Finds the user's Plex copy of a film: by IMDb ID, else (for films Plex couldn't identify) by title and year. */
class PlexIndex(library: PlexLibrary?) {
    private val films = library?.films.orEmpty()
    private val byImdb = films.filter { it.imdbId != null }.associateBy { it.imdbId }
    private val unidentified = films.filter { it.imdbId == null }.groupBy { normalise(it.title) }

    fun find(imdbId: String?, title: String, year: Int?): PlexFilm? {
        imdbId?.takeIf { it.isNotBlank() }?.let { id -> byImdb[id]?.let { return it } }
        // Release years differ by one between sources now and then.
        return unidentified[normalise(title)]?.firstOrNull { year == null || it.year == null || kotlin.math.abs(it.year - year) <= 1 }
    }

    val isEmpty: Boolean get() = films.isEmpty()

    private fun normalise(title: String) = title.lowercase().replace("&", "and").filter { it.isLetterOrDigit() }
}
