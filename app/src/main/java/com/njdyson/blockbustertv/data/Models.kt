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
