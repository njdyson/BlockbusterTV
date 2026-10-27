package com.njdyson.blockbustertv.linklab

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One service's set of candidate links for a single film; see tools/fetch_link_tests.py. */
@Serializable
data class ServiceTest(
    val service: String,
    val serviceName: String,
    val film: String,
    val imdbId: String,
    val packages: List<String>,
    val links: List<LinkSpec>,
)

@Serializable
data class LinkSpec(
    val label: String,
    val uri: String,
    val `package`: String? = null,
)

@Serializable
private data class LinkTestFile(val region: String, val tests: List<ServiceTest>)

fun loadLinkTests(context: Context): List<ServiceTest> {
    val text = context.assets.open("link_tests.json").bufferedReader().use { it.readText() }
    return Json { ignoreUnknownKeys = true }.decodeFromString<LinkTestFile>(text).tests
}

fun linkId(test: ServiceTest, index: Int) = "${test.service}#${index + 1}"
