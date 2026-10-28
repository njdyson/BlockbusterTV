package com.njdyson.blockbustertv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.njdyson.blockbustertv.data.Person
import com.njdyson.blockbustertv.data.SearchFilters
import com.njdyson.blockbustertv.data.SearchSort
import com.njdyson.blockbustertv.data.ShelfFilm
import kotlinx.coroutines.delay
import java.text.Normalizer

/** Which filter's picker is open. */
private enum class Picker { GENRE, DECADE, RATING, SERVICE, LANGUAGE, SORT }

/**
 * Quick add on the TV: search by title, actor or director, narrow it down with filters
 * (which also work without any text), pick a result, pick a shelf.
 */
@Composable
fun SearchScreen(app: AppState) {
    // Series mode searches series and adds them to the series shelves.
    val series = app.seriesMode
    val shelves by app.currentShelves.collectAsState()
    // Language is remembered between visits, and the age limit from Settings always applies.
    var filters by remember { mutableStateOf(SearchFilters(series = series, english = app.englishOnly, ageLimit = app.ageLimit)) }
    var results by remember { mutableStateOf<List<ShelfFilm>>(emptyList()) }
    var people by remember { mutableStateOf<List<Person>>(emptyList()) }
    var message by remember {
        mutableStateOf<String?>(if (series) "Type a series or actor, or use the filters to browse." else "Type a film, actor or director, or use the filters to browse.")
    }
    var picking by remember { mutableStateOf<ShelfFilm?>(null) }
    var picker by remember { mutableStateOf<Picker?>(null) }
    val genres = if (series) SearchFilters.GENRES + SearchFilters.SERIES_GENRES else SearchFilters.GENRES

    // Search as you type (after a short pause), since the Fire TV keyboard may have no Search key.
    LaunchedEffect(filters) {
        if (filters.isEmpty) {
            results = emptyList()
            people = emptyList()
            return@LaunchedEffect
        }
        delay(500)
        message = "Searching…"
        message = try {
            results = app.justWatch.search(filters)
            people = if (filters.person == null) app.justWatch.people(filters.text).filter { it.matches(filters.text) }.distinctBy { it.name }.take(3) else emptyList()
            if (results.isEmpty() && people.isEmpty()) "No ${app.noun} found." else null
        } catch (e: Exception) {
            "Search failed: ${e.message}"
        }
    }

    Column(
        Modifier.fillMaxSize().background(NightNavy).padding(horizontal = 48.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Logo()
            Text(if (series) "Add a series" else "Add a film", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.fillMaxWidth(0.6f)) {
            TvTextField(
                placeholder = if (series) "Series or actor" else "Film, actor or director",
                value = filters.text,
                onValueChange = { filters = filters.copy(text = it) },
                onSubmit = { filters = filters.copy(text = it) },
            )
        }
        // Filters: each opens a picker. A chosen person shows first, Select to clear it.
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 4.dp, horizontal = 4.dp)) {
            filters.person?.let { person ->
                item { Button(onClick = { filters = filters.copy(person = null) }) { Text("With ${person.name}  ✕") } }
            }
            item { FilterButton("Genre", genres.firstOrNull { it.first == filters.genre }?.second) { picker = Picker.GENRE } }
            item { FilterButton("Decade", filters.decade?.let { "${it % 100}s".padStart(3, '0') }) { picker = Picker.DECADE } }
            item { FilterButton("IMDb", filters.minRating?.let { "${it.toInt()}+" }) { picker = Picker.RATING } }
            item { FilterButton("On", filters.service?.let { app.services.byId(it)?.name }) { picker = Picker.SERVICE } }
            item { FilterButton("Language", "English".takeIf { filters.english }) { picker = Picker.LANGUAGE } }
            item { FilterButton("Sort", filters.sort.label.takeIf { filters.sort != SearchSort.POPULAR }) { picker = Picker.SORT } }
        }
        // People matching the text: choose one to see their films.
        if (people.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 4.dp, horizontal = 4.dp)) {
                items(people, key = { it.id }) { person ->
                    OutlinedButton(onClick = { filters = filters.copy(person = person, text = "") }) { Text("🎬 ${person.name}") }
                }
            }
        }
        message?.let { Text(it, color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp) }
        // Side padding leaves room for the focused poster to grow without being clipped.
        LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp)) {
            items(results, key = { it.jwId }) { film ->
                Surface(
                    onClick = { picking = film },
                    modifier = Modifier.width(112.dp),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                    colors = ClickableSurfaceDefaults.colors(containerColor = ShelfGrey, focusedContainerColor = ShelfGrey),
                    border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, TicketYellow))),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
                ) {
                    Column {
                        Poster(film.poster, film.title, Modifier.fillMaxWidth().height(168.dp))
                        Column(Modifier.padding(6.dp)) {
                            Text(film.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(film.year?.toString().orEmpty(), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }

    when (picker) {
        Picker.GENRE -> ChoiceDialog(
            "Genre",
            listOf("Any genre" to { filters = filters.copy(genre = null) }) +
                genres.map { (code, name) -> name to { filters = filters.copy(genre = code) } },
            onDismiss = { picker = null },
        )
        Picker.DECADE -> ChoiceDialog(
            "Decade",
            listOf("Any decade" to { filters = filters.copy(decade = null) }) +
                SearchFilters.DECADES.map { decade -> "${decade}s" to { filters = filters.copy(decade = decade) } },
            onDismiss = { picker = null },
        )
        Picker.RATING -> ChoiceDialog(
            "IMDb rating",
            listOf("Any rating" to { filters = filters.copy(minRating = null) }) +
                SearchFilters.RATINGS.map { rating -> "${rating.toInt()} or more" to { filters = filters.copy(minRating = rating) } },
            onDismiss = { picker = null },
        )
        Picker.SERVICE -> ChoiceDialog(
            "Streaming on",
            listOf("Anywhere" to { filters = filters.copy(service = null) }) +
                app.services.all.filter { it.id != "youtube" }.map { service -> service.name to { filters = filters.copy(service = service.id) } },
            onDismiss = { picker = null },
        )
        Picker.LANGUAGE -> ChoiceDialog(
            "Language",
            listOf(
                "Any language" to { filters = filters.copy(english = false); app.showEnglishOnly(false) },
                "English" to { filters = filters.copy(english = true); app.showEnglishOnly(true) },
            ),
            onDismiss = { picker = null },
        )
        Picker.SORT -> ChoiceDialog(
            "Sort by",
            SearchSort.entries.map { sort -> sort.label to { filters = filters.copy(sort = sort) } },
            onDismiss = { picker = null },
        )
        null -> Unit
    }

    picking?.let { film ->
        ShelfPicker(
            shelves,
            onPick = { shelfId, newName ->
                val id = shelfId ?: app.shelfStore.addShelf(newName.orEmpty(), series)
                app.addToShelf(id, film)
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}

/** "Genre: Any" style button; highlighted when the filter is set. */
@Composable
private fun FilterButton(label: String, value: String?, onClick: () -> Unit) {
    if (value != null) Button(onClick = onClick) { Text("$label: $value") }
    else OutlinedButton(onClick = onClick) { Text("$label: Any") }
}

/**
 * Whether every word typed starts a part of the person's name ("nolan", "chris nol"),
 * so a film title like "Back to…" doesn't suggest people who happen to be called Back.
 */
private fun Person.matches(text: String): Boolean {
    fun plain(t: String) = Normalizer.normalize(t, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()
    val parts = plain(name).split(Regex("[\\s.-]+"))
    return plain(text).split(Regex("\\s+")).filter { it.isNotEmpty() }.all { word -> parts.any { it.startsWith(word) } }
}
