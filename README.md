# HunterNav

**A motorcycle-focused navigation app for Android, built on Google Maps Platform — traffic-aware two-wheeler routing for India.**

HunterNav is a complete Android navigation prototype: an interactive bright map, live GPS
tracking, destination search, real routing, turn-by-turn guidance, off-route detection with
automatic rerouting, and a navigation camera with dynamic zoom. It is built to feel like the
brain of a dedicated motorcycle navigator — the phone does the thinking, and the navigation
state is kept independent from the UI so a future ESP32 display can consume it over Bluetooth.

> This is the **software prototype only**. No ESP32, Bluetooth, TFT/LCD, screen mirroring,
> Android Auto, CarPlay, OBD or ECU integration is implemented yet — see
> [docs/future-hardware.md](docs/future-hardware.md) for that plan.

---

## Features

- Interactive vector map via the **Google Maps SDK for Android** (key via manifest meta-data)
- Live GPS location with smoothed bearing/position (Fused Location Provider)
- Destination selection: map long-press **and** text search (Google Geocoding API)
- Real route calculation via the **Google Routes API v2** (traffic-aware `TWO_WHEELER` mode —
  see [docs/google-maps.md](docs/google-maps.md)); no fallback engine, missing key fails
  loudly with setup instructions
- Route preview with distance, ETA, arrival clock time and alternatives
- Turn-by-turn instructions using normalized maneuver types (provider-independent)
- Off-route detection with hysteresis, sustained-deviation, accuracy and cooldown guards
- Automatic rerouting (single in-flight request, stale responses ignored)
- Arrival detection with configurable radius and hysteresis
- Navigation camera: FOLLOW / OVERVIEW / FREE modes with dynamic maneuver-based zoom
- Distance units (metric/imperial), camera preference, attribution screen
- **Hidden demo mode**: simulated GPS, wrong turns and rerouting without riding
- **Handlebar display pipeline**: rendered navigation frames + guidance streamed to an ESP32
  over Wi-Fi (WebSocket), behind the stable `DisplayNavigationState` contract — driven by
  HunterNav's own engine (same Google-backed routing as the phone UI) or the offline
  simulator ([docs/display-pipeline.md](docs/display-pipeline.md))
- Unit tests for parsing, normalization, formatting, off-route, cooldown, arrival and engine
  state transitions

## Tech stack

| Layer          | Choice                                              |
|----------------|-----------------------------------------------------|
| Language       | Kotlin 2.4                                          |
| UI             | Jetpack Compose (single Activity, Navigation-Compose) |
| Map            | Google Maps SDK for Android (play-services-maps)    |
| Location       | Fused Location Provider (play-services-location)    |
| Routing        | Google Routes API v2 (TWO_WHEELER, TRAFFIC_AWARE)   |
| Geocoding      | Google Geocoding API                                |
| Display tiles  | Google Map Tiles API (2D roadmap raster)            |
| Networking     | OkHttp 5 + kotlinx.serialization                    |
| Build          | Gradle 9.8, Android Gradle Plugin 9.4 (built-in Kotlin) |
| Min/Target     | minSdk 26, compileSdk 37, targetSdk 36              |

No Mapbox, no MapLibre, no Mappls, no OSRM/Nominatim: map, routing and search data come from
Google Maps Platform behind **one API key**. See [docs/google-maps.md](docs/google-maps.md)
for setup; without a key, routing and search report a clear configuration error instead of
silently using a different engine.

## Architecture

```
UI (Compose screens)
   ↓ StateFlow<UiState / NavigationState>
ViewModel (AppViewModel)
   ↓ use cases (CalculateRoutes, SearchDestination)
Domain (models, NavigationEngine, RouteProgressTracker, OffRouteDetector, NavigationOutput)
   ↓ interfaces (RoutingProvider, GeocodingProvider, LocationProvider)
Data (GoogleRoutesProvider + DTOs/mappers, GoogleGeocodingProvider,
      AndroidLocationProvider, FakeLocationProvider)
```

Package layout lives under `app/src/main/java/com/hunternav/` with `ui/`, `domain/`,
`data/`, `core/` and `di/`. See [docs/architecture.md](docs/architecture.md) and
[docs/navigation-engine.md](docs/navigation-engine.md).

## Requirements

- **Android Studio** (Ladybug or newer recommended) with JDK 17+
- Android device or emulator with Google Play services, API 26+
- A **Google Cloud project with billing enabled** and the Maps SDK for Android, Routes API,
  Geocoding API and Map Tiles API enabled — plus a restricted API key
  ([docs/google-maps.md](docs/google-maps.md))
