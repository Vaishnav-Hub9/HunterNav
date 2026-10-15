# HunterNav

**A motorcycle-focused navigation app for Android, built on Mappls (MapmyIndia) — traffic-aware routing tuned for Indian roads.**

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

- Interactive vector map via the **Mappls Maps SDK** (primary), with a keyless MapLibre +
  OpenFreeMap fallback for builds without the local SDK auth files
- Live GPS location with smoothed bearing/position (Fused Location Provider)
- Destination selection: map long-press **and** text search (Mappls search APIs, or Nominatim
  when no Mappls token is configured)
- Real route calculation via the **Mappls Direction API** (traffic-aware `route_traffic` with
  the two-wheeler profile — see [docs/mappls.md](docs/mappls.md)); no OSRM fallback, missing
  auth fails loudly with setup instructions
- Route preview with distance, ETA, arrival clock time and alternatives
- Turn-by-turn instructions using normalized maneuver types (provider-independent)
- Off-route detection with hysteresis, sustained-deviation, accuracy and cooldown guards
- Automatic rerouting (single in-flight request, stale responses ignored)
- Arrival detection with configurable radius and hysteresis
- Navigation camera: FOLLOW / OVERVIEW / FREE modes with dynamic maneuver-based zoom
- Distance units (metric/imperial), camera preference, attribution screen
- **Hidden demo mode**: simulated GPS, wrong turns and rerouting without riding
- **Handlebar display pipeline**: rendered navigation frames + guidance streamed to an ESP32
  over Wi-Fi (WebSocket), behind the stable `DisplayNavigationState` contract — works with the
  Google Navigation SDK *or* HunterNav's own engine (same Mappls routing as the phone UI)
  ([docs/display-pipeline.md](docs/display-pipeline.md))
- Unit tests for parsing, normalization, formatting, off-route, cooldown, arrival and engine
  state transitions

## Tech stack

| Layer        | Choice                                                     |
|--------------|------------------------------------------------------------|
| Language     | Kotlin 2.4                                                 |
| UI           | Jetpack Compose (single Activity, Navigation-Compose)      |
| Map          | Mappls Maps SDK 9.0 (primary; keyless MapLibre fallback)     |
| Location     | Fused Location Provider (play-services-location)           |
| Routing      | Mappls Direction API v1 (route_traffic, biking profile)    |
| Geocoding    | Mappls search APIs, or Nominatim when unconfigured         |
| Networking   | OkHttp 5 + kotlinx.serialization                           |
| Build        | Gradle 9.8, Android Gradle Plugin 9.4 (built-in Kotlin)    |
| Min/Target   | minSdk 26, compileSdk 37, targetSdk 36                    |

No Google Maps and no Mapbox. Routing/map data comes from Mappls (free tier + paid API plans).
See [docs/mappls.md](docs/mappls.md) for setup; without a Console token, routing reports a
clear configuration error instead of silently using a different engine.

## Architecture

```
UI (Compose screens)
   ↓ StateFlow<UiState / NavigationState>
ViewModel (AppViewModel)
   ↓ use cases (CalculateRoutes, SearchDestination)
Domain (models, NavigationEngine, RouteProgressTracker, OffRouteDetector, NavigationOutput)
   ↓ interfaces (RoutingProvider, GeocodingProvider, LocationProvider)
Data (MapplsRoutingProvider + shared OsrmMappers, MapplsGeocodingProvider, NominatimGeocodingProvider, AndroidLocationProvider, FakeLocationProvider)
```

Package layout lives under `app/src/main/java/com/hunternav/` with `ui/`, `domain/`,
`data/`, `core/` and `di/`. See [docs/architecture.md](docs/architecture.md) and
[docs/navigation-engine.md](docs/navigation-engine.md).

## Requirements

- **Android Studio** (Ladybug or newer recommended) with JDK 17+
- Android device or emulator with Google Play services, API 26+
- Internet access for the Mappls map/routing APIs and geocoding (V1 is online-only)

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

