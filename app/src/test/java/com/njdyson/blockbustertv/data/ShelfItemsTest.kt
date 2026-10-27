package com.njdyson.blockbustertv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ShelfItemsTest {
    private fun film(id: String) = ShelfFilm(id, title = id)
    private val shelf = Shelf("s1", "Boxing", listOf("r1", "other", "r2", "r3", "r4", "r5").map(::film))

    @Test
    fun gatheringMatchesTheServer() {
        // Same case as server/tests/api_test.py: set of r1–r4 gathers at r1's place.
        val withSet = shelf.copy(sets = listOf(FilmSet("set1", "Rocky", listOf("r1", "r2", "r3", "r4")))).gathered("set1")
        assertEquals(listOf("r1", "r2", "r3", "r4", "other", "r5"), withSet.films.map { it.jwId })
        assertEquals(listOf("set:set1", "other", "r5"), withSet.items().map { it.key })
    }

    @Test
    fun setsFoldIntoOneItem() {
        val withSet = shelf.copy(sets = listOf(FilmSet("set1", "Rocky", listOf("r3", "r1", "r2"))))
        val items = withSet.items()
        assertEquals(listOf("set:set1", "other", "r4", "r5"), items.map { it.key })
        // No years known: the set keeps its saved order.
        assertEquals(listOf("r3", "r1", "r2"), (items[0] as SetItem).films.map { it.jwId })
    }

    @Test
    fun shelvesAreAlwaysShuffledStablyAndSetsKeepReleaseOrder() {
        val years = mapOf("f1" to 1983, "f2" to 1977, "f3" to 1980)
        val big = Shelf("s2", "Big", (1..20).map { film("f$it") }, sets = listOf(FilmSet("set1", "Star Wars", listOf("f1", "f2", "f3"))))
        val yearOf = { f: ShelfFilm -> years[f.jwId] }
        val a = big.displayItems(seed = 42, yearOf = yearOf)
        assertEquals(a, big.displayItems(seed = 42, yearOf = yearOf))
        assertNotEquals(a.map { it.key }, big.displayItems(seed = 7, yearOf = yearOf).map { it.key })
        assertNotEquals(big.items(yearOf).map { it.key }, a.map { it.key })
        assertEquals(18, a.size) // 17 films + 1 set
        assertEquals(listOf("f2", "f3", "f1"), (a.single { it is SetItem } as SetItem).films.map { it.jwId }) // 1977, 1980, 1983
        // Removing a film leaves everything else in the same order.
        val fewer = big.copy(films = big.films.filterNot { it.jwId == "f10" }).displayItems(seed = 42, yearOf = yearOf)
        assertEquals(a.map { it.key }.filterNot { it == "f10" }, fewer.map { it.key })
    }

    @Test
    fun shelfOrderIsShuffledPerSeed() {
        val shelves = (1..10).map { Shelf("shelf$it", "Shelf $it", emptyList()) }
        assertEquals(shelves.shuffled(1L), shelves.shuffled(1L))
        assertNotEquals(shelves.shuffled(1L), shelves.shuffled(2L))
        assertEquals(shelves.toSet(), shelves.shuffled(1L).toSet())
    }

    @Test
    fun cleaningDropsMissingFilmsAndEmptySets() {
        val s = shelf.copy(films = shelf.films.filterNot { it.jwId == "r1" }, sets = listOf(FilmSet("a", "A", listOf("r1")), FilmSet("b", "B", listOf("r1", "r2"))))
        assertEquals(listOf(FilmSet("b", "B", listOf("r2"))), s.withCleanSets().sets)
    }
}
