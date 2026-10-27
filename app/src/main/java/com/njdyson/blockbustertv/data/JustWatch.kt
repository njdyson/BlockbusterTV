package com.njdyson.blockbustertv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL

/**
 * JustWatch's GraphQL API (unofficial, no key): metadata, IMDb ratings and UK availability
 * with Fire TV deep links, for many films in one request.
 */
class JustWatch(private val services: Services) {

    suspend fun films(jwIds: List<String>): List<Film> = withContext(Dispatchers.IO) {
        if (jwIds.isEmpty()) return@withContext emptyList()
        val query = jwIds.mapIndexed { i, id -> "f$i: node(id: \"$id\") { $FIELDS }" }.joinToString(" ", "{ ", " }")
        val data = post(query)
        jwIds.indices.mapNotNull { i -> (data["f$i"] as? JsonObject)?.let(::parseFilm) }
    }

    /** Films matching a title search, most popular first. */
    suspend fun search(text: String): List<ShelfFilm> = search(SearchFilters(text = text))

    /**
     * Film search with optional filters that combine: title, a person's films, genre,
     * decade, minimum IMDb rating, a streaming service, and sort order.
     */
    suspend fun search(f: SearchFilters): List<ShelfFilm> = withContext(Dispatchers.IO) {
        if (f.isEmpty) return@withContext emptyList()
        val filter = buildJsonObject {
            put("objectTypes", JsonArray(listOf(JsonPrimitive("MOVIE"))))
            f.text.trim().takeIf { it.isNotEmpty() }?.let { put("searchQuery", it) }
            f.person?.let { put("personId", it.id) }
            f.genre?.let { put("genres", JsonArray(listOf(JsonPrimitive(it)))) }
            f.decade?.let { put("releaseYear", buildJsonObject { put("min", it); put("max", it + 9) }) }
            f.minRating?.let { put("imdbScore", buildJsonObject { put("min", it) }) }
            f.service?.let { id ->
                SERVICE_PACKAGES[id]?.let { put("packages", JsonArray(listOf(JsonPrimitive(it)))) }
                put("monetizationTypes", JsonArray(listOf("FLATRATE", "FREE", "ADS").map(::JsonPrimitive)))
            }
        }
        val query = """query(${'$'}filter: TitleFilter!, ${'$'}sort: PopularTitlesSorting!) {
            popularTitles(country: GB, first: 24, sortBy: ${'$'}sort, filter: ${'$'}filter) {
              edges { node { id ... on Movie { content(country: GB, language: "en") {
                title originalReleaseYear posterUrl externalIds { imdbId } } } } } } }"""
        val variables = buildJsonObject { put("filter", filter); put("sort", f.sort.code) }
        post(query, variables).obj("popularTitles")?.get("edges")?.jsonArray.orEmpty().mapNotNull { edge ->
            val node = edge.jsonObject.obj("node") ?: return@mapNotNull null
            val c = node.obj("content") ?: return@mapNotNull null
            ShelfFilm(
                jwId = node.str("id") ?: return@mapNotNull null,
                imdbId = c.obj("externalIds")?.str("imdbId").orEmpty(),
                title = c.str("title").orEmpty(),
                year = c["originalReleaseYear"]?.jsonPrimitive?.intOrNull,
                poster = image(c.str("posterUrl"), "s166"),
            )
        }
    }

    /** Actors, directors and others matching a name, most popular first. */
    suspend fun people(text: String): List<Person> = withContext(Dispatchers.IO) {
        if (text.trim().length < 3) return@withContext emptyList()
        val query = """query(${'$'}q: String!) { popularPeople(country: GB, first: 6, filter: {searchQuery: ${'$'}q}) {
            edges { node { id ... on Person { content(country: GB, language: "en") { fullName } } } } } }"""
        post(query, buildJsonObject { put("q", text.trim()) }).obj("popularPeople")?.get("edges")?.jsonArray.orEmpty().mapNotNull { edge ->
            val node = edge.jsonObject.obj("node") ?: return@mapNotNull null
            val name = node.obj("content")?.str("fullName") ?: return@mapNotNull null
            Person(node.str("id") ?: return@mapNotNull null, name)
        }
    }

