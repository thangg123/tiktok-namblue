package com.namblue.live

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure list-logic tests for [WatchHistory] (no SharedPreferences needed). */
class WatchHistoryTest {

    @Test
    fun `adding a new username puts it first`() {
        assertEquals(listOf("c", "a", "b"), WatchHistory.added(listOf("a", "b"), "c"))
    }

    @Test
    fun `re-watching an existing username moves it to the front without duplicating`() {
        assertEquals(listOf("b", "a", "c"), WatchHistory.added(listOf("a", "b", "c"), "b"))
    }

    @Test
    fun `duplicates are matched case-insensitively and the newest spelling wins`() {
        assertEquals(listOf("NamBlue", "x"), WatchHistory.added(listOf("namblue", "x"), "NamBlue"))
    }

    @Test
    fun `history is capped at the max size dropping the oldest`() {
        val full = (1..WatchHistory.MAX_ENTRIES).map { "u$it" }
        val result = WatchHistory.added(full, "new")
        assertEquals(WatchHistory.MAX_ENTRIES, result.size)
        assertEquals("new", result.first())
        assertEquals("u${WatchHistory.MAX_ENTRIES - 1}", result.last())
    }

    @Test
    fun `blank username is ignored`() {
        assertEquals(listOf("a"), WatchHistory.added(listOf("a"), "  "))
    }

    @Test
    fun `removing deletes only that username`() {
        assertEquals(listOf("a", "c"), WatchHistory.removed(listOf("a", "b", "c"), "b"))
    }

    @Test
    fun `removing a missing username leaves the list unchanged`() {
        assertEquals(listOf("a"), WatchHistory.removed(listOf("a"), "zzz"))
    }

    @Test
    fun `encode then decode round-trips and skips empty lines`() {
        val list = listOf("a", "b.c", "d_e")
        assertEquals(list, WatchHistory.decode(WatchHistory.encode(list)))
        assertEquals(emptyList<String>(), WatchHistory.decode(null))
        assertEquals(listOf("a", "b"), WatchHistory.decode("a\n\n b \n"))
    }
}
