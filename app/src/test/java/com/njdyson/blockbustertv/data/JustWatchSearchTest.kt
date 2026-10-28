package com.njdyson.blockbustertv.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The TV's film search against the live JustWatch API. Needs the network, so it only
 * runs when BBTV_TEST_JUSTWATCH=1 is set.
 */
class JustWatchSearchTest {
    private val justWatch by lazy {
        JustWatch(Services(Services.parse(File("src/main/assets/services.json").readText())))
    }

    @Test
    fun peopleAndTheirFilms() = runBlocking {
        assumeTrue(System.getenv("BBTV_TEST_JUSTWATCH") == "1")
        val nolan = justWatch.people("christopher nolan").first { it.name == "Christopher Nolan" }
        val films = justWatch.search(SearchFilters(person = nolan, sort = SearchSort.RATING)).map { it.title }
        assertTrue(films.toString(), films.containsAll(listOf("Inception", "Interstellar")))
    }

    @Test
    fun filtersWithoutText() = runBlocking {
        assumeTrue(System.getenv("BBTV_TEST_JUSTWATCH") == "1")
        val films = justWatch.search(SearchFilters(genre = "scf", decade = 1980, minRating = 7.0, service = "netflix"))
        assertTrue(films.isNotEmpty() && films.all { (it.year ?: 0) in 1980..1989 })
        assertTrue(justWatch.search(SearchFilters()).isEmpty())
    }

    @Test
    fun seriesSearchAndSeasons() = runBlocking {
        assumeTrue(System.getenv("BBTV_TEST_JUSTWATCH") == "1")
        val shows = justWatch.search(SearchFilters(text = "slow horses", series = true))
        assertTrue(shows.toString(), shows.first().title == "Slow Horses" && shows.all { isSeriesId(it.jwId) })
        val show = justWatch.films(listOf(shows.first().jwId)).single()
        assertTrue(show.isSeries && (show.seasonCount ?: 0) >= 5 && show.watchOffers.isNotEmpty())
    }
}
