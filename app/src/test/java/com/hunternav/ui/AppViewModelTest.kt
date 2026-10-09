package com.hunternav.ui

import android.content.ContextWrapper
import com.hunternav.core.result.AppErrorKind
import com.hunternav.core.result.AppResult
import com.hunternav.di.AppContainer
import com.hunternav.domain.model.Coordinate
import com.hunternav.domain.model.Destination
import com.hunternav.domain.model.LocationData
import com.hunternav.domain.model.Route
import com.hunternav.domain.model.RouteLeg
import com.hunternav.domain.repository.GeocodingProvider
import com.hunternav.domain.repository.RoutingProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Destination selection and Route Preview state (spec §3, §6, §13, §18):
 *  - the shared AppViewModel keeps the chosen destination *with coordinates* (the old bug was
 *    Route Preview rendering "YOUR DESTINATION —" because each screen had its own instance),
 *  - "Start Navigation" is enabled only when a route actually exists,
 *  - every failure produces a visible explanation instead of silence.
 */
class AppViewModelTest {

    /** Fake router: counts calls, result swappable per test. */
    private class FakeRoutingProvider(var result: AppResult<List<Route>>) : RoutingProvider {
        var calls = 0

        override suspend fun getRoutes(
            origin: Coordinate,
            destination: Coordinate,
            alternatives: Boolean,
        ): AppResult<List<Route>> {
            calls++
            return result
        }
    }

    /** Fake geocoder for the map long-press (reverse) path. */
    private class FakeGeocoder : GeocodingProvider {
        var reverseCalls = 0
        var reverseResult: AppResult<Destination> = AppResult.Success(
            Destination(Coordinate(17.4000, 78.5000), title = "Nearest Place", subtitle = "Hyderabad"),
        )

        override suspend fun search(query: String, near: Coordinate?, limit: Int): AppResult<List<Destination>> =
            AppResult.Success(emptyList())

        override suspend fun reverse(coordinate: Coordinate): AppResult<Destination> {
            reverseCalls++
            return reverseResult
        }
    }

    @Before
    fun setUp() {
        // AppViewModel's viewModelScope runs on Dispatchers.Main; back it with the test scheduler.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val charminar = Destination(Coordinate(17.3616, 78.4747), title = "Charminar", subtitle = "Hyderabad")

    private fun route(distanceMeters: Double = 445.0) = Route(
        distanceMeters = distanceMeters,
        durationSeconds = 90.0,
        geometry = listOf(Coordinate(17.3850, 78.4867), Coordinate(17.3890, 78.4867)),
        legs = listOf(RouteLeg(distanceMeters = distanceMeters, durationSeconds = 90.0, steps = emptyList())),
    )

    private fun location(lat: Double = 17.3850, lon: Double = 78.4867) = LocationData(
        latitude = lat,
        longitude = lon,
        accuracy = 8f,
        speed = 6f,
        bearing = 0f,
        timestamp = 0L,
    )

    private fun container(
        routing: FakeRoutingProvider = FakeRoutingProvider(AppResult.Success(listOf(route()))),
        geocoder: GeocodingProvider = FakeGeocoder(),
    ) = AppContainer(ContextWrapper(null), routing, geocoder)

    // -----------------------------------------------------------------
    // Destination selection (spec §3)
    // -----------------------------------------------------------------

    @Test
    fun `selected search destination is kept in shared state with coordinates`() = runTest {
        val vm = AppViewModel(container())
        vm.onDestinationSelected(charminar)
        advanceUntilIdle()

        val shown = vm.destination.value
        assertNotNull("a selected destination must be present in shared state", shown)
        assertEquals("Charminar", shown!!.title)
        assertEquals(17.3616, shown.coordinate.latitude, 1e-9)
        assertEquals(78.4747, shown.coordinate.longitude, 1e-9)
    }

    @Test
    fun `map long press pins coordinates immediately then upgrades the label`() = runTest {
        val geocoder = FakeGeocoder()
        val vm = AppViewModel(container(geocoder = geocoder))

        vm.onMapLongPress(Coordinate(17.4000, 78.5000))
        // Coordinates are available synchronously — routing never waits on reverse geocoding.
        assertEquals(17.4000, vm.destination.value!!.coordinate.latitude, 1e-9)
        assertEquals(78.5000, vm.destination.value!!.coordinate.longitude, 1e-9)

        advanceUntilIdle()
        assertEquals("Nearest Place", vm.destination.value!!.title)
        // The upgraded label keeps the pressed pin's coordinates.
        assertEquals(17.4000, vm.destination.value!!.coordinate.latitude, 1e-9)
        assertEquals(1, geocoder.reverseCalls)
    }

    // -----------------------------------------------------------------
    // Route preview state (spec §6, §13)
    // -----------------------------------------------------------------

    @Test
    fun `successful route fetch populates preview state and enables start navigation`() = runTest {
        val primary = route()
        val alternative = route(distanceMeters = 520.0)
        val routing = FakeRoutingProvider(AppResult.Success(listOf(primary, alternative)))
        val vm = AppViewModel(container(routing = routing))

        vm.onDestinationSelected(charminar)
        vm.engine.onLocationUpdate(location()) // GPS fix arrives before routing
        vm.prepareRoute()
        advanceUntilIdle()

        assertNull("no error expected", vm.routeError.value)
        assertEquals(2, vm.routes.value.size)
        // Start Navigation is enabled iff a selected route exists while not loading.
        assertEquals(primary, vm.selectedRoute.value)
        // The destination still renders with a real name — never "YOUR DESTINATION —".
        assertEquals("Charminar", vm.destination.value?.title)
        assertEquals(17.3616, vm.destination.value!!.coordinate.latitude, 1e-9)
        assertEquals(1, routing.calls)
    }

    @Test
    fun `route preview without destination fails with explanation not silence`() = runTest {
        val vm = AppViewModel(container())
        vm.prepareRoute()
        advanceUntilIdle()

        val error = vm.routeError.value
        assertNotNull("a missing destination must produce a visible error", error)
        assertTrue("unexpected message: $error", error!!.contains("Destination"))
        assertNull(vm.selectedRoute.value)
        assertTrue(vm.routes.value.isEmpty())
    }

    @Test
    fun `route preview without gps fix explains the location problem`() = runTest {
        val vm = AppViewModel(container())
        vm.onDestinationSelected(charminar)
        vm.prepareRoute()
        advanceUntilIdle()

        val error = vm.routeError.value
        assertNotNull("a missing origin must produce a visible error", error)
        assertTrue("unexpected message: $error", error!!.contains("location"))
        assertTrue(vm.routes.value.isEmpty())
        assertNull(vm.selectedRoute.value)
    }

    @Test
    fun `routing failure disables start navigation with a readable error`() = runTest {
        val routing = FakeRoutingProvider(AppResult.Failure(AppErrorKind.NO_ROUTE, "NoRoute"))
        val vm = AppViewModel(container(routing = routing))

        vm.onDestinationSelected(charminar)
        vm.engine.onLocationUpdate(location())
        vm.prepareRoute()
        advanceUntilIdle()

        assertTrue(vm.routes.value.isEmpty())
        assertNull("Start Navigation must be disabled (no selected route)", vm.selectedRoute.value)
        assertNotNull("the failure must be explained on screen", vm.routeError.value)
        assertEquals(1, routing.calls)
    }

    @Test
    fun `start navigation without a selected route is a safe no-op`() = runTest {
        val vm = AppViewModel(container())
        vm.startNavigation()
        advanceUntilIdle()

        assertEquals(AppPhase.IDLE, vm.phase.value)
    }
}
