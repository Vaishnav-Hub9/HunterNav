# Backend

HunterNav V1 has **no first-party backend**. It talks directly to open services:

| Concern    | Service (default)                        | Config key          | Notes                                   |
|------------|------------------------------------------|---------------------|-----------------------------------------|
| Routing    | OSRM public demo `router.project-osrm.org` | `osrm.baseUrl`     | **Development only** — rate limited, no SLA |
| Geocoding  | Nominatim `nominatim.openstreetmap.org`  | `geocoder.baseUrl`  | Low volume; User-Agent sent by the app  |
| Map tiles  | OpenFreeMap `tiles.openfreemap.org`      | `map.styleUrl`      | Free vector tiles, no API key           |

All three are centralized in `BuildConfig` (see `app/build.gradle.kts` /
`local.properties.example`) so they can be pointed at self-hosted instances without code
changes.

**Do not commit secrets.** V1 requires none — but if you add a commercial provider later,
keep its key out of git (local properties / CI secrets).

## Why self-host later?

- The OSRM demo server and Nominatim public instance are explicitly for development volume.
- Real product traffic needs an SLA, higher rate limits and data locality.
- A motorcycle-specific routing profile would live on a GraphHopper instance.

See [future-self-hosting.md](future-self-hosting.md) for the planned setup.
