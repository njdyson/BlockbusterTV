package com.njdyson.blockbustertv.data

import kotlin.random.Random

/** One box on a shelf: a film, or a set of films shown as one box set. */
sealed interface ShelfItem {
    /** Unique within its shelf: the film's jwId, or "set:" + the set's ID. */
    val key: String
    /** The film whose poster and backdrop represent the item. */
    val cover: ShelfFilm?
}

data class FilmItem(val film: ShelfFilm) : ShelfItem {
    override val key get() = film.jwId
    override val cover get() = film
}

data class SetItem(val set: FilmSet, val films: List<ShelfFilm>) : ShelfItem {
    override val key get() = "set:${set.id}"
    override val cover get() = films.firstOrNull()
}

/** The shelf's items in its own order: each set folded into one item where its first film is. */
fun Shelf.items(): List<ShelfItem> {
    val setOf = sets.flatMap { set -> set.films.map { it to set } }.toMap()
    val byId = films.associateBy { it.jwId }
    val seen = mutableSetOf<String>()
    return films.mapNotNull { film ->
        val set = setOf[film.jwId]
        when {
            set == null -> FilmItem(film)
            seen.add(set.id) -> SetItem(set, set.films.mapNotNull { byId[it] })
            else -> null
        }
    }
}

/** What the TV shows: shuffled shelves in an order fixed for the session's [seed]. */
fun Shelf.displayItems(seed: Long): List<ShelfItem> =
    if (shuffle) items().shuffled(Random(seed xor id.hashCode().toLong())) else items()

/** The films' order that lays the items out as given, sets in their own order. */
fun List<ShelfItem>.flatOrder(): List<String> = flatMap { item ->
    when (item) {
        is FilmItem -> listOf(item.film.jwId)
        is SetItem -> item.films.map { it.jwId }
    }
}

/** Sets only reference films on their shelf; drops missing ones and empty sets. */
fun Shelf.withCleanSets(): Shelf {
    val onShelf = films.map { it.jwId }.toSet()
    return copy(sets = sets.map { set -> set.copy(films = set.films.filter { it in onShelf }) }.filter { it.films.isNotEmpty() })
}

/** Keeps a set's films together in the shelf's order, where its first member is (as the server does). */
fun Shelf.gathered(setId: String): Shelf {
    val set = sets.firstOrNull { it.id == setId } ?: return this
    val members = set.films.toSet()
    val first = films.indexOfFirst { it.jwId in members }
    if (first < 0) return this
    val others = films.filterNot { it.jwId in members }
    val before = films.take(first).count { it.jwId !in members }
    val grouped = set.films.mapNotNull { id -> films.firstOrNull { it.jwId == id } }
    return copy(films = others.take(before) + grouped + others.drop(before))
}
