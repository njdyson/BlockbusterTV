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
import com.njdyson.blockbustertv.data.Services
import com.njdyson.blockbustertv.data.Shelf
import com.njdyson.blockbustertv.data.ShelfFilm
import com.njdyson.blockbustertv.data.ShelfStore
import com.njdyson.blockbustertv.data.isSeriesId
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
     * (subscription, free or with ads), or already owned on YouTube. Rent or buy only, not
     * streaming, or not loaded yet doesn't count.
     */
    fun isFreeToWatch(film: ShelfFilm): Boolean =
        film.jwId in shelfStore.owned.value || films[film.jwId]?.watchOffers?.isNotEmpty() == true

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
