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
    fun setsKeepTheirOwnOrderAndFlattenBack() {
        val withSet = shelf.copy(sets = listOf(FilmSet("set1", "Rocky", listOf("r3", "r1", "r2"))))
        val items = withSet.items()
        assertEquals(listOf("set:set1", "other", "r4", "r5"), items.map { it.key })
        assertEquals(listOf("r3", "r1", "r2"), (items[0] as SetItem).films.map { it.jwId })
        assertEquals(listOf("r3", "r1", "r2", "other", "r4", "r5"), items.flatOrder())
    }

    @Test
    fun shuffleIsStablePerSeedAndKeepsSetsWhole() {
        val big = Shelf("s2", "Big", (1..20).map { film("f$it") }, sets = listOf(FilmSet("set1", "Trilogy", listOf("f1", "f2", "f3"))), shuffle = true)
        val a = big.displayItems(seed = 42)
        assertEquals(a, big.displayItems(seed = 42))
        assertNotEquals(a.map { it.key }, big.displayItems(seed = 7).map { it.key })
        assertEquals(18, a.size) // 17 films + 1 set
        assertEquals(listOf("f1", "f2", "f3"), (a.single { it is SetItem } as SetItem).films.map { it.jwId })
        assertEquals(big.items(), big.copy(shuffle = false).displayItems(seed = 42))
    }

    @Test
    fun cleaningDropsMissingFilmsAndEmptySets() {
        val s = shelf.copy(films = shelf.films.filterNot { it.jwId == "r1" }, sets = listOf(FilmSet("a", "A", listOf("r1")), FilmSet("b", "B", listOf("r1", "r2"))))
        assertEquals(listOf(FilmSet("b", "B", listOf("r2"))), s.withCleanSets().sets)
    }
}
