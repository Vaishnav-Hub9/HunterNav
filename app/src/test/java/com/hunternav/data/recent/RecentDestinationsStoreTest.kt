package com.hunternav.data.recent

import com.hunternav.domain.model.Coordinate
import com.hunternav.domain.model.Destination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recents must be trustworthy: exact selected coordinates (lat/lon order preserved),
 * most-recent-first, deduped, capped, persistent, and crash-proof against bad storage.
 */
class RecentDestinationsStoreTest {

    private val backend = InMemoryPreferenceBackend()
    private fun store() = RecentDestinationsStore(backend)

    private val vasavi = Destination(
        Coordinate(17.3807982, 78.3820908),
        title = "Vasavi College of Engineering",
        subtitle = "Uppal, Hyderabad",
    )
    private val charminar = Destination(Coordinate(17.3616, 78.4747), title = "Charminar")
    private val gachibowli = Destination(Coordinate(17.4401, 78.3489), title = "Gachibowli")

    @Test
    fun `records keep the exact selected coordinate in latitude longitude order`() {
        val s = store()
        s.record(vasavi)

        val r = s.recent().single()
        assertEquals(17.3807982, r.coordinate.latitude, 0.0)
        assertEquals(78.3820908, r.coordinate.longitude, 0.0)
        assertEquals("Vasavi College of Engineering", r.title)
        assertEquals("Uppal, Hyderabad", r.subtitle)
    }

    @Test
    fun `recents are most-recent-first and deduped by coordinate`() {
        val s = store()
        s.record(charminar)
        s.record(gachibowli)
        s.record(vasavi)
        assertEquals(
            listOf("Vasavi College of Engineering", "Gachibowli", "Charminar"),
            s.recent().map { it.title },
        )

        // Re-selecting the same coordinate moves it to the front without duplicating it.
        s.record(charminar)
        assertEquals(
            listOf("Charminar", "Vasavi College of Engineering", "Gachibowli"),
            s.recent().map { it.title },
        )
    }

    @Test
    fun `history is capped and persists across store instances`() {
        val s = store()
        val many = List(RecentDestinationsStore.MAX_ENTRIES + 3) { i ->
            Destination(Coordinate(17.20 + i * 0.01, 78.30 + i * 0.01), title = "Place $i")
        }
        many.forEach { s.record(it) }

        assertEquals(RecentDestinationsStore.MAX_ENTRIES, s.recent().size)
        // A fresh store over the same backend reads the same entries (SharedPreferences path).
        assertEquals(s.recent(), store().recent())
    }

    @Test
    fun `corrupted storage degrades to empty instead of crashing`() {
        backend.write("not json {{{")
        assertTrue(store().recent().isEmpty())

        // …and recording works again afterwards.
        store().record(charminar)
        assertEquals(listOf("Charminar"), store().recent().map { it.title })
    }

    @Test
    fun `out of range or non finite coordinates are never stored`() {
        val s = store()
        s.record(Destination(Coordinate(91.0, 78.5), title = "Bad lat"))
        s.record(Destination(Coordinate(17.4, 181.0), title = "Bad lon"))
        s.record(Destination(Coordinate(Double.NaN, 78.5), title = "NaN"))
        assertTrue(s.recent().isEmpty())

        s.record(vasavi)
        assertEquals(listOf("Vasavi College of Engineering"), s.recent().map { it.title })
    }
}
