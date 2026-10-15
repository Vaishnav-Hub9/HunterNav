# Backend

HunterNav has **no first-party backend**. It talks directly to Google Maps Platform behind a
single API key (see [docs/google-maps.md](../docs/google-maps.md)):

| Concern       | Service (default endpoint)             | Config key                   |
|---------------|-----------------------------------------|------------------------------|
| Map rendering | Maps SDK for Android                    | `google.maps.apiKey` (manifest meta-data) |
| Routing       | Routes API v2 `routes.googleapis.com`   | `google.maps.apiKey`, override `google.routes.baseUrl` |
| Geocoding     | Geocoding API `maps.googleapis.com`     | `google.maps.apiKey`, override `google.geocoding.baseUrl` |
| Display tiles | Map Tiles API `tile.googleapis.com`     | `google.maps.apiKey`         |

The REST endpoints are centralized in `BuildConfig` (see `app/build.gradle.kts` /
`local.properties.example`) so they can be pointed at a logging proxy without code changes.

**Do not commit secrets.** The API key lives only in your local `local.properties` (and the
signed app binary). Restrict it to the Android package + signing-cert SHA-1.

## Why self-host later?

- Google Maps Platform is usage-billed (routing, geocoding and tiles each bill per request).
- A self-hosted engine gives data locality and predictable latency, at the cost of map/route
  quality and traffic data you no longer get.
- A motorcycle-specific routing profile would live on a self-hosted GraphHopper/Valhalla
  instance.

Note: there is **no config-key switch** for self-hosted engines anymore — the old
`osrm.baseUrl`/`geocoder.baseUrl` switches belonged to providers that were removed in the
Google-only migration. Reintroducing a self-hosted stack means implementing
`RoutingProvider` / `GeocodingProvider` implementations again.

See [future-self-hosting.md](future-self-hosting.md) for the planned setup and
[../docs/routing-provider-evaluation.md](../docs/routing-provider-evaluation.md) for the
engine evaluation.
