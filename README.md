# Tessera Fleet

Real-Time Dispatch & Operations Platform. A live map and nearest-available-vehicle
assignment for a small fleet (20–200 vehicles), built to the SRS in `../srs/`.

The system is two cooperating layers (SRS §2.1):

- **Live layer** — an in-memory geospatial index (Redis) holding every vehicle's
  current position and status. Serves the dispatcher map and nearest-vehicle
  search with no disk I/O in the decision path.
- **Durable layer** — PostgreSQL + PostGIS + TimescaleDB for history and
  reporting. Written asynchronously, never in the critical path. *Arrives in
  Phase 2.*

## Status: Phase 1 complete

Phase 1 delivers the live layer and dispatcher map (SRS §8):

| Req | Delivered |
|-----|-----------|
| FR-1.1 | Live map, vehicles colour-coded by status (available / en route / on site / offline) |
| FR-1.2 | Positions refresh in the UI within ~1 s of ingestion (WebSocket push) |
| FR-1.3 | Status filter on the map |
| FR-1.4 | Vehicle detail: current job, ETA, recent status history |
| FR-2.1 | Job location (map click) → ranked nearest-available shortlist |
| FR-2.2 | Ranking by **real road-network travel time** (OSM graph + Dijkstra), not straight-line |
| FR-2.3 | Shortlist returns well under 1 s |
| FR-2.4 | Assign a job to a vehicle in one action |
| FR-7 | Data-source transparency panel; the position feed is disclosed as a substitute |
| NFR-7 | All API and WebSocket endpoints require an authenticated session |

Out of Phase 1: geofencing/dwell (Phase 2), durable persistence (Phase 2),
reporting (Phase 3), address geocoding & trajectory replay (Phase 4).

## Layout

```
backend/    Spring Boot 3.4 (Java 21). Live layer, ingestion, routing, REST + WebSocket.
frontend/   Vite + React + TypeScript. Dispatcher dashboard (Leaflet + OSM).
infra/      Dockerfile, helper scripts.
docs/       Phase notes.
docker-compose.yml   Redis + Postgres/PostGIS/TimescaleDB + backend.
```

## Run it

### With Docker (preferred)

```bash
cd frontend && npm install && npm run build && cd ..
docker compose up --build
```

Open <http://localhost:8080>. Sign in with `dispatch` / `dispatch` (or `ops` / `ops`).
The `demo` profile assigns a few jobs on startup so every status colour appears.

### Without Docker (local dev)

Terminal 1 — Redis (uses the binary bundled in the test dependency; run
`mvn -q -f backend/pom.xml test-compile` once first to download it):

```bash
pwsh ./infra/scripts/run-local-redis.ps1
```

Terminal 2 — backend:

```bash
cd backend && SPRING_PROFILES_ACTIVE=demo mvn spring-boot:run
```

> Port 8080 in use? Some machines run an Oracle TNS listener there. Start the
> backend with `PORT=8090 …` and point the frontend at it with
> `TESSERA_BACKEND=http://localhost:8090`.

Terminal 3 — frontend dev server (proxies to the backend):

```bash
cd frontend && npm install && npm run dev
```

Open <http://localhost:5173>.

## Live position feed (SRS §2.6, FR-7)

The default feed is a **deterministic simulator** that moves synthetic vehicles
along a real OpenStreetMap road network — disclosed in-product as *not real data*.
To use a real public GTFS-Realtime `VehiclePositions` feed instead:

```bash
TESSERA_POSITION_SOURCE=GTFS_REALTIME \
TESSERA_GTFS_URL=https://<agency>/vehiclepositions.pb \
TESSERA_GTFS_AGENCY="<Agency name>" \
# optional: TESSERA_GTFS_KEY=... TESSERA_GTFS_KEY_HEADER=x-api-key
```

## Test

```bash
cd backend && mvn verify        # 24 unit + 13 integration (embedded Redis, no Docker)
cd frontend && npm test         # 9 component/client tests
```

## Road graph

`backend/src/main/resources/roadgraph/roadgraph.json` is real OSM data (ODbL) for
a downtown-Boston demo area, built by `infra/scripts/build_roadgraph.py`. Rebuild
or retarget:

```bash
python infra/scripts/build_roadgraph.py \
  --bbox <south,west,north,east> --name "<Area>" \
  --out backend/src/main/resources/roadgraph/roadgraph.json
```