- Internet access for map/routing/geocoding (V1 is online-only)

## How to run

```bash
git clone <this repo>
cd hunter-nav
./gradlew :app:assembleDebug          # builds app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug           # installs on a connected device
./gradlew :app:testDebugUnitTest      # runs the unit test suite
```

Or open the project root in Android Studio and press Run. The app opens around **Hyderabad,
Telangana** by default (configurable) — the primary test region.

### Configuration (key, endpoints, default camera)

All endpoints are centralized in `BuildConfig` (see `app/build.gradle.kts`) and can be
overridden per machine in `local.properties` — **never commit secrets**:

```properties
# REQUIRED — Google Maps Platform key (docs/google-maps.md)
google.maps.apiKey=<your key>
# Optional overrides (defaults shown)
# google.routes.baseUrl=https://routes.googleapis.com
# google.geocoding.baseUrl=https://maps.googleapis.com
default.latitude=17.3850
default.longitude=78.4867
default.zoom=12.5
demo.enabled=false
```

Without `google.maps.apiKey`, routing and search fail with a setup message (no other-provider
fallback) and the map renders no tiles — that is intentional so wrong-provider
distance/ETA numbers can never ship silently.

See [local.properties.example](local.properties.example).

## How the map is rendered

The map is the **Google Maps SDK for Android** (`com.google.android.gms:play-services-maps`),
authenticated via the manifest meta-data `com.google.android.geo.API_KEY`, whose value comes
from `local.properties` (`google.maps.apiKey`) through a Gradle manifest placeholder. The key
is never committed to git. Builds without a key (CI, other contributors) still compile and
run the whole test suite — the map area is simply blank and routing/search report the
configuration error.

## Attribution requirements

Google attribution **must remain visible** wherever Google-served data is shown. HunterNav
shows "Map data © Google" in *Settings → About & attribution*, and the Maps SDK renders its
built-in attribution control on the map. Do not remove or hide either.

## Routing provider & traffic truth

- Routing uses **only** the official Google Routes API v2
  (`routes.googleapis.com/directions/v2:computeRoutes`), documented in
  [docs/google-maps.md](docs/google-maps.md). There is no OSRM endpoint or silent provider
  switch anywhere in the app: without `google.maps.apiKey`, route calculation returns a clear
  configuration error.
