# HunterNav Architecture

## Layers

```
┌────────────────────────────────────────────────────────────┐
│ UI (Jetpack Compose, single Activity)                      │
│   Splash · Home · Search · Route Preview · Navigation ·     │
│   Settings — pure rendering + intent forwarding            │
├────────────────────────────────────────────────────────────┤
│ ViewModel                                                   │
│   AppViewModel: destination selection, route fetch,        │
│   navigation phase, demo mode, location status             │
├────────────────────────────────────────────────────────────┤
│ Use cases                                                   │
│   CalculateRoutesUseCase · SearchDestinationUseCase        │
├────────────────────────────────────────────────────────────┤
│ Domain                                                      │
│   models (Route, RouteStep, Maneuver, NavigationState, ...) │
│   NavigationEngine + RouteProgressTracker + OffRouteDetector│
│   NavigationOutput / DisplayNavigationState                │
├────────────────────────────────────────────────────────────┤
│ Interfaces                                                  │
│   RoutingProvider · GeocodingProvider · LocationProvider   │
├────────────────────────────────────────────────────────────┤
│ Data                                                        │
│   OsrmRoutingProvider (+ DTOs/mappers)                     │
│   NominatimGeocodingProvider                               │
│   AndroidLocationProvider (Fused) · FakeLocationProvider   │
│   MapController / NavigationCameraController (MapLibre)    │
└────────────────────────────────────────────────────────────┘
```

Rules enforced in code:

- **No business logic in composables.** Screens render state and forward events.
- **No giant MainActivity** — it only wires the NavHost.
- **UI never sees OSRM JSON.** DTOs live in `data/routing/`; maneuvers are normalized to
  `ManeuverType` before crossing into the domain.
- **Routing and geocoding are swappable** behind interfaces (see README "Switching routing
  providers").

## State flow

- `AppViewModel` owns trip state: destination, routes, selected route, phase
  (`IDLE → ROUTE_PREVIEW → NAVIGATING → ARRIVED`).
- `NavigationEngine` owns live navigation state as a `StateFlow<NavigationState>` fed by the
  active `LocationProvider` update stream.
- Screens collect with `collectAsState()`; there is no duplicated server/app state.

## DI

Hand-rolled `di/AppContainer` (no Hilt in V1 to keep the dependency graph minimal). It wires
BuildConfig-driven endpoints, providers, use cases and the engine. Swapping an implementation
is a one-line change there.

## Configuration

`BuildConfig` fields are generated in `app/build.gradle.kts` with defaults, overridable via
`local.properties` (gitignored): `OSRM_BASE_URL`, `GEOCODER_BASE_URL`, `MAP_STYLE_URL`,
`DEFAULT_LATITUDE/LONGITUDE/ZOOM` (Hyderabad), `DEMO_MODE_ENABLED`. Business logic never
hardcodes endpoints.

## Map rendering

`ui/map/MapController` wraps MapLibre: style loading, route line (bright cobalt with white
casing), traveled/remaining split, destination pin, camera modes. `HunterNavMapView` embeds
`MapView` into Compose and forwards lifecycle events. `NavigationCameraController` +
`NavigationZoomPolicy` centralize dynamic zoom — thresholds live in one place, not in UI
components.

## Threading & performance

- Network runs on OkHttp's async executor bridged into coroutines; stale queries are
  cancelled (search debounce 350 ms, reroute single-flight).
- Engine mutations are synchronized so route replacement is atomic with location fixes.
- Location updates are smoothed (EMA on accuracy/speed/bearing) before reaching the engine.
- The search debounce, camera easing and windowed geometry snapping (local vertex window
  instead of full-scan) keep recomposition and CPU cost low.