### Configuration (routing, geocoder, default camera)

All endpoints are centralized in `BuildConfig` (see `app/build.gradle.kts`) and can be
overridden per machine in `local.properties` — **never commit secrets**:

```properties
# REQUIRED — Mappls Console access token (docs/mappls.md)
mappls.accessToken=<your token>
geocoder.baseUrl=https://nominatim.openstreetmap.org/
default.latitude=17.3850
default.longitude=78.4867
default.zoom=12.5
demo.enabled=false
```

Without `mappls.accessToken`, routing fails with a setup message (no OSRM/other-provider
fallback) — that is intentional so wrong-provider distance/ETA numbers can never ship silently.

See [local.properties.example](local.properties.example).

## How the map is rendered

The primary map is the **Mappls Maps SDK** (`com.mappls.sdk:mappls-android-sdk`), authenticated
via the `app/*.a.olf` + `app/*.a.conf` files issued by the Mappls Console (kept local, gitignored,
never committed). When those files are present at build time, the Mappls services Gradle plugin
runs, `BuildConfig.MAPPLS_ENABLED` is true and the app renders Mappls vector tiles. Builds
without the auth files (CI, other contributors) fall back to the keyless MapLibre + OpenFreeMap
map so the project always builds and runs.

## OSM attribution requirements

OpenStreetMap attribution **must remain visible** wherever OSM-derived data is shown (the
fallback map, the offline Hyderabad region, Nominatim results). HunterNav shows it in
*Settings → About & attribution* ("Map data © OpenStreetMap contributors"), the fallback
map's built-in attribution control is left enabled, and the Mappls map displays Mappls'
required attribution. Do not remove or hide either.

## Routing provider & traffic truth

- Routing uses **only** the official Mappls Direction API v1
  (`route.mappls.com/route/direction/{resource}/{profile}/{lon,lat}`), documented in
  [docs/mappls.md](docs/mappls.md). There is no OSRM endpoint or silent provider switch anywhere
  in the app: without `mappls.accessToken`, route calculation returns a clear configuration error.
- The active resource is **`route_traffic`** (traffic-aware) with the **`biking`** two-wheeler
  profile. Mappls documents only `walking`/`trucking` as restricted to `route_adv`; `biking` has
  no documented resource restriction. If an account/region combination rejects `route_traffic`,
  the provider degrades in order (`route_eta` → `route_adv`) and the last successful resource is
  remembered for the session. The UI never labels a free-flow route as traffic-aware.
- Traffic truth: when `route_traffic` is active, duration/distance reflect live traffic at
  request time. On the degraded resources the numbers are free-flow estimates — honest, but not
  "live traffic". Check Logcat (`ROUTING_PROVIDER`, debug builds) to see which resource served a
  route. Rate limits, retries and error handling: see [docs/mappls.md](docs/mappls.md).
- Nominatim's public instance (used for search only when no Mappls token is configured) requires
  low volume and a proper User-Agent (set by the app). For anything beyond testing, self-host
  (see [backend/README.md](backend/README.md)).

## Privacy & external services

HunterNav has **no accounts, no analytics and no crash-reporting SDKs**, and stores no data
server-side (there is no first-party backend). What third parties can see:

| Provider | Data sent | Why |
|----------|-----------|-----|
| **Mappls** (map + routing) | Which map areas are displayed; origin/destination coordinates of route requests; current position while rerouting; your Console access token as a query parameter | Map rendering, routing, search |
| **Nominatim** (search, only when no Mappls token) | Search text you submitted; optionally a dropped pin's coordinates for reverse labeling | Place search. **Your live GPS position is never sent for text search.** |
| **OpenFreeMap** (fallback map only) | Which geographic tiles the fallback map displays, your IP | Map rendering on builds without Mappls auth files |
| **Google Play services** (device location) | Location handled per Google's Play services terms, if installed | Fused on-device/network location only |

