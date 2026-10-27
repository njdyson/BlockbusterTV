package com.njdyson.blockbustertv.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * A local cache of film metadata and availability, so the app opens instantly and
 * refreshes from JustWatch once a day (or straight away for newly added films).
 */
class FilmRepository(context: Context, private val justWatch: JustWatch) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cacheFile = File(context.filesDir, "films.json")

    @Serializable
    private data class Cache(val fetchedAt: Long, val films: Map<String, Film>)

    suspend fun cached(): Map<String, Film> = withContext(Dispatchers.IO) { readCache()?.films.orEmpty() }

    suspend fun isStale(): Boolean = withContext(Dispatchers.IO) {
        val cache = readCache() ?: return@withContext true
        System.currentTimeMillis() - cache.fetchedAt > REFRESH_MS
    }

    /** Re-fetches every film in [ids]. */
    suspend fun refresh(ids: Collection<String>): Map<String, Film> {
        val films = justWatch.films(ids.distinct()).associateBy { it.jwId }
        write(Cache(System.currentTimeMillis(), films))
        return films
    }

    /** Fetches only films missing from the cache, keeping the cache's refresh time. */
    suspend fun addMissing(ids: Collection<String>): Map<String, Film> {
        val cache = withContext(Dispatchers.IO) { readCache() } ?: Cache(System.currentTimeMillis(), emptyMap())
        val missing = ids.filterNot { it in cache.films }.distinct()
        if (missing.isEmpty()) return cache.films
        val merged = cache.films + justWatch.films(missing).associateBy { it.jwId }
        write(cache.copy(films = merged))
        return merged
    }

    private suspend fun write(cache: Cache) = withContext(Dispatchers.IO) {
        cacheFile.writeText(json.encodeToString(Cache.serializer(), cache))
    }

    private fun readCache(): Cache? =
        runCatching { json.decodeFromString(Cache.serializer(), cacheFile.readText()) }.getOrNull()

    private companion object {
        const val REFRESH_MS = 24 * 60 * 60 * 1000L
    }
}
