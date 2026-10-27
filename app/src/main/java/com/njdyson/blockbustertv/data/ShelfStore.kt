package com.njdyson.blockbustertv.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** A film on a shelf. Title, year and poster are kept so the phone page can show it without a lookup. */
@Serializable
data class ShelfFilm(
    val jwId: String,
    val imdbId: String = "",
    val title: String,
    val year: Int? = null,
    val poster: String? = null,
)

@Serializable
data class Shelf(val id: String, val name: String, val films: List<ShelfFilm>)

@Serializable
data class ShelvesFile(
    val version: Int = 1,
    val shelves: List<Shelf>,
    /** JustWatch IDs of films bought on YouTube / Google TV, so their Watch button opens YouTube. */
    val owned: List<String> = emptyList(),
)

/**
 * The user's shelves, saved as JSON in the app's files. The same format is the
 * backup/export file. Thread-safe: the phone editing server changes it from its own threads.
 */
class ShelfStore(context: Context) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val file = File(context.filesDir, "shelves.json")
    private val saved = load(context)
    private val _shelves = MutableStateFlow(saved.shelves)
    val shelves: StateFlow<List<Shelf>> = _shelves.asStateFlow()
    private val _owned = MutableStateFlow(saved.owned.toSet())
    val owned: StateFlow<Set<String>> = _owned.asStateFlow()

    fun export(): String = json.encodeToString(ShelvesFile.serializer(), snapshot())

    private fun snapshot() = ShelvesFile(shelves = _shelves.value, owned = _owned.value.sorted())

    @Synchronized
    fun setOwned(jwId: String, owned: Boolean) {
        _owned.value = if (owned) _owned.value + jwId else _owned.value - jwId
        save()
    }

    fun addShelf(name: String): String {
        val id = UUID.randomUUID().toString().take(8)
        update { it + Shelf(id, name.trim().ifEmpty { "New shelf" }, emptyList()) }
        return id
    }

    fun renameShelf(id: String, name: String) = updateShelf(id) { it.copy(name = name.trim().ifEmpty { it.name }) }

    fun deleteShelf(id: String) = update { list -> list.filterNot { it.id == id } }

    fun moveShelf(id: String, delta: Int) = update { list -> list.move(list.indexOfFirst { it.id == id }, delta) }

    fun addFilm(shelfId: String, film: ShelfFilm) = updateShelf(shelfId) { shelf ->
        if (shelf.films.any { it.jwId == film.jwId }) shelf else shelf.copy(films = shelf.films + film)
    }

    fun removeFilm(shelfId: String, jwId: String) = updateShelf(shelfId) { shelf ->
        shelf.copy(films = shelf.films.filterNot { it.jwId == jwId })
    }

    fun moveFilm(shelfId: String, jwId: String, delta: Int) = updateShelf(shelfId) { shelf ->
        shelf.copy(films = shelf.films.move(shelf.films.indexOfFirst { it.jwId == jwId }, delta))
    }

    private fun updateShelf(id: String, change: (Shelf) -> Shelf) = update { list -> list.map { if (it.id == id) change(it) else it } }

    @Synchronized
    private fun update(change: (List<Shelf>) -> List<Shelf>) {
        _shelves.value = change(_shelves.value)
        save()
    }

    private fun save() = file.writeText(json.encodeToString(ShelvesFile.serializer(), snapshot()))

    /** Saved shelves, or on first run the starter shelves from assets/collections.json. */
    private fun load(context: Context): ShelvesFile {
        runCatching { return json.decodeFromString(ShelvesFile.serializer(), file.readText()) }
        return runCatching {
            val starter = context.assets.open("collections.json").bufferedReader().use { it.readText() }
            val shelves = json.decodeFromString<StarterFile>(starter).collections.map { c ->
                Shelf(UUID.randomUUID().toString().take(8), c.name, c.films.map { ShelfFilm(it.jwId, it.imdbId, it.title) })
            }
            ShelvesFile(shelves = shelves)
        }.getOrDefault(ShelvesFile(shelves = emptyList()))
    }

    @Serializable
    private data class StarterFile(val collections: List<FilmCollection>)

    private fun <T> List<T>.move(index: Int, delta: Int): List<T> {
        val target = index + delta
        if (index < 0 || target !in indices) return this
        return toMutableList().apply { add(target, removeAt(index)) }
    }
}
