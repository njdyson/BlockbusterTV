package com.njdyson.blockbustertv.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * The TV's server client against a real PocketBase running server/ from this repo.
 * Skipped unless BBTV_TEST_SERVER, BBTV_TEST_ADMIN and BBTV_TEST_ADMIN_PASSWORD are set, e.g.
 * BBTV_TEST_SERVER=http://127.0.0.1:8090 ./gradlew testDebugUnitTest
 */
class AccountServerTest {
    private val server = System.getenv("BBTV_TEST_SERVER")
    private lateinit var adminToken: String
    private lateinit var userId: String
    private lateinit var userToken: String

    @Before
    fun createUser() {
        assumeTrue("set BBTV_TEST_SERVER to run", server != null)
        adminToken = post("/api/collections/_superusers/auth-with-password", """{"identity":"${System.getenv("BBTV_TEST_ADMIN")}","password":"${System.getenv("BBTV_TEST_ADMIN_PASSWORD")}"}""")["token"]!!
        val email = "tv-${UUID.randomUUID().toString().take(8)}@test.local"
        userId = post("/api/collections/users/records", """{"email":"$email","password":"testpass123","passwordConfirm":"testpass123"}""", adminToken)["id"]!!
        userToken = post("/api/collections/users/auth-with-password", """{"identity":"$email","password":"testpass123"}""")["token"]!!
    }

    @After
    fun deleteUser() {
        if (::userId.isInitialized) send("DELETE", "/api/collections/users/records/$userId", null, adminToken)
    }

    @Test
    fun linkImportEditSyncAndUnlink() {
        // The TV asks for a code and polls; the phone app approves it.
        val started = Pairing.start(server!!, "Test Fire Stick")
        assertEquals("pending", Pairing.poll(server, started).status)
        post("/api/bbtv/pair/approve", """{"code":"${started.code}"}""", userToken)
        val key = Pairing.poll(server, started).deviceKey!!
        assertEquals(Pairing.approveUrl(server, started.code), "$server/#pair=${started.code}")

        // First link uploads the TV's shelves into the empty account…
        val local = ShelvesFile(
            shelves = listOf(Shelf("aaaa1111", "Saturday", listOf(ShelfFilm("tm92641", "tt1375666", "Inception", 2010)))),
            owned = listOf("tm92641"),
        )
        val remote = RemoteLibrary(server, key)
        assertEquals(local, remote.importIfEmpty(local))
        // …and a second TV linking later keeps the account's shelves.
        assertEquals("Saturday", remote.importIfEmpty(ShelvesFile(shelves = emptyList())).shelves.single().name)

        // Edits in the shape ShelfStore sends them, including a TV-chosen shelf ID.
        remote.op(buildJsonObject { put("op", "addShelf"); put("id", "bbbb2222"); put("name", "Family") })
        val after = remote.op(buildJsonObject { put("op", "moveShelf"); put("shelf", "bbbb2222"); put("delta", -1) })
        assertEquals(listOf("Family", "Saturday"), after.shelves.map { it.name })
        assertEquals(after, remote.library())

        // Sets and shuffle, as ShelfStore sends them: the server's copy matches the TV's own logic.
        remote.op(buildJsonObject { put("op", "addFilm"); put("shelf", "aaaa1111"); put("film", buildJsonObject { put("jwId", "tm10"); put("title", "The Matrix") }) })
        remote.op(buildJsonObject {
            put("op", "createSet"); put("shelf", "aaaa1111"); put("id", "set00001"); put("name", "Mind-benders")
            put("films", JsonArray(listOf("tm10", "tm92641").map { JsonPrimitive(it) }))
        })
        val shuffled = remote.op(buildJsonObject { put("op", "setShuffle"); put("shelf", "aaaa1111"); put("shuffle", true) })
        val saturday = shuffled.shelves.single { it.id == "aaaa1111" }
        assertEquals(listOf(FilmSet("set00001", "Mind-benders", listOf("tm10", "tm92641"))), saturday.sets)
        assertTrue(saturday.shuffle)
        assertEquals(listOf("set:set00001"), saturday.items().map { it.key })

        // Unlinking stops the key working.
        remote.forget()
        try {
            remote.library()
            throw AssertionError("expected UnlinkedException")
        } catch (e: UnlinkedException) {
            assertTrue(true)
        }
    }

    private fun post(path: String, body: String, token: String? = null): Map<String, String?> =
        Json.parseToJsonElement(send("POST", path, body, token)).jsonObject.mapValues { (_, v) -> runCatching { v.jsonPrimitive.content }.getOrNull() }

    private fun send(method: String, path: String, body: String?, token: String?): String {
        val conn = URL(server + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.setRequestProperty("Content-Type", "application/json")
        token?.let { conn.setRequestProperty("Authorization", it) }
        if (body != null) {
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray()) }
        }
        val stream = if (conn.responseCode < 400) conn.inputStream else conn.errorStream
        return stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    }
}
