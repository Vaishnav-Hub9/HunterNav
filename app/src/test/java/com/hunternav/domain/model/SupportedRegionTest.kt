package com.hunternav.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Supported operating area (spec P3): Telangana maximum, Hyderabad default. */
class SupportedRegionTest {

    @Test
    fun `hyderabad destinations are inside the supported region`() {
        listOf(
            Coordinate(17.3616, 78.4747), // Charminar
            Coordinate(17.3969, 78.3208), // Vasavi College (Ibrahimbagh)
            Coordinate(17.44008, 78.44823), // Vasavi College (EC campus)
            Coordinate(17.4401, 78.3489), // Gachibowli
            Coordinate(17.3850, 78.4867), // Hyderabad city center (app default)
        ).forEach { coordinate ->
            assertTrue("Hyderabad point $coordinate must be supported", SupportedRegion.isSupported(coordinate))
            assertTrue(SupportedRegion.HYDERABAD.contains(coordinate))
        }
    }

    @Test
    fun `other telangana cities are inside the supported region`() {
        listOf(
            Coordinate(17.9689, 79.5941), // Warangal
            Coordinate(16.5771, 79.3266), // Nagarjuna Sagar
            Coordinate(18.4386, 79.1288), // Karimnagar
        ).forEach { coordinate ->
            assertTrue("Telangana point $coordinate must be supported", SupportedRegion.isSupported(coordinate))
        }
    }

    @Test
    fun `destinations outside telangana are rejected`() {
        listOf(
            Coordinate(12.9716, 77.5946), // Bangalore
            Coordinate(13.0827, 80.2707), // Chennai
            Coordinate(19.0760, 72.8777), // Mumbai
            Coordinate(28.6139, 77.2090), // Delhi
            Coordinate(22.5726, 88.3639), // Kolkata
        ).forEach { coordinate ->
            assertFalse("Out-of-region point $coordinate must be rejected", SupportedRegion.isSupported(coordinate))
        }
    }

    @Test
    fun `hyderabad default area is contained within the telangana maximum area`() {
        assertTrue(SupportedRegion.TELANGANA.contains(SupportedRegion.HYDERABAD.center))
        assertTrue(SupportedRegion.TELANGANA.contains(Coordinate(SupportedRegion.HYDERABAD.minLatitude, SupportedRegion.HYDERABAD.minLongitude)))
        assertTrue(SupportedRegion.TELANGANA.contains(Coordinate(SupportedRegion.HYDERABAD.maxLatitude, SupportedRegion.HYDERABAD.maxLongitude)))
    }

    @Test
    fun `rejection message names the location and the supported area`() {
        val message = SupportedRegion.unsupportedDestinationMessage("Bangalore")
        assertTrue(message.contains("Bangalore"))
        assertTrue(message.contains(SupportedRegion.NAME))
        // Null title still yields a readable message.
        assertTrue(SupportedRegion.unsupportedDestinationMessage(null).contains(SupportedRegion.NAME))
    }

    @Test
    fun `padded bounds grow symmetrically`() {
        val padded = SupportedRegion.TELANGANA.padded(0.5)
        assertTrue(padded.minLatitude < SupportedRegion.TELANGANA.minLatitude)
        assertTrue(padded.maxLongitude > SupportedRegion.TELANGANA.maxLongitude)
    }
}
