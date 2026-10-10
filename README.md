# HunterNav

**A motorcycle-focused navigation brain for Android — open-source stack, no paid APIs.**

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

- Interactive vector map (OpenFreeMap **Liberty** style — bright, daylight-friendly)
- Live GPS location with smoothed bearing/position (Fused Location Provider)
- Destination selection: map long-press **and** text search (Nominatim geocoder)
- Real route calculation via **OSRM** behind a `RoutingProvider` abstraction
- Route preview with distance, ETA, arrival clock time and alternatives
- Turn-by-turn instructions using normalized maneuver types (provider-independent)
- Off-route detection with hysteresis, sustained-deviation, accuracy and cooldown guards
- Automatic rerouting (single in-flight request, stale responses ignored)
- Arrival detection with configurable radius and hysteresis
- Navigation camera: FOLLOW / OVERVIEW / FREE modes with dynamic maneuver-based zoom
- Distance units (metric/imperial), camera preference, attribution screen
- **Hidden demo mode**: simulated GPS, wrong turns and rerouting without riding
- Unit tests for parsing, normalization, formatting, off-route, cooldown, arrival and engine
  state transitions

## Tech stack

| Layer        | Choice                                                     |
|--------------|------------------------------------------------------------|
| Language     | Kotlin 2.4                                                 |
| UI           | Jetpack Compose (single Activity, Navigation-Compose)      |
| Map          | MapLibre Native Android 13.6 + OpenFreeMap Liberty tiles   |
| Location     | Fused Location Provider (play-services-location)           |
| Routing      | OSRM HTTP API behind `RoutingProvider`                     |
| Geocoding    | Nominatim behind `GeocodingProvider`                       |
| Networking   | OkHttp 5 + kotlinx.serialization                           |
| Build        | Gradle 9.8, Android Gradle Plugin 9.4 (built-in Kotlin)    |
| Min/Target   | minSdk 26, compileSdk 37, targetSdk 36                    |

No Google Maps, no Mapbox, no paid API key is required for V1.

## Architecture

```
UI (Compose screens)
   ↓ StateFlow<UiState / NavigationState>
ViewModel (AppViewModel)
   ↓ use cases (CalculateRoutes, SearchDestination)
Domain (models, NavigationEngine, RouteProgressTracker, OffRouteDetector, NavigationOutput)
   ↓ interfaces (RoutingProvider, GeocodingProvider, LocationProvider)
Data (OsrmRoutingProvider, NominatimGeocodingProvider, AndroidLocationProvider, FakeLocationProvider)
```

Package layout lives under `app/src/main/java/com/hunternav/` with `ui/`, `domain/`,
`data/`, `core/` and `di/`. See [docs/architecture.md](docs/architecture.md) and
[docs/navigation-engine.md](docs/navigation-engine.md).

## Requirements

- **Android Studio** (Ladybug or newer recommended) with JDK 17+
- Android device or emulator with Google Play services, API 26+
- Internet access for map tiles, OSRM and Nominatim (V1 is online-only)

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

### Configuration (OSRM URL, geocoder, default camera)

All endpoints are centralized in `BuildConfig` (see `app/build.gradle.kts`) and can be
overridden per machine in `local.properties` — **never commit secrets**:

```properties
osrm.baseUrl=https://router.project-osrm.org/
geocoder.baseUrl=https://nominatim.openstreetmap.org/
map.styleUrl=https://tiles.openfreemap.org/styles/liberty
default.latitude=17.3850
default.longitude=78.4867
default.zoom=12.5
demo.enabled=true
```

See [local.properties.example](local.properties.example).

## How map tiles work

The map uses **OpenFreeMap** (`https://tiles.openfreemap.org/styles/liberty`): free,
MapLibre-compatible vector tiles derived from OpenStreetMap data. **No API key is needed.**
The style is bright with a light land background, visible road hierarchy and restrained
labels — chosen for daylight readability and to keep the cobalt route line dominant.

## OSM attribution requirements

OpenStreetMap attribution **must remain visible**. HunterNav shows it in
*Settings → About & attribution* ("Map data © OpenStreetMap contributors"), and MapLibre's
built-in attribution control is left enabled on the map. Do not remove or hide it.

## Limitations of public routing services

- The default `https://router.project-osrm.org/` is a **public demo server** intended for
  development and small test volumes — **it is not a production endpoint**. Expect rate
  limits and no SLA.
- Nominatim's public instance also requires low volume and a proper User-Agent (set by the
  app). For anything beyond testing, self-host (see [backend/README.md](backend/README.md)).
