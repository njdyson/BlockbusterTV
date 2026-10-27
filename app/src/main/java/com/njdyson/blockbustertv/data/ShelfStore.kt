package com.njdyson.blockbustertv.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

/** Films on a shelf grouped into one box, e.g. Rocky 1–5. [films] are jwIds, in the set's own order. */
@Serializable
data class FilmSet(val id: String, val name: String, val films: List<String>)

@Serializable
data class Shelf(
    val id: String,
    val name: String,
    val films: List<ShelfFilm>,
    val sets: List<FilmSet> = emptyList(),
    /** No longer used (every shelf is shuffled); kept so saved data and the server's copy still read. */
    val shuffle: Boolean = false,
)

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
 *
 * When the TV is linked to an account ([remote] set), each edit is applied here straight
 * away and also sent to the server, whose reply then becomes the saved copy.
 */
class ShelfStore(context: Context) {
    @Volatile
    var remote: RemoteLibrary? = null
    var onSyncError: (Exception) -> Unit = {}

    // One at a time, in order, so edits reach the server in the order they were made.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val file = File(context.filesDir, "shelves.json")
    private val saved = load(context)
    private val _shelves = MutableStateFlow(saved.shelves)
    val shelves: StateFlow<List<Shelf>> = _shelves.asStateFlow()
    private val _owned = MutableStateFlow(saved.owned.toSet())
    val owned: StateFlow<Set<String>> = _owned.asStateFlow()

    fun export(): String = json.encodeToString(ShelvesFile.serializer(), snapshot())

    fun snapshot() = ShelvesFile(shelves = _shelves.value, owned = _owned.value.sorted())

    /** Replaces everything with the server's copy. */
    @Synchronized
    fun replace(file: ShelvesFile) {
        _shelves.value = file.shelves
        _owned.value = file.owned.toSet()
        save()
    }

    @Synchronized
    fun setOwned(jwId: String, owned: Boolean) {
        _owned.value = if (owned) _owned.value + jwId else _owned.value - jwId
        save()
        send("setOwned") { put("jwId", jwId); put("owned", owned) }
    }

    fun addShelf(name: String): String {
        // Lower-case letters and digits, which the server accepts as the shelf's ID too.
        val id = UUID.randomUUID().toString().replace("-", "").take(8)
        val clean = name.trim().ifEmpty { "New shelf" }
        update { it + Shelf(id, clean, emptyList()) }
        send("addShelf") { put("id", id); put("name", clean) }
        return id
    }

    fun renameShelf(id: String, name: String) {
        updateShelf(id) { it.copy(name = name.trim().ifEmpty { it.name }) }
        send("renameShelf") { put("shelf", id); put("name", name) }
    }

    fun deleteShelf(id: String) {
        update { list -> list.filterNot { it.id == id } }
        send("deleteShelf") { put("shelf", id) }
    }

    fun addFilm(shelfId: String, film: ShelfFilm) {
        updateShelf(shelfId) { shelf ->
            if (shelf.films.any { it.jwId == film.jwId }) shelf else shelf.copy(films = shelf.films + film)
        }
        send("addFilm") { put("shelf", shelfId); put("film", json.encodeToJsonElement(ShelfFilm.serializer(), film)) }
    }

    fun removeFilm(shelfId: String, jwId: String) {
        updateShelf(shelfId) { shelf -> shelf.copy(films = shelf.films.filterNot { it.jwId == jwId }).withCleanSets() }
        send("removeFilm") { put("shelf", shelfId); put("jwId", jwId) }
    }

    /** Groups films on a shelf into a new set; returns its ID. */
    fun createSet(shelfId: String, name: String, films: List<String>): String {
        val id = UUID.randomUUID().toString().replace("-", "").take(8)
        val clean = name.trim().ifEmpty { "Box set" }
        updateShelf(shelfId) { shelf ->
            val members = films.distinct().filter { jwId -> shelf.films.any { it.jwId == jwId } }
            val others = shelf.sets.map { set -> set.copy(films = set.films - members.toSet()) }
            shelf.copy(sets = others + FilmSet(id, clean, members)).withCleanSets().gathered(id)
        }
        send("createSet") { put("shelf", shelfId); put("id", id); put("name", clean); put("films", JsonArray(films.map(::JsonPrimitive))) }
        return id
    }

    fun renameSet(shelfId: String, setId: String, name: String) {
        updateShelf(shelfId) { shelf -> shelf.copy(sets = shelf.sets.map { if (it.id == setId) it.copy(name = name.trim().ifEmpty { it.name }) else it }) }
        send("renameSet") { put("shelf", shelfId); put("set", setId); put("name", name) }
    }

    fun ungroupSet(shelfId: String, setId: String) {
        updateShelf(shelfId) { shelf -> shelf.copy(sets = shelf.sets.filterNot { it.id == setId }) }
        send("ungroupSet") { put("shelf", shelfId); put("set", setId) }
    }

    fun addToSet(shelfId: String, setId: String, jwId: String) {
        updateShelf(shelfId) { shelf ->
            val sets = shelf.sets.map { set ->
                val without = set.films - jwId
                if (set.id == setId) set.copy(films = without + jwId) else set.copy(films = without)
            }
            shelf.copy(sets = sets).withCleanSets().gathered(setId)
        }
        send("addToSet") { put("shelf", shelfId); put("set", setId); put("jwId", jwId) }
    }

    fun removeFromSet(shelfId: String, jwId: String) {
        updateShelf(shelfId) { shelf -> shelf.copy(sets = shelf.sets.map { it.copy(films = it.films - jwId) }).withCleanSets() }
        send("removeFromSet") { put("shelf", shelfId); put("jwId", jwId) }
    }

    private fun send(op: String, fields: JsonObjectBuilder.() -> Unit) {
        val server = remote ?: return
        val body = buildJsonObject { put("op", op); fields() }
        syncScope.launch {
            try {
                replace(server.op(body))
            } catch (e: Exception) {
                onSyncError(e)
            }
        }
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
}
