package com.njdyson.blockbustertv.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class StreamingService(
    val id: String,
    val name: String,
    val match: List<String>,
    val `package`: String,
    val launch: String,
) {
    val opensTitle: Boolean get() = launch == "deeplink"
}

@Serializable
private data class ServicesFile(val services: List<StreamingService>)

/** The services from assets/services.json, in display order. */
class Services(context: Context) {
    val all: List<StreamingService> = context.assets.open("services.json").bufferedReader().use {
        Json { ignoreUnknownKeys = true }.decodeFromString<ServicesFile>(it.readText()).services
    }

    fun byId(id: String): StreamingService? = all.firstOrNull { it.id == id }

    /** Maps a JustWatch package technicalName (e.g. "amazonhbomax") to one of our services. */
    fun forPackage(technicalName: String): StreamingService? =
        all.firstOrNull { service -> service.match.any { technicalName.startsWith(it) } }

    fun order(id: String): Int = all.indexOfFirst { it.id == id }.let { if (it < 0) Int.MAX_VALUE else it }
}
