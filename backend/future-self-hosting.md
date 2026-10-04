# Future Self-Hosting Plan (OSRM + Nominatim)

Not required for V1 — documented so the repository and endpoints are ready for it.

## Target pipeline

```
OSM PBF extract (e.g. Telangana region)
   ↓  osrm-extract  -p profiles/car.lua      (or a motorcycle profile)
   ↓  osrm-partition / osrm-contract         (MLD / CH preparation)
osrm-routed  ← serves route/v1/driving on :5000
   ↓
HunterNav  with  osrm.baseUrl=https://your.host:5000/
```

A parallel Nominatim instance serves `geocoder.baseUrl`:

```
OSM PBF → Nominatim import (PostgreSQL/PostGIS) → nominatim serve :8900
```

## Important guardrails

- **Do not download OSM planet/PBF files during Android builds.** Preprocessing is an
  ops task, never part of Gradle. Nothing in `app/` fetches OSM extracts.
- Never commit PBF/planet files or huge data to this repository (see `.gitignore` policy in
  the README's repository structure).
- Use regional extracts ( Geofabrik: Telangana / India ) — a state extract is plenty for
  the Hyderabad test region.
- Host behind TLS; keep `osrm.baseUrl` / `geocoder.baseUrl` as the only switches — no
  application code changes needed.

## Sketch (Docker, for later reference)

```bash
# one-time data prep
wget https://download.geofabrik.de/asia/india/telangana-latest.osm.pbf
docker run -v $PWD:/data osrm/osrm-backend osrm-extract -p /data/car.lua /data/telangana-latest.osm.pbf -o /data/telangana
docker run -v $PWD:/data osrm/osrm-backend osrm-partition /data/telangana.osrm
docker run -v $PWD:/data osrm/osrm-backend osrm-customize /data/telangana.osrm

# serve
docker run -t -p 5000:5000 -v $PWD:/data osrm/osrm-backend osrm-routed --algorithm mld /data/telangana.osrm
```

Then set `osrm.baseUrl=http://<host>:5000/` in `local.properties` (or the production env
override for your build flavor).

## GraphHopper (motorcycle profile)

The same host pattern applies: GraphHopper can run a motorcycle-oriented profile; point
`osrm.baseUrl`-equivalent config at it **after** implementing `GraphHopperRoutingProvider`
(the routing interface stays identical — see README "Switching routing providers").
