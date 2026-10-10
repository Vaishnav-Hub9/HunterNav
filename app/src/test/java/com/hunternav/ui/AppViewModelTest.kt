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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** Fake router: counts calls, records requested destinations, result swappable per test. */
    private class FakeRoutingProvider(var result: AppResult<List<Route>>) : RoutingProvider {
        var calls = 0
        val requestedDestinations = mutableListOf<Coordinate>()

        override suspend fun getRoutes(
            origin: Coordinate,
            destination: Coordinate,
            alternatives: Boolean,
        ): AppResult<List<Route>> {
            calls++
            requestedDestinations += destination
            return result
        }
    }

    /**
     * Router whose first request only completes after an explicit gate — and completes even if
     * the requesting coroutine was cancelled meanwhile, exactly like a socket read that was
     * already in flight when the user changed destination (stale-response scenario).
     */
    private class SlowFirstRequestRouter(
        private val staleRoute: Route,
        private val freshRoute: Route,
    ) : RoutingProvider {
        val requestedDestinations = mutableListOf<Coordinate>()
        val firstRequestGate = CompletableDeferred<Unit>()

        override suspend fun getRoutes(
            origin: Coordinate,
            destination: Coordinate,
            alternatives: Boolean,
        ): AppResult<List<Route>> {
            requestedDestinations += destination
            if (requestedDestinations.size == 1) {
                withContext(NonCancellable) { firstRequestGate.await() }
                return AppResult.Success(listOf(staleRoute))
            }
            return AppResult.Success(listOf(freshRoute))
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

    /** Reverse geocoder whose answer only arrives after the gate opens (stale-label test). */
    private class SlowReverseGeocoder(private val resultDestination: Destination) : GeocodingProvider {
        val gate = CompletableDeferred<Unit>()

        override suspend fun search(query: String, near: Coordinate?, limit: Int): AppResult<List<Destination>> =
            AppResult.Success(emptyList())

        override suspend fun reverse(coordinate: Coordinate): AppResult<Destination> {
            withContext(NonCancellable) { gate.await() }
            return AppResult.Success(resultDestination)
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
        routing: RoutingProvider = FakeRoutingProvider(AppResult.Success(listOf(route()))),
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

    // -----------------------------------------------------------------
    // Destination-specific routes + stale responses (spec: verify destination changes)
    // -----------------------------------------------------------------

    @Test
    fun `three distinct destinations produce three distinct route requests`() = runTest {
        val routing = FakeRoutingProvider(AppResult.Success(listOf(route())))
        val vm = AppViewModel(container(routing = routing))
        val destinations = listOf(
            Destination(Coordinate(17.3616, 78.4747), title = "Charminar"),
            Destination(Coordinate(17.3969, 78.3208), title = "Vasavi College of Engineering"),
            Destination(Coordinate(17.4401, 78.3489), title = "Gachibowli"),
        )
        vm.engine.onLocationUpdate(location())

        for (destination in destinations) {
            vm.onDestinationSelected(destination)
            // Selecting a different destination discards the previous trip's routes.
            assertTrue("stale routes for ${destination.title}", vm.routes.value.isEmpty())
            assertNull(vm.selectedRoute.value)
            vm.ensureRoute()
            advanceUntilIdle()
        }

        assertEquals(destinations.map { it.coordinate }, routing.requestedDestinations)
        assertEquals("routes must come from the last fetch", 1, vm.routes.value.size)
        assertEquals(destinations.last(), vm.destination.value)
        assertNotNull(vm.selectedRoute.value)
        assertNull(vm.routeError.value)
    }

    @Test
    fun `changing destination during an in-flight request keeps the newer route`() = runTest {
        val routing = SlowFirstRequestRouter(
            staleRoute = route(distanceMeters = 1111.0),
            freshRoute = route(distanceMeters = 2222.0),
        )
        val vm = AppViewModel(container(routing = routing))
        val charminar = Destination(Coordinate(17.3616, 78.4747), title = "Charminar")
        val gachibowli = Destination(Coordinate(17.4401, 78.3489), title = "Gachibowli")

        vm.onDestinationSelected(charminar)
        vm.engine.onLocationUpdate(location())
        vm.prepareRoute()
        advanceUntilIdle()
        // First request is stuck in flight for Charminar.
        assertEquals(listOf(charminar.coordinate), routing.requestedDestinations)
        assertTrue(vm.isLoadingRoutes.value)
        assertNull(vm.selectedRoute.value)

        // Destination changes while request 1 is unresolved.
        vm.onDestinationSelected(gachibowli)
        vm.prepareRoute()
        advanceUntilIdle()
        assertEquals(listOf(charminar.coordinate, gachibowli.coordinate), routing.requestedDestinations)
        assertEquals(gachibowli, vm.destination.value)
        assertEquals(2222.0, vm.selectedRoute.value!!.distanceMeters, 0.01)
        assertFalse(vm.isLoadingRoutes.value)

        // The stale Charminar response arrives late — it must NOT overwrite Gachibowli's route.
        routing.firstRequestGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(gachibowli, vm.destination.value)
        assertEquals(2222.0, vm.selectedRoute.value!!.distanceMeters, 0.01)
        assertEquals(1, vm.routes.value.size)
        assertFalse(vm.isLoadingRoutes.value)
        assertNull(vm.routeError.value)
    }

    // -----------------------------------------------------------------
    // Repeated taps, trip lifecycle (reliability audit §2/§11)
    // -----------------------------------------------------------------

    @Test
    fun `repeated route taps while loading do not duplicate requests`() = runTest {
        val routing = FakeRoutingProvider(AppResult.Success(listOf(route())))
        val vm = AppViewModel(container(routing = routing))

        vm.onDestinationSelected(charminar)
        vm.engine.onLocationUpdate(location())
        // Rapid "Start Navigation" taps all funnel into ensureRoute(): only ONE request.
        vm.ensureRoute()
        vm.ensureRoute()
        vm.ensureRoute()
        advanceUntilIdle()

        assertEquals("duplicate in-flight route requests", 1, routing.calls)
        assertEquals(1, vm.routes.value.size)
    }

    @Test
    fun `double-tapped start navigation does not restart the trip`() = runTest {
        val vm = AppViewModel(container())
        vm.setDemoMode(true) // demo source keeps the test hermetic (no Android location APIs)
        vm.onDestinationSelected(charminar)
        vm.engine.onLocationUpdate(location())
        vm.prepareRoute()
        advanceUntilIdle()

        vm.startNavigation()
        vm.startNavigation() // second tap must be a no-op
        advanceUntilIdle()

        assertEquals(AppPhase.NAVIGATING, vm.phase.value)
        assertEquals("the trip must have been installed exactly once", 1L, vm.engine.state.value.routeVersion)
    }

    @Test
    fun `cancel navigation clears the whole trip`() = runTest {
        val vm = AppViewModel(container())
        vm.setDemoMode(true)
        vm.onDestinationSelected(charminar)
        vm.engine.onLocationUpdate(location())
        vm.prepareRoute()
        advanceUntilIdle()
        vm.startNavigation()
        assertEquals(AppPhase.NAVIGATING, vm.phase.value)

        vm.cancelNavigation()

        assertEquals(AppPhase.IDLE, vm.phase.value)
        assertNull("stop must drop the prior destination", vm.destination.value)
        assertTrue(vm.routes.value.isEmpty())
        assertNull(vm.selectedRoute.value)
        assertNull("the engine must hold no route after stop", vm.engine.state.value.activeRoute)
        assertFalse(vm.isLoadingRoutes.value)
        assertNull(vm.routeError.value)
    }

    @Test
    fun `cancel during an in-flight route request prevents the late response from restoring state`() = runTest {
        val routing = SlowFirstRequestRouter(
            staleRoute = route(distanceMeters = 1111.0),
            freshRoute = route(distanceMeters = 2222.0),
        )
        val vm = AppViewModel(container(routing = routing))

        vm.onDestinationSelected(charminar)
        vm.engine.onLocationUpdate(location())
        vm.prepareRoute()
        advanceUntilIdle()
        assertTrue(vm.isLoadingRoutes.value)

        vm.cancelNavigation()
        routing.firstRequestGate.complete(Unit) // late response for the cancelled trip
        advanceUntilIdle()

        assertNull(vm.destination.value)
        assertTrue("late responses must not resurrect a cancelled trip", vm.routes.value.isEmpty())
        assertNull(vm.selectedRoute.value)
        assertFalse(vm.isLoadingRoutes.value)
    }

    @Test
    fun `finish arrival clears the trip so the next one starts clean`() = runTest {
        val vm = AppViewModel(container())
        vm.setDemoMode(true)
        vm.onDestinationSelected(charminar)
        vm.engine.onLocationUpdate(location())
        vm.prepareRoute()
        advanceUntilIdle()
        vm.startNavigation()

        vm.finishArrival()

        assertEquals(AppPhase.IDLE, vm.phase.value)
        assertNull(vm.destination.value)
        assertTrue(vm.routes.value.isEmpty())
        assertNull(vm.selectedRoute.value)
        assertNull(vm.engine.state.value.activeRoute)
    }

    @Test
    fun `stale reverse label from a previous pin never overwrites a newer destination`() = runTest {
        val geocoder = SlowReverseGeocoder(Destination(Coordinate(17.4111, 78.5111), title = "Old Pin"))
        val vm = AppViewModel(container(geocoder = geocoder))

        vm.onMapLongPress(Coordinate(17.4000, 78.5000))
        advanceUntilIdle() // reverse lookup now in flight (gated)

        vm.onDestinationSelected(charminar)
        geocoder.gate.complete(Unit) // stale label arrives late
        advanceUntilIdle()

        assertEquals("Charminar", vm.destination.value?.title)
    }
}
