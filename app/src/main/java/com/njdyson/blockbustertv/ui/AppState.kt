package com.njdyson.blockbustertv.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.njdyson.blockbustertv.BuildConfig
import com.njdyson.blockbustertv.data.AccountStore
import com.njdyson.blockbustertv.data.Film
import com.njdyson.blockbustertv.data.RemoteLibrary
import com.njdyson.blockbustertv.data.UnlinkedException
import com.njdyson.blockbustertv.data.FilmLauncher
import com.njdyson.blockbustertv.data.FilmRepository
import com.njdyson.blockbustertv.data.JustWatch
import com.njdyson.blockbustertv.data.Plex
import com.njdyson.blockbustertv.data.PlexFilm
import com.njdyson.blockbustertv.data.PlexIndex
import com.njdyson.blockbustertv.data.PlexLibrary
import com.njdyson.blockbustertv.data.PlexStore
import com.njdyson.blockbustertv.data.Services
import com.njdyson.blockbustertv.data.Shelf
import com.njdyson.blockbustertv.data.ShelfFilm
import com.njdyson.blockbustertv.data.ShelfStore
import com.njdyson.blockbustertv.data.isSeriesId
import com.njdyson.blockbustertv.linklab.LinkSpec
import com.njdyson.blockbustertv.linklab.ServiceTest
import kotlinx.coroutines.flow.StateFlow
import com.njdyson.blockbustertv.server.EditServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

sealed interface Screen {
    data object Home : Screen
    data class Detail(val jwId: String) : Screen
    data object Search : Screen
    data object PhoneEdit : Screen
    data object LinkAccount : Screen
    data object PlexLink : Screen
    data object Settings : Screen
    data object LinkLab : Screen
    data class BoxSet(val shelfId: String, val setId: String) : Screen
    data object Shortlist : Screen
}

/** App-wide state: shelves, film data, and Home's scroll and focus so Back returns to the same box. */
class AppState(private val context: Context, private val scope: CoroutineScope) {
    val services = Services(context)
    val justWatch = JustWatch(services)
    val shelfStore = ShelfStore(context)
    val repository = FilmRepository(context, justWatch)
    val launcher = FilmLauncher(context, services)
    val server = EditServer(context, shelfStore, justWatch) { jwId -> films[jwId]?.poster }
    private val account = AccountStore(context)
    val plex = PlexStore(context)
    val serverUrl: String = BuildConfig.SERVER_URL

    /** True when this TV is linked to an account on the server, which then holds the shelves. */
    var linked by mutableStateOf(false)
        private set

    var films by mutableStateOf<Map<String, Film>>(emptyMap())
        private set
    var status by mutableStateOf<String?>(null)
        private set

    private val shelfStates = mutableMapOf<String, LazyListState>()
    fun shelfState(shelfId: String): LazyListState = shelfStates.getOrPut(shelfId) { LazyListState() }
    var focusedKey by mutableStateOf<String?>(null)

    /**
     * Fixes the order of shuffled shelves for a session: new when the app starts, and when
     * it comes back after half an hour or more away, so the shelves feel fresh each time.
     */
    var shuffleSeed by mutableLongStateOf(System.nanoTime())
        private set

    fun reshuffleIfAwayFor(awayMs: Long) {
        if (awayMs >= 30 * 60 * 1000L) {
            shuffleSeed = System.nanoTime()
            focusedKey = null
        }
    }

