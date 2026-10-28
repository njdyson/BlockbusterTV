package com.njdyson.blockbustertv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeasonsLabelTest {
    @Test
    fun labels() {
        assertEquals("Season 1", seasonsLabel(listOf(1)))
        assertEquals("Seasons 1–3", seasonsLabel(listOf(1, 2, 3)))
        assertEquals("Seasons 1–2, 5", seasonsLabel(listOf(1, 2, 5)))
        assertEquals("Seasons 2, 4", seasonsLabel(listOf(2, 4)))
    }

    @Test
    fun seriesIds() {
        assertTrue(isSeriesId("ts131317"))
        assertFalse(isSeriesId("tm92641"))
    }
}
