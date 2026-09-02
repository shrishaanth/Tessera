# Phase 1 — Live layer & dispatcher map

Scope from SRS §8: *In-memory geospatial index, live map, nearest-available-vehicle
assignment (FR-1, FR-2). A complete, demonstrable slice on its own.*

## What was built

### Backend (`com.tessera.fleet`)

| Package | Responsibility |
|---------|----------------|
| `model` | Immutable records: `PositionReport`, `Vehicle`, `Job`, `NearestVehicle`, `StatusChange`, `VehicleStatus` |
| `ingestion` | `PositionSource` interface + `SimulatedPositionSource` (deterministic, real OSM edges) and `GtfsRealtimePositionSource` (real transit feed). `IngestionService` pulls on a fixed cadence and writes to the live layer; feed errors are logged and skipped, never propagated (SRS §2.5). |
| `live` | `LiveFleetService` — the Redis-backed live layer: GEOADD + a hash per vehicle + a capped status-history list. `VehicleStatusResolver` derives the dispatcher-visible status. `LiveBroadcastService` pushes a fleet snapshot over WebSocket each tick. |
| `routing` | `RoadGraph` (CSR, forward + reverse edges) loaded from OSM JSON; `TravelTimeService` runs one reverse-Dijkstra rooted at the job location. |
| `nearest` | `NearestVehicleService` — GEOSEARCH-radius prefilter (grown geometrically) → road-network travel-time ranking. |
| `job` | `JobService` — in-memory job store (durable in Phase 2), single-action assignment. |
| `transparency` | `DataSourceService` — FR-7 disclosure list, derived from the active feed. |
| `web` | REST controllers, `LiveWebSocketHandler`, session auth (`AuthController` + `SecurityConfig`), SPA forwarding. |

### Frontend (`frontend/src`)

Vite + React + TypeScript. Login → app shell (Live Map / Reports / Settings).
Live Map: Leaflet + OSM tiles, status-coloured markers, live WebSocket updates,
status filter, vehicle detail panel, click-to-create-job with a ranked shortlist
and one-click assign. Settings: the FR-7 data-source panel.

## Key decisions

- **Framework**: Spring Boot 3.4 (SRS Appendix B was open between Spring Boot /
  Javalin / Vert.x).
- **Position feed**: pluggable `PositionSource`; simulator is the default and is
  used by every automated test, so the suite needs no network.
- **GEORADIUS, not GEOSEARCH**: SRS §3.2 lists both; GEORADIUS keeps compatibility
  with Redis 5+ (the embedded test binary) while working identically on Redis 7
  (docker-compose).
- **Integration tests use embedded Redis**, not Testcontainers — the dev machine's
  Docker was unavailable, and a bundled native `redis-server` makes `mvn verify`
  self-contained on any JVM. Production/compose still targets Redis 7.
- **Road graph** is real OSM data (Overpass, ODbL) for a downtown-Boston demo
  area, committed as JSON so builds and tests are offline and reproducible.

## Tests

- `mvn verify` — 24 unit + 13 integration, green.
  - unit: geo math, road-graph load, Dijkstra correctness on a hand-built graph,
    status resolution, simulator determinism + bounds, GTFS parsing, nearest
    ranking (Mockito live layer + real graph).
  - integration (embedded Redis): live-layer apply/query/history/offline sweep;
    full dispatch API (auth 401 → login → fleet → nearest < 1 s → create+assign →
    conflict → data sources); WebSocket auth + snapshot delivery.
- `npm test` — 9: API client (credentials, error mapping, query building),
  status filter bar, new-job panel (shortlist render + one-click assign).

## Manual verification (this build)

Ran backend (`PORT=8090`, `demo`) + local Redis + Vite, in a browser:
sign-in, live map with 40 simulated vehicles moving on real roads, live status
counts, pick-location → ranked nearest shortlist → assign flips the vehicle to
EN_ROUTE with an ETA and a status-history entry, data-source panel shows the
simulator plainly marked SUBSTITUTE.

## Deferred to later phases

- Geofencing, dwell-time, async write-behind to the durable layer — Phase 2.
- On-time %, dwell trends, trend indicators — Phase 3.
- Address autocomplete / geocoding (Nominatim), trajectory replay — Phase 4.
- `ON_SITE` status is modelled but only produced once geofencing lands.
- CSRF is disabled (JSON API + `SameSite=Lax` session cookie); revisit if
  cookie-bearing cross-site POSTs become possible.