    private val uiPrefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)

    /** Home zoomed out: every shelf as a tile of four posters. Remembered between launches. */
    var overview by mutableStateOf(uiPrefs.getBoolean("overview", false))
        private set

    fun showOverview(show: Boolean) {
        overview = show
        uiPrefs.edit().putBoolean("overview", show).apply()
    }

    /**
     * Showing series instead of films: Home, Add and the shortlist then use the series
     * shelves, which are kept apart from the film shelves. Films are the default. Remembered between launches.
     */
    var seriesMode by mutableStateOf(uiPrefs.getBoolean("seriesMode", false))
        private set

    fun showSeries(show: Boolean) {
        seriesMode = show
        focusedKey = null
        uiPrefs.edit().putBoolean("seriesMode", show).apply()
    }

    /** The shelves for the current mode: series shelves or film shelves. */
    val currentShelves: StateFlow<List<Shelf>> get() = if (seriesMode) shelfStore.series else shelfStore.shelves

    /** "films" or "series", for labels. */
    val noun: String get() = if (seriesMode) "series" else "films"

    /** Home shows only films that are free to watch (see [isFreeToWatch]). Remembered between launches. */
    var freeOnly by mutableStateOf(uiPrefs.getBoolean("freeOnly", false))
        private set

    fun showFreeOnly(show: Boolean) {
        freeOnly = show
        uiPrefs.edit().putBoolean("freeOnly", show).apply()
    }

    /**
     * Free to watch: included at no extra cost on one of the services in services.json
     * (subscription, free or with ads), already owned on YouTube, or on the user's own Plex
     * server. Rent or buy only, not streaming, or not loaded yet doesn't count.
     */
    fun isFreeToWatch(film: ShelfFilm): Boolean =
        film.jwId in shelfStore.owned.value || films[film.jwId]?.watchOffers?.isNotEmpty() == true || plexCopy(film) != null

    // Rebuilt only when the Plex film list changes.
    private var plexIndexFor: PlexLibrary? = null
    private var plexIndexCache = PlexIndex(null)
    private val plexIndex: PlexIndex
        get() = plex.library.let { lib ->
            if (lib !== plexIndexFor) { plexIndexCache = PlexIndex(lib); plexIndexFor = lib }
            plexIndexCache
        }

    /** The user's own copy of a film on their Plex server, if it has one. Films only. */
    fun plexCopy(film: ShelfFilm): PlexFilm? {
        if (isSeriesId(film.jwId)) return null
        val index = plexIndex.takeUnless { it.isEmpty } ?: return null
        val data = films[film.jwId]
        return index.find(data?.imdbId ?: film.imdbId, data?.title ?: film.title, data?.year ?: film.year)
    }

    fun plexCopy(film: Film): PlexFilm? = plexCopy(ShelfFilm(film.jwId, film.imdbId, film.title, film.year))

    var plexStatus by mutableStateOf<String?>(null)
        private set

    /** Reads the Plex film list again: from Settings, or quietly on launch when it's a few hours old. */
    fun refreshPlex(quiet: Boolean = false) = scope.launch {
        if (!plex.connected) return@launch
        if (!quiet) plexStatus = "Reading your Plex films…"
        try {
            val library = withContext(Dispatchers.IO) { plex.refresh() }
            plexStatus = if (quiet) null else plexSummary(library)
        } catch (e: Exception) {
            if (!quiet) plexStatus = e.message ?: "Couldn't reach your Plex server"
        }
    }

    /** E.g. "312 films on NAS, 41 of them on your shelves". */
    fun plexSummary(library: PlexLibrary): String {
        val onShelves = (shelfStore.shelves.value.flatMap { it.films } + shortlist).distinctBy { it.jwId }.count { plexCopy(it) != null }
        return "${library.films.size} films on ${library.serverName.ifBlank { "your Plex server" }}, $onShelves of them on your shelves"
    }

    fun disconnectPlex() {
        plex.disconnect()
        plexStatus = null
    }

    /**
     * Link Lab tests for opening a film on the user's own server in the Plex app. The link
     * format for that isn't known, so these are candidates; the film page link is known to open.
     */
    suspend fun plexLinkTests(): List<ServiceTest> {
        val library = plex.library ?: return emptyList()
        val onShelves = (shelfStore.shelves.value.flatMap { it.films } + shortlist).firstNotNullOfOrNull { f ->
            plexCopy(f)?.let { it to films[f.jwId] }
        }
        val (copy, film) = onShelves ?: (library.films.firstOrNull { it.plexId != null } ?: library.films.firstOrNull())?.let { it to null } ?: return emptyList()
        val page = copy.plexId?.let { withContext(Dispatchers.IO) { Plex.pageUrl(it) } }
        val key = java.net.URLEncoder.encode("/library/metadata/${copy.ratingKey}", "UTF-8")
        val server = library.machineId
        val links = listOfNotNull(
            page?.let { LinkSpec("Plex film page", it, Plex.PACKAGE) },
            LinkSpec("plex://server", "plex://server://$server/com.plexapp.plugins.library/library/metadata/${copy.ratingKey}", Plex.PACKAGE),
            LinkSpec("plex://preplay", "plex://preplay/?metadataKey=$key&metadataType=1&server=$server", Plex.PACKAGE),
            LinkSpec("plex://play", "plex://play/?metadataKey=$key&metadataType=1&server=$server", Plex.PACKAGE),
            LinkSpec("app.plex.tv details", "https://app.plex.tv/desktop/#!/server/$server/details?key=$key", Plex.PACKAGE),
        )
        return listOf(
            ServiceTest(
                service = "plexown",
                serviceName = "Plex (your server)",
                film = listOfNotNull(copy.title, copy.year?.let { "($it)" }).joinToString(" "),
                imdbId = copy.imdbId.orEmpty(),
                packages = listOf(Plex.PACKAGE),
                links = links,
                poster = film?.poster,
                backdrop = film?.backdrop,
            ),
        )
    }

    /**
     * Films picked while browsing, to compare side by side before choosing one. Kept on this
     * TV only (not synced to the account), in the order they were added. Remembered between launches.
     */
    var shortlist by mutableStateOf(
        runCatching { Json.decodeFromString(shortlistSerializer, uiPrefs.getString("shortlist", null) ?: "[]") }.getOrDefault(emptyList()),
    )
        private set

    fun isShortlisted(jwId: String): Boolean = shortlist.any { it.jwId == jwId }

    /** The shortlisted films, or the shortlisted series in series mode. */
    val currentShortlist: List<ShelfFilm> get() = shortlist.filter { isSeriesId(it.jwId) == seriesMode }

    /** Adds the film to the shortlist, or takes it off if it's already there. */
    fun toggleShortlist(film: ShelfFilm) {
        if (isShortlisted(film.jwId)) {
            saveShortlist(shortlist.filterNot { it.jwId == film.jwId })
            toast("Took ${film.title} off the shortlist", Toast.LENGTH_SHORT)
        } else {
            saveShortlist(shortlist + film)
            toast("Added ${film.title} to the shortlist (${shortlist.count { isSeriesId(it.jwId) == isSeriesId(film.jwId) }})", Toast.LENGTH_SHORT)
        }
    }

    fun removeFromShortlist(jwId: String) = saveShortlist(shortlist.filterNot { it.jwId == jwId })

    /** Clears the shortlist for the current mode (films or series), keeping the other. */
    fun clearShortlist() = saveShortlist(shortlist - currentShortlist.toSet())

    private fun saveShortlist(films: List<ShelfFilm>) {
        shortlist = films
        uiPrefs.edit().putString("shortlist", Json.encodeToString(shortlistSerializer, films)).apply()
    }

    init {
        account.deviceKey?.let { connect(it) }
        shelfStore.onSyncError = { e ->
            mainThread.post {
                if (e is UnlinkedException) unlinkLocally("This TV was unlinked from your account")
                else toast("Couldn't save to your account. Check the internet connection.")
            }
        }
    }

    private val mainThread = Handler(Looper.getMainLooper())

    private fun connect(deviceKey: String) {
        shelfStore.remote = RemoteLibrary(serverUrl, deviceKey)
        linked = true
    }

    /** Pulls the account's shelves (on launch, on returning to the app, and every minute). */
    fun sync() {
        val remote = shelfStore.remote ?: return
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) { remote.library() }
                shelfStore.replace(file)
            } catch (e: UnlinkedException) {
                unlinkLocally("This TV was unlinked from your account")
            } catch (e: Exception) {
                // Offline: keep showing the saved copy.
            }
        }
    }

    /**
     * Finishes linking: if the account has no shelves yet it gets this TV's, otherwise the
     * TV switches to the account's. The local phone-editing server is no longer needed.
     */
    suspend fun completeLink(deviceKey: String) {
        val remote = RemoteLibrary(serverUrl, deviceKey)
        val file = withContext(Dispatchers.IO) { remote.importIfEmpty(shelfStore.snapshot()) }
        account.deviceKey = deviceKey
        shelfStore.replace(file)
        connect(deviceKey)
        server.stop()
    }

    /** Unlinks this TV; it keeps its current shelves locally. */
    fun unlink() {
        val remote = shelfStore.remote
        scope.launch(Dispatchers.IO) { runCatching { remote?.forget() } }
        unlinkLocally("This TV is no longer linked. It keeps its shelves.")
    }

    private fun unlinkLocally(message: String) {
        if (!linked) return
        account.deviceKey = null
        shelfStore.remote = null
        linked = false
        toast(message)
    }

    private fun toast(message: String, length: Int = Toast.LENGTH_LONG) = Toast.makeText(context, message, length).show()

    // Film and series shelves both. Shortlisted films stay loaded even if they're later taken off every shelf.
    private fun allIds(shelves: List<Shelf> = shelfStore.shelves.value + shelfStore.series.value) =
        (shelves.flatMap { s -> s.films.map { it.jwId } } + shortlist.map { it.jwId }).distinct()

    fun load() = scope.launch {
        if (plex.isStale()) refreshPlex(quiet = true)
        films = repository.cached()
        if (films.isEmpty() || repository.isStale()) refresh() else fetchMissing(shelfStore.shelves.value + shelfStore.series.value)
    }

    fun refresh() = scope.launch {
        status = "Checking what's streaming…"
        status = try {
            films = repository.refresh(allIds())
            null
        } catch (e: Exception) {
            if (films.isEmpty()) "Couldn't load films: ${e.message}" else "Offline: showing saved films"
        }
    }

    /** Called whenever the shelves change, e.g. from the phone: fetches data for new films. Pass film and series shelves. */
    fun fetchMissing(shelves: List<Shelf>) = scope.launch {
        if (allIds(shelves).all { it in films }) return@launch
        runCatching { films = repository.addMissing(allIds(shelves)) }
    }

    fun addToShelf(shelfId: String, film: ShelfFilm) {
        shelfStore.addFilm(shelfId, film)
        val shelf = (shelfStore.shelves.value + shelfStore.series.value).firstOrNull { it.id == shelfId }?.name
        Toast.makeText(context, "Added ${film.title} to $shelf", Toast.LENGTH_SHORT).show()
    }
}

private val shortlistSerializer = ListSerializer(ShelfFilm.serializer())
