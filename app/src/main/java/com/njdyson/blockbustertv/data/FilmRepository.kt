package com.njdyson.blockbustertv.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The shelves (assets/collections.json, hard-coded for this prototype) and a local cache of
 * their films' metadata, so the app opens instantly and refreshes availability once a day.
 */
class FilmRepository(context: Context, private val justWatch: JustWatch) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cacheFile = File(context.filesDir, "films.json")

    val collections: List<FilmCollection> = context.assets.open("collections.json").bufferedReader().use {
        json.decodeFromString<CollectionsFile>(it.readText()).collections
    }

    @Serializable
    private data class CollectionsFile(val collections: List<FilmCollection>)

    @Serializable
    private data class Cache(val fetchedAt: Long, val films: Map<String, Film>)

    suspend fun cached(): Map<String, Film> = withContext(Dispatchers.IO) { readCache()?.films.orEmpty() }

    /** True if the cache is missing films or older than a day. */
    suspend fun isStale(): Boolean = withContext(Dispatchers.IO) {
        val cache = readCache() ?: return@withContext true
        val ids = collections.flatMap { c -> c.films.map { it.jwId } }.toSet()
        System.currentTimeMillis() - cache.fetchedAt > REFRESH_MS || !cache.films.keys.containsAll(ids)
    }

    suspend fun refresh(): Map<String, Film> {
        val ids = collections.flatMap { c -> c.films.map { it.jwId } }.distinct()
        val films = justWatch.films(ids).associateBy { it.jwId }
        withContext(Dispatchers.IO) {
            cacheFile.writeText(json.encodeToString(Cache.serializer(), Cache(System.currentTimeMillis(), films)))
        }
        return films
    }

    private fun readCache(): Cache? =
        runCatching { json.decodeFromString(Cache.serializer(), cacheFile.readText()) }.getOrNull()

    private companion object {
        const val REFRESH_MS = 24 * 60 * 60 * 1000L
    }
}
