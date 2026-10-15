# Routing Provider Evaluation — self-hosted engines vs. the current Google stack

Status: living evaluation (Oct 2026). The production provider since the Google-only
migration is the **Google Routes API v2** (`GoogleRoutesProvider`, traffic-aware
TWO_WHEELER — see [google-maps.md](google-maps.md)); the OSRM section below is retained as
historical context and as the blueprint for a self-hosted option (see
[../backend/future-self-hosting.md](../backend/future-self-hosting.md)).
`RoutingProvider` (domain/repository) remains the single abstraction, so any provider below
can be benchmarked or swapped without touching the UI or navigation engine.

## Historical: OSRM public demo (pre-Google migration)

- Endpoint: `https://router.project-osrm.org/route/v1/driving/…` (configurable via
  `osrm.baseUrl`). Profile: `driving` (car) — **not motorcycle-optimized**; a cycling profile
  would not be a substitute either.
- Public demo server: development volume only, rate-limited, no SLA. The app enforces
  ≤1 req/s, bounded retries and reroute cooldown to stay polite.
- Distance/ETA come from the validated OSRM response and actual route progress — never from
  hardcoded factors. There is **no live traffic**; ETA is a free-flow-speed estimate and is
  labeled as an estimate in the UI.

## Candidate: self-hosted Valhalla (Telangana extract)

Research findings (sources at the end):

| Question | Finding |
|---|---|
| Motorcycle routing? | **Yes — dedicated `motorcycle` and `motor_scooter` costing** in Valhalla's source (`src/sif/motorcyclecost.cc`), not an alias of `auto`. No formal stability guarantee: route quality against Telangana road tagging must be benchmarked before trusting it. |
| Instructions | Odin generates maneuvers + human-readable narration comparable in purpose to OSRM steps. **API is not OSRM-compatible** — a `ValhallaRoutingProvider` would need its own request builder + response mapper behind the existing interface (a contained data-layer task). |
| Map matching | Valhalla includes Meili (map matching) and correlates request locations to graph edges (road snapping) — comparable to OSRM for our reroute use. |
| Latency | No published benchmark for a Telangana-sized graph; planning expectation is tens to a few hundred ms per request on modest hardware — **must be measured on the actual VM**. This would beat the public OSRM demo server's typical 1–15 s and cut most reroute latency. |
| Hosting | One small VM: ~4 vCPU / 16 GB RAM / 100 GB SSD (16 GB covers the initial graph build; serving needs less). ~US$30–70/month. Docker: `ghcr.io/valhalla/valhalla-scripted` with the PBF mounted at `/custom_files`. |
| Data | Geofabrik has **no Telangana-only extract**; use the India → Southern Zone PBF (532 MB) and clip to a Telangana boundary polygon before building tiles. |
| Traffic | **Open-source Valhalla provides NO live traffic out of the box.** ETA uses graph speed assumptions. Real traffic needs a licensed speed feed mapped onto Valhalla edges plus recurring traffic-tile updates — a separate project, not a config flag. Do not claim traffic-aware ETA from this deployment. |
| License | Valhalla: MIT. OSM data: ODbL (attribution + share-alike obligations on derived databases). Both fine for this app; OSM attribution is already displayed. |

## Recommendation

1. **Keep Google Routes API v2 behind `RoutingProvider` for production** — it is validated
   end-to-end and provides live traffic, which no self-hosted open-source engine here does.
2. **Deploy a regional Valhalla instance when self-hosting matters** (cost control, data
   locality, road-grade reroute latency without per-request billing). Work items:
   provision the VM above, clip the Southern Zone extract, run the scripted Docker image,
   then implement `ValhallaRoutingProvider` (request builder + maneuver mapping +
   spoken-instruction mapping) and benchmark against `GoogleRoutesProvider` using the same
   fixture trips. Nothing in the UI changes.
3. **Traffic-aware ETA is out of scope** until a licensed feed exists; the UI keeps labeling
   ETA as an estimate.

## Sources

- Valhalla repo & costing: https://github.com/valhalla/valhalla , https://github.com/valhalla/valhalla/blob/master/src/sif/motorcyclecost.cc
- Docker deployment: https://github.com/valhalla/valhalla/blob/master/docker/README.md
- Geofabrik India extracts: https://download.geofabrik.de/asia/india.html , https://download.geofabrik.de/asia/india/southern-zone.html
- OSM wiki (Valhalla, licensing): https://wiki.openstreetmap.org/wiki/Valhalla
