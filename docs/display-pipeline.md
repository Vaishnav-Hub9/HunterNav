# Display Pipeline (Handlebar Display / ESP32)

HunterNav can stream a rendered navigation view plus guidance metadata to an external
handlebar display (ESP32-S3 + small TFT) over Wi-Fi. The phone does all the thinking; the
display is a thin receiver that draws JPEG frames and reads a few guidance bytes.

```
NavigationDataProvider (one active source)
        │  DisplayNavigationState  (stable, Android-free contract)
        ▼
DisplayPipeline ── FrameRenderer (android.graphics → bitmap)
        │        └ JpegEncoder   (bounded JPEG bytes)
        ▼
FrameProtocol frames ── DisplayTransport (WebSocket binary) ──▶ ESP32 receiver
```

All code lives under `app/src/main/java/com/hunternav/display/`.

## Navigation data sources (`display/nav/`)

`NavigationDataProvider` is the single source interface (`state: StateFlow<DisplayNavigationState>`,
`startNavigation`, `stopNavigation`, `isAvailable`). Two implementations:

| Provider | When used | Backend |
|----------|-----------|---------|
| `EngineNavigationDataProvider` | default | HunterNav's own `NavigationEngine` + the same Google-backed `RoutingProvider` as the phone UI |
| `SimulatedNavigationDataProvider` | `display.simulator=true` | Deterministic route simulator; every state is flagged `simulated = true` |

Selection happens once in `di/AppContainer.kt`; `DisplayForegroundService` (manifest,
`foregroundServiceType="location"`) owns the pipeline so streaming survives the phone being
pocketed mid-ride. The turn-by-turn feed plumbing — `NavFeedSnapshot`, `NavStateReducer`
and `GoogleManeuverMapper` (maneuver constants javap-verified against Google's Navigation
SDK 8.0.0 AAR) — is SDK-free, JVM-tested and kept as the contract for a future live
feed integration. Everything downstream — renderer, encoder, transport, protocol — is
SDK-free and JVM-testable.

### The state contract

`DisplayNavigationState` (`domain/model/NavigationState.kt`) carries: position (with
road-snapped preference), bearing + `headingValid`, maneuver + distance-to-maneuver, remaining
distance/duration, ETA, route geometry with a monotonic `routeVersion`, and a coarse
`NavPhase` (`IDLE / NAVIGATING / REROUTING / ARRIVED / ERROR`). Rules enforced by
`NavStateReducer` (unit-tested):

- road-snapped fixes win over raw GPS for the pointer;
- a location fix older than 10 s flips the phase to `ERROR` (GPS lost) but keeps the geometry;
- rerouting (feed state) sets `REROUTING` + `offRoute`;
- new geometry always bumps `routeVersion`, so stale lines can never linger;
- ETA = now + remaining seconds, only while navigating.

## Rendering and encoding (`display/render/`)

`FrameRenderer` is a plain `android.graphics` software renderer (240×240 default,
configurable via `display.width`/`display.height`): heading-up route view using the Web
Mercator math in `display/tiles/WebMercator.kt`, rider arrow fixed at center, top band with
maneuver glyph + distance, footer with remaining distance, ETA and road name. Zoom keeps
~260 m ahead visible. `JpegEncoder` bounds frames to ≤96 KB by stepping quality down (never
resolution — the wire message always carries the true width/height).

`display/tiles/` also carries a tested Google Map Tiles API client (`GoogleMapTileProvider`:
documented session flow, `Cache-Control`-aware in-memory `TileCache`) reserved for a
basemap layer; the current renderer draws the route view on a plain background without one.

## Wire protocol (`display/transport/FrameProtocol.kt`)

One WebSocket **binary** message = one protocol message (JPEG is never base64'd). Fixed
36-byte little-endian header: magic `"HNV1"`, version, type (`IMAGE_FRAME` / `META_ONLY`),
per-connection sequence (receiver detects gaps), width/height, nav state, maneuver code,
flags (simulated / degraded / heading-valid), distance-to-maneuver, remaining meters, ETA
seconds, payload length. `decode` returns null on any malformed input — the receiver counts
and skips, never crashes. This file is the shared contract with the firmware; keep it in sync
with the ESP32 receiver.

## Transport (`display/transport/DisplayTransport.kt`)

`WebSocketDisplayTransport` connects to the ESP32's WebSocket **server** (ESP32 soft-AP
default `ws://192.168.4.1:81/display`, configurable via `display.wsUrl` in
`local.properties`) and reconnects with capped exponential backoff. The transport only ships
opaque bytes; sequencing and framing belong to the pipeline.

## Cadence (`display/DisplayPipeline.kt`)

- META_ONLY guidance messages at ~10 Hz — a few dozen bytes each, so turn changes appear
  immediately on the display.
- JPEG IMAGE_FRAMEs at ~4 Hz while navigating; ~0.5 Hz when idle; re-rendered immediately
  whenever `routeVersion` changes (reroute).
- Nothing is sent while the transport is disconnected; sequences restart per connection.

## Configuration

```properties
# local.properties (see local.properties.example) — never commit secrets
google.maps.apiKey=<key: Maps SDK, Routes API, Geocoding API, Map Tiles API>
display.wsUrl=ws://192.168.4.1:81/display   # ESP32 endpoint
display.simulator=false                     # true ⇒ offline simulator source
display.width=240                           # panel resolution
display.height=240
```

Without a key the pipeline still runs end-to-end with the simulator source, and
`EngineNavigationDataProvider` reports a clear configuration error for routing instead of
silently switching engines ([google-maps.md](google-maps.md)).

## Testing

JVM unit tests (no device needed): `FrameProtocolTest` (round-trips, malformed input),
`NavStateReducerTest` (snap/stale/reroute/ETA rules), `GoogleManeuverMapperTest` (constant
values), `WebMercatorTest` + `TileCacheTest` (tile math and cache policy),
`SimulatedNavigationDataProviderTest` (virtual-time walk/arrive).

Not verified without hardware: real Wi-Fi behavior with an ESP32, JPEG decode performance on
the firmware side, and Navigation SDK feed behavior on a device. See also
[future-hardware.md](future-hardware.md).
