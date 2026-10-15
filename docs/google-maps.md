# Google Maps Platform Setup

Since the Google-only migration (Oct 2026), **Google Maps Platform is HunterNav's only
external data provider**. There is no Mappls, MapLibre, OSRM or Nominatim path left in the
app: every map render, route and place lookup comes from Google behind **one API key**.
Missing auth always fails loudly with a setup message — it never silently switches engines
(a different engine returns different distance/ETA numbers, which is the exact failure mode
this repo was migrated away from).

## APIs to enable

| API | Used for | Consumed by |
|-----|----------|-------------|
| **Maps SDK for Android** | The phone map | `ui/map/GoogleMapsSurface` / `GoogleMapView`, via manifest meta-data `com.google.android.geo.API_KEY` |
| **Routes API** (v2) | Traffic-aware two-wheeler routing | `data/routing/GoogleRoutesProvider` — `X-Goog-Api-Key` header |
| **Geocoding API** | Destination search + reverse geocoding (dropped pins) | `data/geocoding/GoogleGeocodingProvider` — `key` query parameter |
| **Map Tiles API** | 2D roadmap raster tiles for the handlebar display basemap | `display/tiles/GoogleMapTileProvider` — `key` query parameter + documented session flow |

Google Cloud Console: select/create a project → **Billing** (Maps Platform requires a billing
account) → **APIs & Services → Library** → enable the four APIs above →
**Credentials → Create credentials → API key** → restrict the key (see below).

## Key restrictions (do this)

- **Application restrictions → Android apps**: package `com.hunternav` plus the SHA-1 of your
  signing certificate (debug keystore for development, release keystore for production builds).
- **API restrictions**: only the four APIs listed above.
This keeps a leaked key from being burned on other projects or APIs.

## Configuration

The key lives only in `local.properties` (gitignored) — **never commit it**:

```properties
google.maps.apiKey=<your key>

# Optional endpoint overrides (defaults shown)
# google.routes.baseUrl=https://routes.googleapis.com
# google.geocoding.baseUrl=https://maps.googleapis.com
```

`app/build.gradle.kts` turns it into `BuildConfig.GOOGLE_MAPS_API_KEY` and into the
manifest placeholder for the Maps SDK. See [local.properties.example](../local.properties.example).

## Routing behavior (`GoogleRoutesProvider`)

- `POST https://routes.googleapis.com/directions/v2:computeRoutes`
- `travelMode: TWO_WHEELER` — Google's documented motorcycle/scooter mode;
  `routingPreference: TRAFFIC_AWARE` — distance/ETA adjusted for live traffic at request time.
- `X-Goog-FieldMask` limits the response to the fields the mapper consumes (smaller payloads;
  Google bills per returned field beyond the mask).
- **Documented traffic fallback**: if the traffic-aware request is rejected as invalid
  (HTTP 400), the provider retries **once** with the default (best-guess) routing preference
  and logs `ROUTE_TRAFFIC_FALLBACK`. The route stays real — only the traffic adjustment is
  missing. Auth (401/403), quota (429) and server (5xx) failures never trigger the fallback.
- App-level rate limit ≤1 request/second; 3 attempts max; exponential backoff (700 ms base);
  retries only for network/timeout/429/5xx — never for invalid requests.
- Every response is validated (routes array, overview polyline, finite distance/duration,
  maneuver steps) before it can be shown. A malformed response is a failure, never sample data.

## Geocoding behavior (`GoogleGeocodingProvider`)

- `GET https://maps.googleapis.com/maps/api/geocode/json` with `region=IN`, `language=en`
  (search: `address=…`; reverse: `latlng=…` plus a `result_type` filter).
- **Reverse geocoding is label-only**: the pressed coordinate stays authoritative — Google's
  response describes the nearest mapped entity which can sit metres away from the pin.
  No result ⇒ the destination becomes a "Dropped pin" at the pressed coordinate, not a failure.
- Results without usable coordinates are dropped, never routed to as a fake (0,0).
- ≤1 request/second app-wide; successful searches cached ~10 minutes (64 entries).
- **The rider's GPS position is never sent with ordinary text search** — a location bias is
  transmitted only when the caller explicitly supplies a proximity hint.
- Top-level status handling: `ZERO_RESULTS` is an empty success; `REQUEST_DENIED` and
  `OVER_QUERY_LIMIT` surface with messages that name the credential/quota problem.

## Map tiles (`GoogleMapTileProvider`)

- Documented session flow: `POST tile.googleapis.com/v1/createSession` →
  `GET /v1/2dtiles/{z}/{x}/{y}?session=…&key=…`.
- Session tokens are refreshed 5 minutes before their reported expiry and immediately when a
  tile request is rejected as expired.
- In-memory cache only, honoring `Cache-Control: max-age`; the "Map data © Google"
  attribution must remain visible wherever tiles are shown.

## Without a key

- Routing and destination search report a clear configuration error through the normal error
  path (`UnconfiguredRoutingProvider` / `UnconfiguredGeocodingProvider` in `di/AppContainer`).
- The phone map area stays blank (the Maps SDK renders nothing useful unauthenticated).
- JVM unit tests need **no key**: all providers are exercised with deterministic fake HTTP
  transports and captured fixture responses.

## Debugging

Debug builds log to Logcat: `ROUTING_PROVIDER`, `ROUTE_REQUEST` / `ROUTE_RESPONSE` /
`ROUTE_TRAFFIC_FALLBACK`, `GEOCODING_REQUEST` / `GEOCODING_RESULT`, `TILE_SESSION`.
HTTP 401/403 messages explicitly say whether the key was rejected or an API is not enabled;
429 is quota/rate limiting.

## Attribution

"Map data © Google" is shown in *Settings → About & attribution*, and the Maps SDK renders
its own built-in attribution on the map. Both must remain visible — this is a Google Maps
Platform terms requirement, not decoration.