- OSRM's `driving` (car) profile is used — this is **not** motorcycle-optimized routing
  (no lean-aware, no motorcycle-specific preferences), and it must not be described as
  proven motorcycle routing; a cycling profile is not a substitute either. That is exactly
  why routing sits behind `RoutingProvider`: evaluating a GraphHopper motorcycle-profile
  provider is a separate task (see [backend/README.md](backend/README.md)) and can be added
  later without touching navigation code.
- Requests to the public demo endpoint are rate-limited in-app to **≤1/second** with bounded
  retries (2 retries, exponential backoff) for transient failures only (network, timeout,
  5xx, 429); invalid requests (4xx) are surfaced immediately and never retried.

## Privacy & external services

HunterNav has **no accounts, no analytics and no crash-reporting SDKs**, and stores no data
server-side (there is no first-party backend). What third parties can see:

| Provider | Data sent | Why |
|----------|-----------|-----|
| **OpenFreeMap** (tiles) | Which geographic tiles the map displays, your IP | Map rendering |
| **Nominatim** (search) | Search text you submitted; optionally a dropped pin's coordinates for reverse labeling | Place search. **Your live GPS position is never sent for text search.** |
| **OSRM** (routing) | Origin and destination coordinates of a route request, current position when rerouting | Route calculation |
| **Google Play services** (device location) | Location handled per Google's Play services terms, if installed | Fused on-device/network location only |

These services can see these requests — nothing in HunterNav hides them. Location stays on
the device; precise-coordinate diagnostics are written **only to Logcat in debug builds**
(`DebugLog` is a no-op in release). No secrets/API keys exist in the app or the repository.
An in-app summary lives in *Settings → Privacy & services*. OSM attribution is displayed per
OpenStreetMap attribution requirements.

### Geocoding usage policy (public Nominatim)

- Searches fire only on **deliberate submission** (no type-ahead/autocomplete).
- One app-wide rate limit: **≤1 request/second** across search and reverse lookups.
- Successful queries are cached for ~10 minutes to avoid repeat traffic.
- Requests identify with a `User-Agent`; the provider URL is configurable (`geocoder.baseUrl`).
- Public Nominatim is a **limited development service** — self-host for production volume.

## Switching routing providers

Implement `RoutingProvider` (in `domain/repository/`) and register it in `di/AppContainer.kt`:

```
RoutingProvider
 ├── OsrmRoutingProvider          (implemented)
 └── GraphHopperRoutingProvider   (future)
 └── OfflineRoutingProvider       (future)
```

The navigation engine, UI and demo mode only see the interface.

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

Covers: OSRM response parsing/validation/error classes, retries and rate limiting, polyline
decoding, maneuver normalization and step advancement, remaining-distance/ETA math,
off-route hysteresis, reroute cooldown & single-flight, arrival-once detection, trip
cleanup, stale-response protection (destination switching, cancellation), geocoder rate
limiting + caching, distance/duration formatting, and demo-mode simulation.

Unit tests use deterministic fakes; production routing/geocoding always hit the real
providers. A genuine captured OSRM response is embedded as a test fixture.

## Reliability status — what still needs road testing

Verified by unit tests and live API traces: destination-specific routing, maneuver
advancement, metric math, reroute guards, arrival latching, trip cleanup, failure states.
**Not verified without a physical device:** real GNSS multipath/accuracy behavior, camera
follow feel, on-road reroute latency end-to-end, MapLibre rendering on target GPUs, tile
behavior under flaky cellular networks, and background/foreground transitions during an
active ride. The current-location pointer is **not** proof that turn navigation or
rerouting is correct.

## Future ESP32 integration

The engine publishes every update through the `NavigationOutput` interface as a plain
`DisplayNavigationState` (latitude, longitude, bearing, maneuver, distances, route geometry,
viewport). Nothing Android-specific crosses that boundary. The future path is:

```
NavigationOutput → BluetoothNavigationOutput → ESP32 → small TFT/LCD handlebar display
```

No Bluetooth code exists today — preparation only. See [docs/future-hardware.md](docs/future-hardware.md).

## Repository structure

```
hunter-nav/
├── app/                 # Android application module
├── docs/                # architecture, navigation engine, future hardware
├── backend/             # self-hosting notes (OSRM/Nominatim)
├── scripts/             # build/test helpers
├── README.md
├── local.properties.example
├── .gitignore
└── LICENSE
```

Not in the repository: OSM planet/PBF files, MapLibre/OSRM sources, build outputs, APKs,
secrets or API keys.

## License

MIT — see [LICENSE](LICENSE). Map data © OpenStreetMap contributors; tiles © OpenFreeMap.
