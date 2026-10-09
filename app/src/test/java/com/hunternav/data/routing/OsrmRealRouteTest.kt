package com.hunternav.data.routing

import com.hunternav.core.result.AppResult
import com.hunternav.core.util.distanceMeters
import com.hunternav.domain.model.Coordinate
import com.hunternav.domain.model.LocationData
import com.hunternav.domain.model.ManeuverType
import com.hunternav.domain.model.Route
import com.hunternav.domain.navigation.NavigationEngine
import com.hunternav.domain.repository.RoutingProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fidelity tests against a **verbatim** OSRM response captured from the public demo server
 * (`router.project-osrm.org`, HTTP 200, `code=Ok`) for the trip
 * `78.5229411,17.3735683 → 78.5290,17.3790` with `overview=full&geometries=geojson&steps=true`.
 *
 * Proves the parsing → Route → engine pipeline works on real data: the route ends at the
 * requested destination, maneuvers map to normalized instructions, and progress metrics
 * (distance-to-maneuver, remaining distance/duration) are live — not fixed sample values.
 */
class OsrmRealRouteTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Origin/destination of the capture (Hyderabad). */
    private val originLat = 17.3735683
    private val originLon = 78.5229411
    private val destLat = 17.3790
    private val destLon = 78.5290

    private val realResponse = """{"code":"Ok","routes":[{"legs":[{"steps":[{"intersections":[{"out":0,"entry":[true],"bearings":[198],"location":[78.522829,17.373603]},{"out":2,"in":0,"entry":[false,true,true],"bearings":[15,105,195],"location":[78.522605,17.372948]}],"driving_side":"right","geometry":{"coordinates":[[78.522829,17.373603],[78.522605,17.372948],[78.522426,17.372461]],"type":"LineString"},"maneuver":{"bearing_after":198,"bearing_before":0,"location":[78.522829,17.373603],"modifier":"left","type":"depart"},"name":"Street No 2","mode":"driving","weight":21.4,"duration":21.4,"distance":133.5},{"intersections":[{"out":2,"in":0,"entry":[false,true,true],"bearings":[15,105,285],"location":[78.522426,17.372461]},{"out":2,"in":1,"entry":[true,false,true],"bearings":[15,105,285],"location":[78.521974,17.372626]},{"out":2,"in":0,"entry":[false,true,true],"bearings":[105,195,285],"location":[78.52112,17.372949]},{"out":2,"in":1,"entry":[true,false,true],"bearings":[15,105,285],"location":[78.520948,17.372984]},{"out":2,"in":0,"entry":[false,true,true],"bearings":[105,195,285],"location":[78.520791,17.373023]},{"out":2,"in":1,"entry":[true,false,true],"bearings":[15,105,285],"location":[78.520385,17.373139]},{"out":2,"in":1,"entry":[true,false,true],"bearings":[15,105,285],"location":[78.519801,17.373304]},{"out":2,"in":1,"entry":[true,false,true],"bearings":[15,105,285],"location":[78.519358,17.373425]}],"driving_side":"right","geometry":{"coordinates":[[78.522426,17.372461],[78.521974,17.372626],[78.52112,17.372949],[78.520948,17.372984],[78.520791,17.373023],[78.520385,17.373139],[78.519801,17.373304],[78.519358,17.373425],[78.518824,17.373529]],"type":"LineString"},"maneuver":{"bearing_after":289,"bearing_before":198,"location":[78.522426,17.372461],"modifier":"right","type":"end of road"},"name":"Model Bank Road","mode":"driving","weight":62.8,"duration":62.8,"distance":401.4},{"intersections":[{"out":2,"in":1,"entry":[true,false,true],"bearings":[15,105,195],"location":[78.518824,17.373529]},{"out":1,"in":0,"entry":[false,true,true],"bearings":[15,195,285],"location":[78.518771,17.373316]},{"out":2,"in":0,"entry":[false,true,true],"bearings":[15,105,195],"location":[78.518686,17.372975]},{"out":1,"in":0,"entry":[false,true,true],"bearings":[15,195,285],"location":[78.518655,17.372854]},{"out":1,"in":0,"entry":[false,true,true],"bearings":[15,195,285],"location":[78.518556,17.372455]},{"out":2,"in":0,"entry":[false,true,true],"bearings":[15,105,195],"location":[78.518534,17.37237]},{"out":2,"in":0,"entry":[false,true,true,true],"bearings":[15,105,195,285],"location":[78.51845,17.372032]},{"out":2,"in":0,"entry":[false,true,true],"bearings":[15,105,195],"location":[78.518403,17.371842]}],"driving_side":"right","geometry":{"coordinates":[[78.518824,17.373529],[78.518771,17.373316],[78.518686,17.372975],[78.518655,17.372854],[78.518556,17.372455],[78.518534,17.37237],[78.51845,17.372032],[78.518403,17.371842],[78.518379,17.371747]],"type":"LineString"},"maneuver":{"bearing_after":192,"bearing_before":281,"location":[78.518824,17.373529],"modifier":"left","type":"end of road"},"name":"SBI Officers Colony Road","mode":"driving","weight":31.1,"duration":31.1,"distance":202.8},{"intersections":[{"out":2,"in":0,"entry":[false,true,true],"bearings":[15,195,285],"location":[78.518379,17.371747]},{"out":3,"in":1,"entry":[true,false,true,true],"bearings":[0,105,195,285],"location":[78.517197,17.371989]},{"out":3,"in":1,"entry":[true,false,true,true],"bearings":[15,105,195,285],"location":[78.516866,17.37208]},{"out":3,"in":1,"entry":[true,false,true,true],"bearings":[60,105,195,285],"location":[78.516237,17.372244]},{"out":2,"in":0,"entry":[false,true,true],"bearings":[105,180,270],"location":[78.515971,17.372325]},{"out":2,"in":0,"entry":[false,true,true],"bearings":[90,180,270],"location":[78.515381,17.372386]}],"driving_side":"right","geometry":{"coordinates":[[78.518379,17.371747],[78.517197,17.371989],[78.516866,17.37208],[78.516237,17.372244],[78.515971,17.372325],[78.515381,17.372386],[78.514921,17.372389]],"type":"LineString"},"maneuver":{"bearing_after":281,"bearing_before":192,"location":[78.518379,17.371747],"modifier":"right","type":"turn"},"name":"","mode":"driving","weight":54.2,"duration":54.2,"distance":375.9},{"intersections":[{"out":3,"in":1,"entry":[false,false,true,true],"bearings":[0,90,180,270],"location":[78.514921,17.372389]},{"out":0,"in":1,"entry":[true,false,false,true],"bearings":[0,90,180,285],"location":[78.514834,17.372389]},{"out":0,"in":2,"entry":[true,true,false],"bearings":[0,90,180],"location":[78.514802,17.373622]},{"out":0,"in":1,"entry":[true,false,true],"bearings":[0,180,270],"location":[78.514791,17.374065]},{"out":0,"in":2,"entry":[true,true,false,true],"bearings":[0,90,180,270],"location":[78.514771,17.374534]},{"out":0,"in":2,"entry":[true,true,false,true],"bearings":[15,105,180,270],"location":[78.514637,17.376347]},{"out":0,"in":2,"entry":[true,true,false],"bearings":[15,105,195],"location":[78.514678,17.376543]},{"out":0,"in":2,"entry":[true,true,false,true],"bearings":[30,120,210,300],"location":[78.515233,17.377527]},{"out":0,"in":1,"entry":[true,false,true],"bearings":[30,210,300],"location":[78.516012,17.378678]},{"out":0,"in":1,"entry":[true,false],"bearings":[45,225],"location":[78.517277,17.380286]},{"out":0,"in":1,"entry":[true,false,true],"bearings":[30,225,300],"location":[78.517357,17.380386]},{"out":0,"in":1,"entry":[true,false,true],"bearings":[45,210,315],"location":[78.51776,17.380903]},{"out":0,"in":1,"entry":[true,false,true],"bearings":[30,210,315],"location":[78.518143,17.381464]}],"driving_side":"right","geometry":{"coordinates":[[78.514921,17.372389],[78.514834,17.372389],[78.514832,17.372547],[78.514802,17.373212],[78.514802,17.373622],[78.514791,17.374065],[78.514771,17.374534],[78.514739,17.374881],[78.514675,17.375691],[78.51464,17.376072],[78.514637,17.376347],[78.514678,17.376543],[78.514723,17.376686],[78.514759,17.376779],[78.514801,17.37686],[78.515233,17.377527],[78.515662,17.378199],[78.515761,17.378307],[78.51583,17.378391],[78.51592,17.37854],[78.516012,17.378678],[78.516284,17.379067],[78.51646,17.379286],[78.516536,17.379368],[78.517277,17.380286],[78.517357,17.380386],[78.51751,17.38058],[78.517632,17.380738],[78.51776,17.380903],[78.51794,17.38112],[78.517998,17.381199],[78.518058,17.381287],[78.518081,17.38134],[78.518106,17.381396],[78.518143,17.381464],[78.518209,17.381599],[78.518238,17.381687],[78.518248,17.381777],[78.518228,17.38201]],"type":"LineString"},"maneuver":{"bearing_after":270,"bearing_before":270,"location":[78.514921,17.372389],"modifier":"right","type":"turn"},"name":"Moosarambagh Road","mode":"driving","weight":69,"duration":69,"distance":1192.6},{"intersections":[{"out":0,"in":2,"entry":[true,false,false,true],"bearings":[0,90,180,300],"location":[78.518228,17.38201]},{"out":0,"in":1,"entry":[true,false,false,true],"bearings":[90,180,300,345],"location":[78.518224,17.382131]},{"out":0,"in":2,"entry":[true,true,false,false],"bearings":[105,180,270,330],"location":[78.518352,17.382122]},{"out":1,"in":2,"entry":[true,true,false],"bearings":[15,105,285],"location":[78.518405,17.382113]},{"out":0,"in":2,"entry":[true,true,false],"bearings":[105,270,285],"location":[78.518642,17.382031]},{"out":0,"in":2,"entry":[true,true,false],"bearings":[105,210,285],"location":[78.519303,17.381821]},{"out":1,"in":2,"entry":[true,true,false],"bearings":[15,105,285],"location":[78.519375,17.381795]},{"out":1,"in":2,"entry":[true,true,false],"bearings":[15,105,285],"location":[78.519633,17.381723]},{"out":1,"in":2,"entry":[true,true,false],"bearings":[15,105,285],"location":[78.520337,17.381514]},{"out":1,"in":3,"entry":[true,true,true,false],"bearings":[15,75,225,285],"location":[78.521508,17.381093]},{"out":0,"in":2,"entry":[true,true,false],"bearings":[60,150,225],"location":[78.522493,17.381418]},{"out":0,"in":2,"entry":[true,true,false],"bearings":[120,180,285],"location":[78.524877,17.382055]},{"out":0,"in":2,"entry":[true,true,false],"bearings":[120,210,300],"location":[78.525432,17.381768]},{"out":1,"in":2,"entry":[true,true,false],"bearings":[0,105,285],"location":[78.526809,17.381246]},{"out":1,"in":2,"entry":[true,true,false],"bearings":[0,105,285],"location":[78.526992,17.381208]},{"out":1,"in":2,"entry":[true,true,false],"bearings":[15,105,285],"location":[78.527571,17.38105]},{"out":1,"in":2,"entry":[true,true,false],"bearings":[15,120,285],"location":[78.527815,17.380966]},{"out":1,"in":2,"entry":[true,true,false],"bearings":[15,120,300],"location":[78.528025,17.38088]},{"out":1,"in":2,"entry":[true,true,false],"bearings":[15,120,300],"location":[78.52822,17.380793]},{"out":1,"in":2,"entry":[true,true,false],"bearings":[15,120,300],"location":[78.528495,17.380654]}],"driving_side":"right","geometry":{"coordinates":[[78.518228,17.38201],[78.518224,17.382131],[78.518352,17.382122],[78.518405,17.382113],[78.518551,17.382068],[78.518615,17.382052],[78.518629,17.382046],[78.518642,17.382031],[78.519303,17.381821],[78.519375,17.381795],[78.519633,17.381723],[78.519977,17.381636],[78.520337,17.381514],[78.521508,17.381093],[78.521673,17.381132],[78.521773,17.381149],[78.521916,17.381161],[78.522023,17.38116],[78.522119,17.38116],[78.522226,17.381194],[78.522367,17.381288],[78.522493,17.381418],[78.522875,17.381629],[78.523002,17.381689],[78.523226,17.381797],[78.523384,17.381862],[78.523531,17.381911],[78.523733,17.381962],[78.523936,17.382004],[78.524162,17.382064],[78.524374,17.382098],[78.524562,17.382108],[78.524673,17.382105],[78.524775,17.382086],[78.524877,17.382055],[78.525004,17.381989],[78.525221,17.381879],[78.525432,17.381768],[78.525578,17.381685],[78.525844,17.381551],[78.525946,17.381503],[78.526054,17.381454],[78.526243,17.381386],[78.526454,17.381333],[78.526809,17.381246],[78.526992,17.381208],[78.527182,17.381175],[78.527571,17.38105],[78.527815,17.380966],[78.528025,17.38088],[78.52822,17.380793],[78.528495,17.380654],[78.528928,17.380458],[78.528884,17.38029],[78.528812,17.380147],[78.528659,17.379978],[78.52892,17.379697],[78.529062,17.379556],[78.529324,17.37929]],"type":"LineString"},"maneuver":{"bearing_after":357,"bearing_before":354,"location":[78.518228,17.38201],"modifier":"right","type":"turn"},"name":"","mode":"driving","weight":114.7,"duration":114.7,"distance":1234.8},{"intersections":[{"out":2,"in":3,"entry":[true,true,true,false],"bearings":[15,120,195,300],"location":[78.528928,17.380458]}],"driving_side":"right","geometry":{"coordinates":[[78.528928,17.380458],[78.528884,17.38029],[78.528812,17.380147],[78.528659,17.379978]],"type":"LineString"},"maneuver":{"bearing_after":194,"bearing_before":113,"location":[78.528928,17.380458],"modifier":"right","type":"turn"},"name":"","mode":"driving","weight":9.9,"duration":9.9,"distance":61.5},{"intersections":[{"out":1,"in":0,"entry":[false,true,true],"bearings":[45,135,240],"location":[78.528659,17.379978]}],"driving_side":"right","geometry":{"coordinates":[[78.528659,17.379978],[78.52892,17.379697],[78.529062,17.379556],[78.529324,17.37929]],"type":"LineString"},"maneuver":{"bearing_after":137,"bearing_before":220,"location":[78.528659,17.379978],"modifier":"left","type":"turn"},"name":"","mode":"driving","weight":15,"duration":15,"distance":103.9},{"intersections":[{"in":0,"entry":[true],"bearings":[317],"location":[78.529324,17.37929]}],"driving_side":"right","geometry":{"coordinates":[[78.529324,17.37929],[78.529324,17.37929]],"type":"LineString"},"maneuver":{"bearing_after":0,"bearing_before":137,"location":[78.529324,17.37929],"modifier":"right","type":"arrive"},"name":"","mode":"driving","weight":0,"duration":0,"distance":0}],"weight":378.1,"summary":"Model Bank Road, Moosarambagh Road","duration":378.1,"distance":3706.4}],"weight_name":"routability","geometry":{"coordinates":[[78.522829,17.373603],[78.522605,17.372948],[78.522426,17.372461],[78.521974,17.372626],[78.52112,17.372949],[78.520948,17.372984],[78.520791,17.373023],[78.520385,17.373139],[78.519801,17.373304],[78.519358,17.373425],[78.518824,17.373529],[78.518771,17.373316],[78.518686,17.372975],[78.518655,17.372854],[78.518556,17.372455],[78.518534,17.37237],[78.51845,17.372032],[78.518403,17.371842],[78.518379,17.371747],[78.517197,17.371989],[78.516866,17.37208],[78.516237,17.372244],[78.515971,17.372325],[78.515381,17.372386],[78.514921,17.372389],[78.514834,17.372389],[78.514832,17.372547],[78.514802,17.373212],[78.514802,17.373622],[78.514791,17.374065],[78.514771,17.374534],[78.514739,17.374881],[78.514675,17.375691],[78.51464,17.376072],[78.514637,17.376347],[78.514678,17.376543],[78.514723,17.376686],[78.514759,17.376779],[78.514801,17.37686],[78.515233,17.377527],[78.515662,17.378199],[78.515761,17.378307],[78.51583,17.378391],[78.51592,17.37854],[78.516012,17.378678],[78.516284,17.379067],[78.51646,17.379286],[78.516536,17.379368],[78.517277,17.380286],[78.517357,17.380386],[78.51751,17.38058],[78.517632,17.380738],[78.51776,17.380903],[78.51794,17.38112],[78.517998,17.381199],[78.518058,17.381287],[78.518081,17.38134],[78.518106,17.381396],[78.518143,17.381464],[78.518209,17.381599],[78.518238,17.381687],[78.518248,17.381777],[78.518228,17.38201],[78.518224,17.382131],[78.518352,17.382122],[78.518405,17.382113],[78.518551,17.382068],[78.518615,17.382052],[78.518629,17.382046],[78.518642,17.382031],[78.519303,17.381821],[78.519375,17.381795],[78.519633,17.381723],[78.519977,17.381636],[78.520337,17.381514],[78.521508,17.381093],[78.521673,17.381132],[78.521773,17.381149],[78.521916,17.381161],[78.522023,17.38116],[78.522119,17.38116],[78.522226,17.381194],[78.522367,17.381288],[78.522493,17.381418],[78.522875,17.381629],[78.523002,17.381689],[78.523226,17.381797],[78.523384,17.381862],[78.523531,17.381911],[78.523733,17.381962],[78.523936,17.382004],[78.524162,17.382064],[78.524374,17.382098],[78.524562,17.382108],[78.524673,17.382105],[78.524775,17.382086],[78.524877,17.382055],[78.525004,17.381989],[78.525221,17.381879],[78.525432,17.381768],[78.525578,17.381685],[78.525844,17.381551],[78.525946,17.381503],[78.526054,17.381454],[78.526243,17.381386],[78.526454,17.381333],[78.526809,17.381246],[78.526992,17.381208],[78.527182,17.381175],[78.527571,17.38105],[78.527815,17.380966],[78.528025,17.38088],[78.52822,17.380793],[78.528495,17.380654],[78.528928,17.380458],[78.528884,17.38029],[78.528812,17.380147],[78.528659,17.379978],[78.52892,17.379697],[78.529062,17.379556],[78.529324,17.37929]],"type":"LineString"},"weight":378.1,"duration":378.1,"distance":3706.4}],"waypoints":[{"hint":"olKMhVRTjIVuAAAAKgAAAAAAAAAAAAAAYpmYQjTY6EEAAAAAAAAAAG4AAAAqAAAAAAAAAAAAAABSKAEAzSmuBKMZCQE9Kq4EgBkJAQAALwEAAAAA","location":[78.522829,17.373603],"name":"Street No 2","distance":12.51689309},{"hint":"cFWMhQCP35MFAAAAOwAAADgBAABbAAAAzNVwQO0UIkKSX1hDfoR9QgUAAAA7AAAAOAEAAFsAAABSKAEALEOuBNovCQHoQa4EuC4JAQgAzxMAAAAA","location":[78.529324,17.37929],"name":"","distance":47.07094078}]}"""

    private fun realRoute(): Route {
        val dto = json.decodeFromString(OsrmResponseDto.serializer(), realResponse)
        return OsrmMappers.toRoute(dto.routes.first())
    }

    private fun fix(lat: Double, lon: Double, ts: Long, accuracy: Float = 8f) = LocationData(
        latitude = lat,
        longitude = lon,
        accuracy = accuracy,
        speed = 4f,
        bearing = 0f,
        timestamp = ts,
    )

    @Test
    fun `real osrm response parses and the route ends at the requested destination`() {
        val route = realRoute()

        assertEquals(3706.4, route.distanceMeters, 1.0)
        assertEquals(378.1, route.durationSeconds, 1.0)
        assertTrue("expected full geometry, got ${route.geometry.size} points", route.geometry.size >= 100)

        val end = route.geometry.last()
        val endToDestination = distanceMeters(destLat, destLon, end.latitude, end.longitude)
        assertTrue(
            "route end ${end.latitude},${end.longitude} is ${endToDestination}m from the requested destination",
            endToDestination < 60.0,
        )

        val start = route.geometry.first()
        val startToOrigin = distanceMeters(originLat, originLon, start.latitude, start.longitude)
        assertTrue("route start is ${startToOrigin}m from the origin", startToOrigin < 60.0)
    }

    @Test
    fun `real osrm maneuvers map to normalized instructions`() {
        val route = realRoute()
        val steps = route.legs.single().steps

        assertEquals(9, steps.size)
        assertEquals(ManeuverType.START, steps[0].maneuver.type)
        assertEquals("Street No 2", steps[0].roadName)
        // "end of road" + right/left must normalize to RIGHT/LEFT — this is exactly the kind of
        // instruction the top banner shows on-device.
        assertEquals(ManeuverType.RIGHT, steps[1].maneuver.type)
        assertEquals(ManeuverType.LEFT, steps[2].maneuver.type)
        assertEquals(ManeuverType.RIGHT, steps[3].maneuver.type)
        assertEquals(ManeuverType.RIGHT, steps[4].maneuver.type)
        assertEquals(ManeuverType.LEFT, steps[7].maneuver.type)
        assertEquals(ManeuverType.ARRIVE, steps[8].maneuver.type)
    }

    @Test
    fun `engine reports live step and metrics progress on a real route`() = runTest {
        val route = realRoute()
        val engine = NavigationEngine(
            routingProvider = object : RoutingProvider {
                override suspend fun getRoutes(
                    origin: Coordinate,
                    destination: Coordinate,
                    alternatives: Boolean,
                ): AppResult<List<Route>> = AppResult.Success(listOf(route))
            },
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        val start = route.geometry.first()
        engine.startNavigation(
            route,
            fix(start.latitude, start.longitude, ts = 0L),
            arrivalDestination = Coordinate(destLat, destLon),
        )

        // At the start: departing step active, first real maneuver ahead (~133 m per OSRM).
        var state = engine.state.value
        assertEquals(ManeuverType.START, state.currentStep?.maneuver?.type)
        assertEquals("Street No 2", state.currentStep?.roadName)
        assertEquals(ManeuverType.RIGHT, state.nextStep?.maneuver?.type)
        assertEquals(133.5, state.distanceToNextManeuver, 40.0)
        assertEquals(3706.4, state.remainingDistance, 60.0)
        assertEquals(378.1, state.remainingDuration, 10.0)

        // Advancing along the route decreases the metrics live (no fixed sample values).
        val secondVertex = route.geometry[1]
        engine.onLocationUpdate(fix(secondVertex.latitude, secondVertex.longitude, ts = 1_000L))
        state = engine.state.value
        assertEquals(ManeuverType.START, state.currentStep?.maneuver?.type)
        assertTrue(state.distanceToNextManeuver < 133.5)
        assertTrue(state.remainingDistance < 3706.4)
        assertTrue(state.remainingDuration < 378.1)
        val remainingAfterStart = state.remainingDistance
        val durationAfterStart = state.remainingDuration

        // Past the first maneuver boundary: the current step has advanced to the
        // 'end of road → left' step and the next maneuver is the RIGHT turn — and the metrics
        // must strictly decrease with progress (no fixed sample values).
        val mid = route.geometry[15]
        engine.onLocationUpdate(fix(mid.latitude, mid.longitude, ts = 2_000L))
        state = engine.state.value
        assertEquals(ManeuverType.LEFT, state.currentStep?.maneuver?.type)
        assertEquals(ManeuverType.RIGHT, state.nextStep?.maneuver?.type)
        assertTrue(
            "metrics must keep decreasing: remaining=${state.remainingDistance} vs $remainingAfterStart " +
                "duration=${state.remainingDuration} vs $durationAfterStart at geometryIndex=" +
                "${state.snappedGeometryIndex} of ${route.geometry.size} vertices",
            state.remainingDistance < remainingAfterStart && state.remainingDuration < durationAfterStart,
        )

        // Reaching the route's endpoint (the destination's routable access point) arrives.
        val end = route.geometry.last()
        engine.onLocationUpdate(fix(end.latitude, end.longitude, ts = 3_000L))
        assertTrue(engine.state.value.destinationReached)
        assertEquals(0.0, engine.state.value.remainingDistance, 5.0)
    }
}
