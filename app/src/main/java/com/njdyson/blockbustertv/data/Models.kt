package com.njdyson.blockbustertv.data

import kotlinx.serialization.Serializable

enum class OfferKind { WATCH, RENT, BUY }

/** One way to watch a film: a service plus how (included, rent or buy). */
@Serializable
data class Offer(
    val kind: OfferKind,
    val serviceId: String,
    /** JustWatch's name for the exact package, e.g. "HBO Max Amazon Channel". */
    val providerName: String,
    val icon: String?,
    val price: String?,
    val priceValue: Double?,
    val fireTvLink: String?,
    val webUrl: String?,
) {
    val isChannel: Boolean get() = providerName.contains("Channel", ignoreCase = true)
}

@Serializable
data class Film(
    val jwId: String,
    val imdbId: String,
    val tmdbId: String?,
    val title: String,
    val year: Int?,
    val synopsis: String?,
    val runtimeMinutes: Int?,
    val imdbScore: Double?,
    val ageCertification: String?,
    val genres: List<String>,
    val poster: String?,
    val posterLarge: String?,
    val backdrop: String?,
    val offers: List<Offer>,
) {
    val watchOffers: List<Offer> get() = offers.filter { it.kind == OfferKind.WATCH }

    /** Services that include the film, for the icons on the box. */
    val watchServices: List<Offer> get() = watchOffers.distinctBy { it.serviceId }

    val cheapestRent: Offer? get() = offers.filter { it.kind == OfferKind.RENT }.minByOrNull { it.priceValue ?: Double.MAX_VALUE }
}

@Serializable
data class CollectionEntry(val imdbId: String, val jwId: String, val title: String)

@Serializable
data class FilmCollection(val name: String, val films: List<CollectionEntry>)

/** Someone in films (actor, director…), from JustWatch's people search; id like "tp1725". */
@Serializable
data class Person(val id: String, val name: String)

enum class SearchSort(val code: String, val label: String) {
    POPULAR("POPULAR", "Popular"),
    RATING("IMDB_SCORE", "Top rated"),
    NEWEST("RELEASE_YEAR", "Newest"),
    AZ("ALPHABETICAL", "A–Z"),
}

/** What to search for: any combination, from just a title to "80s sci-fi on Netflix". */
data class SearchFilters(
    val text: String = "",
    val person: Person? = null,
    val genre: String? = null,
    val decade: Int? = null,
    val minRating: Double? = null,
    val service: String? = null,
    val sort: SearchSort = SearchSort.POPULAR,
) {
    val isEmpty: Boolean
        get() = text.isBlank() && person == null && genre == null && decade == null && minRating == null && service == null

    companion object {
        /** JustWatch genre codes and names. */
        val GENRES = listOf(
            "act" to "Action", "cmy" to "Comedy", "drm" to "Drama", "scf" to "Sci-Fi", "trl" to "Thriller",
            "hrr" to "Horror", "rma" to "Romance", "fml" to "Family", "ani" to "Animation", "crm" to "Crime",
            "fnt" to "Fantasy", "doc" to "Documentary", "war" to "War", "wsn" to "Western", "msc" to "Music",
            "hst" to "History", "spt" to "Sport",
        )
        val DECADES = (1950..2020 step 10).toList()
        val RATINGS = listOf(6.0, 7.0, 8.0)
    }
}
