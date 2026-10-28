package com.njdyson.blockbustertv.data

import com.njdyson.blockbustertv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.net.URL

/** Trailers come from TMDB, the one thing JustWatch doesn't supply. Needs a free API key. */
object Tmdb {
    val enabled: Boolean get() = BuildConfig.TMDB_API_KEY.isNotBlank()

    /** YouTube video ID of the film's (or series') trailer, preferring official English trailers. */
    suspend fun trailerId(tmdbId: String, series: Boolean = false): String? = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext null
        val url = "https://api.themoviedb.org/3/${if (series) "tv" else "movie"}/$tmdbId/videos?api_key=${BuildConfig.TMDB_API_KEY}"
        val text = URL(url).openStream().bufferedReader().use { it.readText() }
        Json.parseToJsonElement(text).jsonObject["results"]?.jsonArray.orEmpty()
            .map { it.jsonObject }
            .filter { it["site"]?.jsonPrimitive?.content == "YouTube" }
            .sortedWith(
                compareBy(
                    { it["type"]?.jsonPrimitive?.content != "Trailer" },
                    { it["official"]?.jsonPrimitive?.booleanOrNull != true },
                    { it["iso_639_1"]?.jsonPrimitive?.content != "en" },
                ),
            )
            .firstOrNull()?.get("key")?.jsonPrimitive?.content
    }
}
