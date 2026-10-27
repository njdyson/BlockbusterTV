package com.njdyson.blockbustertv.ui

import android.content.Context
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.njdyson.blockbustertv.data.Film
import com.njdyson.blockbustertv.data.FilmLauncher
import com.njdyson.blockbustertv.data.FilmRepository
import com.njdyson.blockbustertv.data.JustWatch
import com.njdyson.blockbustertv.data.Services
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Home : Screen
    data class Detail(val jwId: String) : Screen
    data object Settings : Screen
    data object LinkLab : Screen
}

/** App-wide state: the film data, plus Home's scroll and focus so Back returns to the same box. */
class AppState(context: Context, private val scope: CoroutineScope) {
    val services = Services(context)
    val repository = FilmRepository(context, JustWatch(services))
    val launcher = FilmLauncher(context, services)

    var films by mutableStateOf<Map<String, Film>>(emptyMap())
        private set
    var status by mutableStateOf<String?>(null)
        private set

    val shelfStates = List(repository.collections.size) { LazyListState() }
    val homeListState = LazyListState()
    var focusedKey by mutableStateOf<String?>(null)

    fun load() = scope.launch {
        films = repository.cached()
        if (films.isEmpty() || repository.isStale()) refresh()
    }

    fun refresh() = scope.launch {
        status = "Checking what's streaming…"
        status = try {
            films = repository.refresh()
            null
        } catch (e: Exception) {
            if (films.isEmpty()) "Couldn't load films: ${e.message}" else "Offline: showing saved films"
        }
    }
}
