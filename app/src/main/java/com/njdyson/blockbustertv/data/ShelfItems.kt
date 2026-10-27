package com.njdyson.blockbustertv.data

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

/**
 * The shelf's items in its saved order: each set folded into one item where its first film
 * is. A set's films are in release order (earliest first; [yearOf] supplies the years).
 */
fun Shelf.items(yearOf: (ShelfFilm) -> Int? = { it.year }): List<ShelfItem> {
    val setOf = sets.flatMap { set -> set.films.map { it to set } }.toMap()
    val byId = films.associateBy { it.jwId }
    val seen = mutableSetOf<String>()
    return films.mapNotNull { film ->
        val set = setOf[film.jwId]
        when {
            set == null -> FilmItem(film)
            seen.add(set.id) -> SetItem(set, set.films.mapNotNull { byId[it] }.sortedBy { yearOf(it) ?: Int.MAX_VALUE })
            else -> null
        }
    }
}

/**
 * What the TV shows: the shelf's items in a random order that's fixed for the session's
 * [seed]. Each item's place comes from its own key, so adding or removing a film doesn't
 * reshuffle the rest of the shelf.
 */
fun Shelf.displayItems(seed: Long, yearOf: (ShelfFilm) -> Int? = { it.year }): List<ShelfItem> =
    items(yearOf).sortedBy { shuffleRank(seed, id + "/" + it.key) }

/** Shelves in a random order fixed for the session's [seed]. */
fun List<Shelf>.shuffled(seed: Long): List<Shelf> = sortedBy { shuffleRank(seed, it.id) }

/** A well-mixed number from the seed and a key (SplitMix64 finaliser), for stable shuffling. */
private fun shuffleRank(seed: Long, key: String): Long {
    var z = seed + key.hashCode().toLong() * -0x61c8864680b583ebL
    z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
    z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
    return z xor (z ushr 31)
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
