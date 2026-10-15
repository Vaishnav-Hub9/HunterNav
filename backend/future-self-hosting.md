# Future Self-Hosting Plan (OSRM / Valhalla + Nominatim)

**Status: future option, not wired.** HunterNav's production stack is Google-only
([docs/google-maps.md](../docs/google-maps.md)); the pre-migration `osrm.baseUrl` /
`geocoder.baseUrl` config switches no longer exist. Reintroducing a self-hosted engine means
implementing new `RoutingProvider` / `GeocodingProvider` classes behind the existing domain
interfaces (see the README's "Switching routing providers") — a contained data-layer change,
not a config flag. This document is the blueprint for that work.

## Target pipeline

```
OSM PBF extract (e.g. Telangana region)
   ↓  osrm-extract  -p profiles/car.lua      (or a motorcycle profile)
   ↓  osrm-partition / osrm-contract         (MLD / CH preparation)
osrm-routed  ← serves route/v1/driving on :5000
   ↓
A future OsrmRoutingProvider with baseUrl=https://your.host:5000/
```

A parallel Nominatim instance would serve a future `NominatimGeocodingProvider`:

```
OSM PBF → Nominatim import (PostgreSQL/PostGIS) → nominatim serve :8900
```

For a motorcycle-oriented engine, prefer Valhalla (dedicated `motorcycle` costing — see
[../docs/routing-provider-evaluation.md](../docs/routing-provider-evaluation.md)) or
GraphHopper with a custom profile.

## Important guardrails

- **Do not download OSM planet/PBF files during Android builds.** Preprocessing is an
  ops task, never part of Gradle. Nothing in `app/` fetches OSM extracts.
- Never commit PBF/planet files or huge data to this repository (see `.gitignore` policy in
  the README's repository structure).
- Use regional extracts (Geofabrik: Telangana / India) — a state extract is plenty for the
  Hyderabad test region.
- Host behind TLS; keep the base URL as the new provider's single config knob — no
  application logic changes beyond the data-layer provider.

## Sketch (Docker, for later reference)

```bash
# one-time data prep
wget https://download.geofabrik.de/asia/india/telangana-latest.osm.pbf
docker run -v $PWD:/data osrm/osrm-backend osrm-extract -p /data/car.lua /data/telangana-latest.osm.pbf -o /data/telangana
docker run -v $PWD:/data osrm/osrm-backend osrm-partition /data/telangana.osm
docker run -v $PWD:/data osrm/osrm-backend osrm-customize /data/telangana.osm

# serve
docker run -t -p 5000:5000 -v $PWD:/data osrm/osrm-backend osrm-routed --algorithm mld /data/telangana.osm
```

Then point the new provider's `baseUrl` at `http://<host>:5000/` (via local properties or a
build-flavor override).

## GraphHopper / Valhalla (motorcycle profile)

The same host pattern applies: GraphHopper or Valhalla can run a motorcycle-oriented
profile; point the new provider's config at it **after** implementing the provider (the
routing interface stays identical — see README "Switching routing providers").
