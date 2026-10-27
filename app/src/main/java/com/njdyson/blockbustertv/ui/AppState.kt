package com.njdyson.blockbustertv.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.njdyson.blockbustertv.data.Film
import com.njdyson.blockbustertv.data.FilmLauncher
import com.njdyson.blockbustertv.data.FilmRepository
import com.njdyson.blockbustertv.data.JustWatch
import com.njdyson.blockbustertv.data.Services
import com.njdyson.blockbustertv.data.Shelf
import com.njdyson.blockbustertv.data.ShelfFilm
import com.njdyson.blockbustertv.data.ShelfStore
import com.njdyson.blockbustertv.server.EditServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Home : Screen
    data class Detail(val jwId: String) : Screen
    data object Search : Screen
    data object PhoneEdit : Screen
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

    var films by mutableStateOf<Map<String, Film>>(emptyMap())
        private set
    var status by mutableStateOf<String?>(null)
        private set

    private val shelfStates = mutableMapOf<String, LazyListState>()
    fun shelfState(shelfId: String): LazyListState = shelfStates.getOrPut(shelfId) { LazyListState() }
    val homeListState = LazyListState()
    var focusedKey by mutableStateOf<String?>(null)

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