- The request uses `travelMode: TWO_WHEELER` (Google's motorcycle/scooter mode) with
  `routingPreference: TRAFFIC_AWARE`. If the account/region rejects the traffic-aware request
  as invalid (HTTP 400), the provider retries **once** with the default routing preference
  and logs `ROUTE_TRAFFIC_FALLBACK` — the route stays real, only the traffic adjustment is
  missing. Auth, quota and server failures never trigger that fallback. The UI never labels a
  free-flow route as traffic-aware.
- Traffic truth: with `TRAFFIC_AWARE` active, duration/distance reflect live traffic at
  request time; after the fallback they are best-guess estimates — honest, but not "live
  traffic". Check Logcat (`ROUTING_PROVIDER`, debug builds) to see which preference served a
  route. Rate limits, retries, field masks and error handling: see
  [docs/google-maps.md](docs/google-maps.md).

## Privacy & external services

HunterNav has **no accounts, no analytics and no crash-reporting SDKs**, and stores no data
server-side (there is no first-party backend). What third parties can see:

| Provider | Data sent | Why |
|----------|-----------|-----|
| **Google Maps Platform** (map, routing, search, display tiles) | Which map areas are displayed; origin/destination coordinates of route requests; current position while rerouting; search text you submitted; a dropped pin's coordinates for reverse labeling; your restricted API key (header/query parameter) | Map rendering, routing, place search |
| **Google Play services** (device location) | Location handled per Google's Play services terms, if installed | Fused on-device/network location only |

These services can see these requests — nothing in HunterNav hides them. Location stays on
the device; precise-coordinate diagnostics are written **only to Logcat in debug builds**
(`DebugLog` is a no-op in release). The API key exists only in your local `local.properties`
and the signed app binary — never in the repository. An in-app summary lives in
*Settings → Privacy & services*.

### Search usage policy

- Searches fire only on **deliberate submission** (no type-ahead/autocomplete).
- One app-wide rate limit: **≤1 request/second** across search and reverse lookups.
- Successful queries are cached for ~10 minutes to avoid repeat traffic.
- Requests are made with `region=IN`, `language=en`; the endpoint is configurable
  (`google.geocoding.baseUrl`).
- **The rider's GPS position is never sent with ordinary text search** — a location bias is
  transmitted only via an explicit proximity hint.

## Switching routing providers

Routing sits behind `RoutingProvider` (in `domain/repository/`) and is registered in
`di/AppContainer.kt`:

```
RoutingProvider
 ├── GoogleRoutesProvider          (the only production provider — TWO_WHEELER, TRAFFIC_AWARE)
 └── UnconfiguredRoutingProvider   (no key: returns the setup error, never another engine)
```

The navigation engine, UI and demo mode only see the interface. Do not add a silent fallback
provider: a different engine returning different distance/ETA numbers is the exact failure
mode this repo was migrated away from.

## Demo mode (developer)

Settings has a hidden developer switch: open *Settings*, tap the version/attribution block
**5 times** to reveal **Demo mode**. With it enabled, the active-navigation screen shows demo
controls:

- **Simulate drive** — fake GPS walks the active route geometry at ~43 km/h
- **Wrong turn** — injects a lateral offset to trigger off-route detection and rerouting

This demonstrates the whole loop (route → follow → deviate → reroute → arrive) without
physically moving. The fake source (`FakeLocationProvider`) is also what the unit tests use.

## Testing

```bash
./gradlew clean :app:testDebugUnitTest :app:assembleDebug
```

Covers: Google routing request construction (two-wheeler, traffic-aware, alternatives,
field mask), the documented traffic-preference fallback, response parsing/validation/error
classes (fixtures from the documented response schema), missing-key error path (no silent
provider switch), retries and rate limiting, geocoding search/reverse parsing and status
handling, maneuver normalization and step advancement, remaining-distance/ETA math,
off-route hysteresis, reroute cooldown & single-flight, arrival-once detection, trip
cleanup, stale-response protection (destination switching, cancellation), geocoder rate
limiting + caching, distance/duration formatting, the display feed reducer, wire-protocol
round-trips, tile math/cache policy and demo-mode simulation.

Unit tests use deterministic fakes; production routing/geocoding always hit the real Google
APIs. Genuine captured responses (documented Google schemas) are embedded as test fixtures.

## Reliability status — what still needs road testing

Verified by unit tests and live API traces: destination-specific routing, maneuver
advancement, metric math, reroute guards, arrival latching, trip cleanup, failure states.
**Not verified without a physical device:** real GNSS multipath/accuracy behavior, camera
follow feel, on-road reroute latency end-to-end, Google Maps rendering on target GPUs,
behavior under flaky cellular networks, and background/foreground transitions during an
active ride. The current-location pointer is **not** proof that turn navigation or
rerouting is correct. Live traffic also depends on your Google Cloud project's Routes API
quota and billing — see [docs/google-maps.md](docs/google-maps.md).

## ESP32 handlebar display pipeline

The navigation state is published as a plain, Android-free `DisplayNavigationState` and can be
streamed to an external display. `display/` implements the full phone-side pipeline: a pluggable
`NavigationDataProvider` — `EngineNavigationDataProvider` (HunterNav's own engine with the same
Google-backed routing as the phone UI) or the deterministic offline simulator — a software
renderer that draws heading-up route frames, a bounded JPEG encoder, a versioned binary wire
protocol (`HNV1`) and a WebSocket transport to the ESP32 — with ~10 Hz guidance metadata and
~4 Hz image frames, owned by `DisplayForegroundService` (a location-type foreground service)
so streaming continues with the screen off. See
[docs/display-pipeline.md](docs/display-pipeline.md) for the architecture, wire format and
configuration. The BLE path in [docs/future-hardware.md](docs/future-hardware.md) remains a
future alternative transport — the state contract is shared.

```
NavigationDataProvider → DisplayNavigationState → FrameRenderer → JpegEncoder
                                                        │
                          FrameProtocol ("HNV1") ◀──────┘
                                   │ WebSocket (binary)
                                   ▼
                              ESP32 display
```

## Repository structure

```
hunter-nav/
├── app/                 # Android application module
├── docs/                # architecture, navigation engine, Google setup, future hardware
├── backend/             # self-hosting notes (future OSM-stack option)
├── scripts/             # build/test helpers
├── README.md
├── local.properties.example
├── .gitignore
└── LICENSE
```

Not in the repository: build outputs, APKs, secrets or API keys — the Google Maps API key
lives only in your local `local.properties`.

## License

MIT — see [LICENSE](LICENSE). Map data, routing and geocoding are served by Google Maps
Platform and subject to its terms ([docs/google-maps.md](docs/google-maps.md)).
