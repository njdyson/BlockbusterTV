package com.njdyson.blockbustertv.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlexTest {
    private fun film(json: String) = Plex.parseFilm(Json.parseToJsonElement(json).jsonObject)

    @Test
    fun readsImdbIdFromCurrentAgent() {
        val f = film("""{"ratingKey":"101","title":"The Big Short","year":2015,"guid":"plex://movie/5d776b679ab544002150af34",
            "Guid":[{"id":"imdb://tt1596363"},{"id":"tmdb://318846"}]}""")!!
        assertEquals("tt1596363", f.imdbId)
        assertEquals("5d776b679ab544002150af34", f.plexId)
        assertEquals(2015, f.year)
    }

    @Test
    fun readsImdbIdFromLegacyAgent() {
        val f = film("""{"ratingKey":"7","title":"Heat","year":1995,"guid":"com.plexapp.agents.imdb://tt0113277?lang=en"}""")!!
        assertEquals("tt0113277", f.imdbId)
        assertNull(f.plexId)
    }

    @Test
    fun matchesByImdbThenTitleAndYear() {
        val library = PlexLibrary(
            "NAS", "abc",
            listOf(
                PlexFilm("1", "Heat", 1995, imdbId = "tt0113277"),
                PlexFilm("2", "Amélie & Friends", 2002),
            ),
            0,
        )
        val index = PlexIndex(library)
        assertEquals("1", index.find("tt0113277", "Whatever", null)?.ratingKey)
        // A remake with the same title but another IMDb ID isn't the same film.
        assertNull(index.find("tt9999999", "Heat", 1995))
        // Films Plex couldn't identify match on title and year, a year either way.
        assertEquals("2", index.find("tt1111111", "amélie and friends", 2003)?.ratingKey)
        assertNull(index.find(null, "Amélie & Friends", 2010))
    }

    @Test
    fun addressesToTry() {
        assertEquals(listOf("http://192.168.1.20:32400", "https://192.168.1.20:32400"), Plex.candidateUrls("192.168.1.20"))
        assertEquals(listOf("http://nas.local:8080", "https://nas.local:8080"), Plex.candidateUrls(" nas.local:8080/ "))
        assertEquals(listOf("https://x.plex.direct:32400"), Plex.candidateUrls("https://x.plex.direct:32400/"))
    }
}
