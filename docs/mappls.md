# Mappls Integration

HunterNav can use Mappls (MapmyIndia) in three independent, optional places:

1. **Maps SDK** — the primary map renderer when the Mappls auth files are present at build time
   (MapLibre + OpenFreeMap is the always-available fallback).
2. **Search & geocoding REST APIs** — destination search and reverse geocoding when a Mappls
   Console access token is configured (Nominatim is the default).
3. **Routing REST API** — traffic-aware routes with distance and ETA when the same access
   token is configured (OSRM is the default).

All three are opt-in per checkout/developer. Nothing in the repo carries credentials, and a
build without any Mappls material works exactly as before (MapLibre map, Nominatim search,
OSRM routing).

## 1. Maps SDK (primary map)

### How it is enabled

The Mappls services Gradle plugin needs the console-issued authentication files for the exact
application id + signing certificate:

```
app/com.hunternav.a.olf
app/com.hunternav.a.conf
```

- Obtain them from the [Mappls Console](https://auth.mappls.com/console/) for the package
  `com.hunternav` and the debug/release signing certificate you build with.
- They are **never committed** (`.gitignore` covers `*.a.olf` / `*.a.conf`). If present,
  `app/build.gradle.kts` applies `com.mappls.services.android` and sets
  `BuildConfig.MAPPLS_ENABLED = true`; otherwise the plugin is skipped and MapLibre stays.
- `HunterNavApplication` calls `Mappls.getInstance(context)` only when `MAPPLS_ENABLED`, so a
  mapless build never touches the SDK at runtime.

### Selection surface

`HunterNavMapSurface` (in `ui/map/MapplsMapView.kt`) is the only switch: Mappls when
`MAPPLS_ENABLED`, MapLibre otherwise. Screens depend on the `MapSurface` interface, never on a
concrete engine — `MapplsMapController` and `MapController` implement the same surface.

## 2. Search & reverse geocoding (REST APIs)

### How it is enabled

Add your Mappls Console **static access token** to `local.properties` (see
`local.properties.example`; never commit it):

```properties
mappls.accessToken=<your token>
# optional overrides:
# mappls.search.baseUrl=https://search.mappls.com/
```

With no token, `AppContainer` keeps `NominatimGeocodingProvider` — the default for every
checkout and CI. With a token it selects `MapplsGeocodingProvider`
(`data/geocoding/MapplsGeocodingProvider.kt`); the active provider is logged at startup
(`GEOCODING_PROVIDER`) and shown in *Settings → About & attribution*. The **same token**
selects the Mappls routing provider (§3) — one Console key covers search, reverse geocoding
and routes.

### Endpoints used

| Operation     | Endpoint                                                             |
|---------------|----------------------------------------------------------------------|
| Search        | `search.mappls.com/search/places/autosuggest/json?query=…`           |
| Reverse label | `search.mappls.com/search/address/rev-geocode?lat=…&lng=…`           |

Both authenticate with the `access_token` query parameter (official Mappls REST auth).

### The coordinate contract (important)

The public Mappls response samples only list eLoc/placeName/placeAddress, but the **official
Mappls SDK's own response model** (`com.mappls.sdk.services.api.autosuggest.model.ELocation`)
declares `latitude`/`longitude`/`entryLatitude`/`entryLongitude` — live responses do carry
coordinates. The provider parses them defensively:

- A search result **without usable coordinates is dropped**, never routed to as a fake (0,0) —
  a destination without latitude/longitude can never be navigated to.
- Reverse geocoding is **label-only**: the pressed coordinate stays authoritative. Mappls
  answers with the nearest mapped entity's own coordinates, which can sit tens of metres from
  the pin; returning those would silently move the marker and the route endpoint (the same
  contract Nominatim has — see `parseReverse` in either provider).
- If the reverse lookup finds no address, the destination becomes a "Dropped pin" at the
  pressed coordinate rather than a failure — the pin is already valid without a name.

### Usage policy (same guarantees as the Nominatim provider)

- ≤1 request/second app-wide across search AND reverse (`RequestRateLimiter`).
- Successful searches are cached ~10 minutes, so repeated queries never hit the network.
- The caller's GPS position is **never** sent with ordinary text search; an explicit proximity
  hint is the only way a location bias is transmitted.
- 401/403 surface with messages naming the credential problem; nothing retries indefinitely.

### Notes / limits

- Mappls search is India-first; outside India coverage varies. The app's supported routing
  region is Telangana (`SupportedRegion`), so this pairs naturally.
- Mappls terms of use and attribution requirements apply to the Maps SDK and the search APIs;
  keep the in-app attribution (Settings → About) visible.

## 3. Routing (REST API)

### How it is enabled

The **same** `mappls.accessToken` switches routing — one Console key covers search, reverse
geocoding and routes:

```properties
mappls.accessToken=<your token>
# optional overrides:
# mappls.routing.baseUrl=https://route.mappls.com/
# mappls.routing.profile=biking
```

Without a token, `AppContainer` keeps `OsrmRoutingProvider` (keyless OSRM public demo) — the
default for every checkout and CI. With a token it selects `MapplsRoutingProvider`
(`data/routing/MapplsRoutingProvider.kt`); the active engine is logged at startup
(`ROUTING_PROVIDER`). The provider sits behind the existing `RoutingProvider` interface, so
navigation, rerouting and the UI are untouched.

### Requirements check: ETA, distance, rerouting

| Requirement | Mappls support |
|-------------|----------------|
| Distance | `routes[].distance` (metres), OSRM-compatible schema |
| ETA | `routes[].duration` (seconds); resource `route_traffic` computes routes **considering live traffic**, so ETAs reflect Indian traffic conditions |
| Rerouting | Re-request from the current position through the same `RoutingProvider` (NavigationEngine's single-flight reroute) — no provider-specific work |
| Maneuvers | `steps=true` returns the documented OSRM-identical `maneuver.type`/`modifier` vocabulary, so normalization is unchanged |
| Two-wheeler | `biking` profile — genuine two-wheeler routing (OSRM only offered its car profile) |

### Endpoint

| Operation  | Endpoint                                                        |
|------------|-----------------------------------------------------------------|
| Directions | `route.mappls.com/route/direction/{resource}/{profile}/{lon,lat;lon,lat}` |

- **Resources**: `route_traffic` (traffic-aware) is requested first; if the account/region
  rejects it (HTTP 400/404/412) the provider falls back **once** to the documented non-traffic
  `route_adv` resource (logged as `ROUTE_RESOURCE_FALLBACK`) — the route stays real, only the
  traffic adjustment is missing. Auth (401), quota (429) and server (5xx) failures never
  trigger the fallback.
- **Profile**: `biking` by default (`mappls.routing.profile` override; undocumented values
  are sanitized back to `biking`).
- **Auth**: `access_token` query parameter (official Mappls REST auth).

### Same contracts as the OSRM provider (spec §6)

- The response schema is OSRM-compatible by design (same `code`/`routes`/`legs`/`steps`/
  `maneuver` fields and geometry formats), so `OsrmResponseDto`/`OsrmMappers` parse it
  unchanged and every validation rule applies: `code=Ok`, non-empty routes, real geometry,
  finite distance/duration, maneuver steps. Malformed responses are failures, never sample
  data.
- ≤1 routing request/second app-wide (`RequestRateLimiter`), bounded retries with
  exponential backoff for transient failures only (network/timeout/5xx/429) — never for
  invalid requests.
- Parsing/validation runs off the main thread (`Dispatchers.Default`); routing keeps its
  tighter OkHttp timeouts so hung reroutes fail fast.
- A missing token fails fast with a message naming `mappls.accessToken` — no unauthenticated
  request is ever fired.

## 4. What is NOT integrated (yet)

- The Mappls **Navigation SDK** is not bundled; turn-by-turn remains the in-app
  `NavigationEngine` ([docs/navigation-engine.md](navigation-engine.md)). Routes are requested
  with WGS84 coordinates — the API's eLoc waypoint support stays unused.
- The keyless OSRM path remains fully supported (see
  [docs/routing-provider-evaluation.md](routing-provider-evaluation.md) for the OSRM notes).
