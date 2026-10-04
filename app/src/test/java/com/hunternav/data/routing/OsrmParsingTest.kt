package com.hunternav.data.routing

import com.hunternav.domain.model.ManeuverType
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OsrmParsingTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val sample = """
        {
          "code": "Ok",
          "routes": [
            {
              "distance": 6841.2,
              "duration": 1024.5,
              "geometry": {
                "type": "LineString",
                "coordinates": [[78.4867, 17.3850], [78.4870, 17.3860], [78.4880, 17.3900]]
              },
              "legs": [
                {
                  "distance": 6841.2,
                  "duration": 1024.5,
                  "summary": "Shaikpet Road",
                  "steps": [
                    {
                      "distance": 300.0,
                      "duration": 45.0,
                      "name": "Shaikpet Road",
                      "mode": "driving",
                      "geometry": { "type": "LineString", "coordinates": [[78.4867, 17.3850], [78.4870, 17.3860]] },
                      "maneuver": { "type": "depart", "location": [78.4867, 17.3850], "bearing_after": 12 }
                    },
                    {
                      "distance": 500.0,
                      "duration": 80.0,
                      "name": "Road B",
                      "mode": "driving",
                      "geometry": { "type": "LineString", "coordinates": [[78.4870, 17.3860], [78.4875, 17.3880]] },
                      "maneuver": { "type": "turn", "modifier": "left", "location": [78.4870, 17.3860] }
                    },
                    {
                      "distance": 100.0,
                      "duration": 20.0,
                      "name": "",
                      "mode": "driving",
                      "geometry": { "type": "LineString", "coordinates": [[78.4875, 17.3880], [78.4880, 17.3900]] },
                      "maneuver": { "type": "arrive", "location": [78.4880, 17.3900] }
                    }
                  ]
                }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `parses route distance duration and geometry in lon-lat order`() {
        val dto = json.decodeFromString(OsrmResponseDto.serializer(), sample)
        val route = OsrmMappers.toRoute(dto.routes.first())

        assertEquals(6841.2, route.distanceMeters, 0.01)
        assertEquals(1024.5, route.durationSeconds, 0.01)
        assertEquals(3, route.geometry.size)
        // GeoJSON is [lon, lat]; domain Coordinate is (lat, lon).
        assertEquals(17.3850, route.geometry.first().latitude, 1e-6)
        assertEquals(78.4867, route.geometry.first().longitude, 1e-6)
    }

    @Test
    fun `parses steps maneuvers and road names`() {
        val dto = json.decodeFromString(OsrmResponseDto.serializer(), sample)
        val route = OsrmMappers.toRoute(dto.routes.first())

        val steps = route.legs.first().steps
        assertEquals(3, steps.size)
        assertEquals(ManeuverType.START, steps[0].maneuver.type)
        assertEquals(ManeuverType.LEFT, steps[1].maneuver.type)
        assertEquals(ManeuverType.ARRIVE, steps[2].maneuver.type)
        assertEquals("Shaikpet Road", steps[0].roadName)
        assertEquals("Road B", steps[1].roadName)
        // Blank names must not leak into the domain model.
        assertNull(steps[2].roadName)
        assertEquals(500.0, steps[1].distanceMeters, 0.01)
        assertTrue(steps[1].geometry.isNotEmpty())
    }

    @Test
    fun `geometry serializer accepts encoded polylines too`() {
        // Google polyline5 sample: (38.5,-120.2), (40.7,-120.95), (43.25,-126.45)
        val dto = json.decodeFromString(
            OsrmRouteDto.serializer(),
            """{"distance":1.0,"duration":1.0,"geometry":"_p~iF~ps|U_ulLnnqC_mqNvxq`@","legs":[]}""",
        )
        val geometry = dto.geometry!!
        assertEquals(3, geometry.coordinates.size)
        assertEquals(-120.2, geometry.coordinates[0][0], 1e-4)
        assertEquals(38.5, geometry.coordinates[0][1], 1e-4)
    }

    @Test
    fun `error response maps to no-route failure`() {
        // Parser-level: code != Ok produces an empty routes list handled by the provider.
        val dto = json.decodeFromString(OsrmResponseDto.serializer(), """{"code":"NoRoute","message":"No route found"}""")
        assertEquals("NoRoute", dto.code)
        assertTrue(dto.routes.isEmpty())
    }
}
