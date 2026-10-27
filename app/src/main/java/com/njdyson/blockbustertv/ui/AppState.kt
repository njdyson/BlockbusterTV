package com.njdyson.blockbustertv.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
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
import com.njdyson.blockbustertv.server.EditServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Home : Screen
    data class Detail(val jwId: String) : Screen
    data object Search : Screen
    data object PhoneEdit : Screen
    data object LinkAccount : Screen
    data object Settings : Screen
    data object LinkLab : Screen
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
    val homeListState = LazyListState()
    var focusedKey by mutableStateOf<String?>(null)

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

    private fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()

    private fun allIds(shelves: List<Shelf> = shelfStore.shelves.value) = shelves.flatMap { s -> s.films.map { it.jwId } }

    fun load() = scope.launch {
        films = repository.cached()
        if (films.isEmpty() || repository.isStale()) refresh() else fetchMissing(shelfStore.shelves.value)
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

    /** Called whenever the shelves change, e.g. from the phone: fetches data for new films. */
    fun fetchMissing(shelves: List<Shelf>) = scope.launch {
        if (allIds(shelves).all { it in films }) return@launch
        runCatching { films = repository.addMissing(allIds(shelves)) }
    }

    fun addToShelf(shelfId: String, film: ShelfFilm) {
        shelfStore.addFilm(shelfId, film)
        val shelf = shelfStore.shelves.value.firstOrNull { it.id == shelfId }?.name
        Toast.makeText(context, "Added ${film.title} to $shelf", Toast.LENGTH_SHORT).show()
    }
}