    private fun parseFilm(node: JsonObject): Film {
        val c = node.obj("content")!!
        val offers = node["offers"]?.jsonArray.orEmpty().mapNotNull { parseOffer(it.jsonObject) }
        return Film(
            jwId = node.str("id")!!,
            imdbId = c.obj("externalIds")?.str("imdbId").orEmpty(),
            tmdbId = c.obj("externalIds")?.str("tmdbId"),
            title = c.str("title").orEmpty(),
            year = c["originalReleaseYear"]?.jsonPrimitive?.intOrNull,
            synopsis = c.str("shortDescription"),
            runtimeMinutes = c["runtime"]?.jsonPrimitive?.intOrNull,
            imdbScore = c.obj("scoring")?.get("imdbScore")?.jsonPrimitive?.doubleOrNull,
            ageCertification = c.str("ageCertification"),
            genres = c["genres"]?.jsonArray.orEmpty().mapNotNull { GENRES[it.jsonObject.str("shortName")] },
            poster = image(c.str("posterUrl"), "s332"),
            posterLarge = image(c.str("posterUrl"), "s592"),
            backdrop = image((c["backdrops"] as? JsonArray)?.firstOrNull()?.jsonObject?.str("backdropUrl"), "s1440"),
            offers = dedupe(offers),
        )
    }

    private fun parseOffer(o: JsonObject): Offer? {
        val pkg = o.obj("package") ?: return null
        val service = services.forPackage(pkg.str("technicalName").orEmpty()) ?: return null
        val kind = when (o.str("monetizationType")) {
            "FLATRATE", "FREE", "ADS" -> OfferKind.WATCH
            "RENT" -> OfferKind.RENT
            "BUY" -> OfferKind.BUY
            else -> return null
        }
        return Offer(
            kind = kind,
            serviceId = service.id,
            providerName = pkg.str("clearName").orEmpty(),
            icon = image(pkg.str("icon"), "s100", "png"),
            price = o.str("retailPrice"),
            priceValue = o["retailPriceValue"]?.jsonPrimitive?.doubleOrNull,
            fireTvLink = o.str("fireTv")?.ifBlank { null },
            webUrl = o.str("standardWebURL"),
        )
    }

    /** JustWatch lists each quality (SD/HD/4K) separately; keep one per service, kind and channel. */
    private fun dedupe(offers: List<Offer>): List<Offer> =
        offers.groupBy { Triple(it.kind, it.serviceId, if (it.isChannel) it.providerName else "") }
            .values
            .map { group -> group.sortedWith(compareBy({ it.fireTvLink == null }, { it.priceValue ?: 0.0 })).first() }
            .sortedWith(compareBy({ it.kind }, { services.order(it.serviceId) }, { it.isChannel }))

    private fun post(query: String, variables: JsonObject? = null): JsonObject {
        val conn = URL(API).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("Content-Type", "application/json")
        val body = buildJsonObject { put("query", query); variables?.let { put("variables", it) } }.toString()
        conn.outputStream.use { it.write(body.toByteArray()) }
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        val json = Json.parseToJsonElement(text).jsonObject
        (json["errors"] as? JsonArray)?.let { if (json["data"] == null || json["data"] is JsonNull) error("JustWatch: $it") }
        return json.obj("data") ?: error("JustWatch returned no data")
    }

    companion object {
        private const val API = "https://apis.justwatch.com/graphql"

        /** Our service IDs (services.json) to JustWatch package short names, for "streaming on" filters. */
        private val SERVICE_PACKAGES = mapOf(
            "netflix" to "nfx", "prime" to "amp", "disney" to "dnp", "appletv" to "atp", "plex" to "plx",
            "iplayer" to "bbc", "itvx" to "itv", "channel4" to "al4", "now" to "ntv",
        )

        private const val FIELDS = """id ... on Movie {
            content(country: GB, language: "en") {
              title originalReleaseYear shortDescription runtime ageCertification
              genres { shortName } scoring { imdbScore } externalIds { imdbId tmdbId }
              posterUrl backdrops { backdropUrl }
            }
            offers(country: GB, platform: WEB) {
              monetizationType retailPrice(language: "en") retailPriceValue standardWebURL
              fireTv: deeplinkURL(platform: FIRE_TV)
              package { technicalName clearName icon }
            }
          }"""

        private val GENRES = mapOf(
            "act" to "Action", "ani" to "Animation", "cmy" to "Comedy", "crm" to "Crime",
            "doc" to "Documentary", "drm" to "Drama", "fnt" to "Fantasy", "fml" to "Family",
            "hst" to "History", "hrr" to "Horror", "msc" to "Music", "rma" to "Romance",
            "scf" to "Sci-Fi", "spt" to "Sport", "trl" to "Thriller", "war" to "War", "wsn" to "Western",
        )

        /** JustWatch image paths are templates: /poster/123/{profile}/name.{format}. */
        fun image(path: String?, profile: String, format: String = "jpg"): String? =
            path?.let { "https://images.justwatch.com" + it.replace("{profile}", profile).replace("{format}", format) }

        private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
        private fun JsonObject.str(key: String): String? = (this[key] as? JsonElement)?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull
    }
}