These services can see these requests — nothing in HunterNav hides them. Location stays on
the device; precise-coordinate diagnostics are written **only to Logcat in debug builds**
(`DebugLog` is a no-op in release). No secrets/API keys exist in the app or the repository.
An in-app summary lives in *Settings → Privacy & services*. OSM attribution is displayed per
OpenStreetMap attribution requirements.

### Geocoding usage policy

With `mappls.accessToken` configured (recommended), search + reverse geocoding use the official
Mappls search APIs. Without a token (keyless CI builds), search falls back to public Nominatim
with these rules:

- Searches fire only on **deliberate submission** (no type-ahead/autocomplete).
- One app-wide rate limit: **≤1 request/second** across search and reverse lookups.
- Successful queries are cached for ~10 minutes to avoid repeat traffic.
- Requests identify with a `User-Agent`; the provider URL is configurable (`geocoder.baseUrl`).
- Public Nominatim is a **limited development service** — self-host for production volume.

## Switching routing providers

Routing sits behind `RoutingProvider` (in `domain/repository/`) and is registered in
`di/AppContainer.kt`:

```
RoutingProvider
 ├── MapplsRoutingProvider        (the only production provider — traffic-aware, two-wheeler profile)
 ├── UnconfiguredRoutingProvider   (no token: returns the setup error, never another engine)
 └── legacy Osrm* classes         (kept only as fixture-tested parsing references — never registered)
```

The navigation engine, UI and demo mode only see the interface. Do not add a silent fallback
provider: a different engine returning different distance/ETA numbers is the exact failure mode
this repo was migrated away from.

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

Covers: Mappls routing request construction, resource fallback (`route_traffic` → `route_eta`
→ `route_adv`) and success-resource memory, response parsing/validation/error classes (fixture
captured from the documented Mappls response schema), missing-auth error path (no silent
provider switch), retries and rate limiting, polyline
decoding, maneuver normalization and step advancement, remaining-distance/ETA math,
off-route hysteresis, reroute cooldown & single-flight, arrival-once detection, trip
cleanup, stale-response protection (destination switching, cancellation), geocoder rate
limiting + caching, distance/duration formatting, and demo-mode simulation.

Unit tests use deterministic fakes; production routing/geocoding always hit the real
providers. Genuine captured responses (Mappls-documented schema + an older OSRM capture) are
embedded as test fixtures.

## Reliability status — what still needs road testing

Verified by unit tests and live API traces: destination-specific routing, maneuver
advancement, metric math, reroute guards, arrival latching, trip cleanup, failure states.
**Not verified without a physical device:** real GNSS multipath/accuracy behavior, camera
follow feel, on-road reroute latency end-to-end, Mappls/MapLibre rendering on target GPUs, tile
behavior under flaky cellular networks, and background/foreground transitions during an
active ride. The current-location pointer is **not** proof that turn navigation or
rerouting is correct. Live traffic inflow also depends on the Mappls account's allocated
APIs — see [docs/mappls.md](docs/mappls.md#traffic-truth-and-limitations).

## ESP32 handlebar display pipeline

The navigation state is published as a plain, Android-free `DisplayNavigationState` and can be
streamed to an external display. `display/` implements the full phone-side pipeline: a pluggable
`NavigationDataProvider` (Google Navigation SDK adapter with turn-by-turn feed service, or
HunterNav's own engine — the same Mappls-backed routing as the phone UI), a software renderer
that draws heading-up route
frames, a bounded JPEG encoder, a versioned binary wire protocol (`HNV1`) and a WebSocket
transport to the ESP32 — with ~10 Hz guidance metadata and ~4 Hz image frames. The Google
Navigation SDK is used only when `google.maps.apiKey` is configured; otherwise the pipeline
uses HunterNav's own engine. See
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

MIT — see [LICENSE](LICENSE). Mappls map data © CEINFO Systems (MapmyIndia); OSM-derived fallback/offline data © OpenStreetMap contributors.
