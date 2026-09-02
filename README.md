# Tessera Fleet

Real-Time Dispatch & Operations Platform. A live map and nearest-available-vehicle
assignment for a small fleet (20–200 vehicles), built to the SRS in `../srs/`.

The system is two cooperating layers (SRS §2.1):

- **Live layer** — an in-memory geospatial index (Redis) holding every vehicle's
  current position and status. Serves the dispatcher map and nearest-vehicle
  search with no disk I/O in the decision path.
- **Durable layer** — PostgreSQL + PostGIS + TimescaleDB for history and
  reporting. Written asynchronously (write-behind), never in the critical path.
  Behind a `DurableStore` seam with an in-memory default, so the system runs with
  no database and live dispatch is unaffected if the database is down (NFR-3).

## Status: Phases 1–3 complete

**Phase 1 — live layer & dispatcher map (SRS §8)**

| Req | Delivered |
|-----|-----------|
| FR-1.1 | Live map, vehicles colour-coded by status (available / en route / on site / offline) |
| FR-1.2 | Positions refresh in the UI within ~1 s of ingestion (WebSocket push) |
| FR-1.3 | Status filter on the map |
| FR-1.4 | Vehicle detail: current job, ETA, recent status history |
| FR-2.1–2.4 | Map-click job location → road-network-ranked shortlist (< 1 s) → one-action assign |
| FR-7 | Data-source transparency panel; substitute feeds disclosed plainly |
| NFR-7 | All API and WebSocket endpoints require an authenticated session |

**Phase 2 — geofencing & durable persistence (SRS §8)**

| Req | Delivered |
|-----|-----------|
| FR-3.1 | Define a customer site as a polygon (draw on map) or a centre + radius |
| FR-3.2 | Automatic enter/exit detection per position fix, timestamped, recorded |
| FR-3.3 | Dwell time computed and stored on each exit |
| FR-3.4 | Debounced boundary transitions — a crossing that reverses within the window is ignored |
| FR-3.5 | Dispatcher alert when dwell exceeds a per-site (or default) threshold; Alerts feed + acknowledge |
| SRS §3.1 | Every position and geofence event written durably via a bounded write-behind queue |
| SRS §2.5 / NFR-3 | Queue-full or DB-down → drop + count, health degraded; live dispatch keeps running |

**Phase 3 — operations reporting (SRS §8)**

| Req | Delivered |
|-----|-----------|
| FR-4.1 | On-time arrival % — filterable by route, driver, site, date range. "Arrival" = geofence ENTER at the job's destination site; "on time" = within the ETA-at-assignment plus a grace window |
| FR-4.2 | Average dwell time per site, filterable by date range |
| FR-4.3 | Trend indicators vs the immediately preceding period of equal length, on both metrics |
| FR-4.4 | Reports are marked **provisional** and a banner is shown until an explicit data-sufficiency gate is met (min collection days + min completed jobs — the Appendix B open item, now defined in `tessera.reporting.*`) |
| SRS §5.3 | Reporting served request/response, not real-time |

Out now: address geocoding & trajectory replay (Phase 4).

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

Open <http://localhost:5173>. Without the `durable` profile the backend uses an
in-memory durable store (history lost on restart; live dispatch unaffected).

## Durable layer (Phase 2, SRS §3.1)

By default the durable store is **in-memory**. For real persistence — PostgreSQL
+ PostGIS + TimescaleDB — run with the `durable` profile against the compose `db`
service (or any such database):

```bash
SPRING_PROFILES_ACTIVE=demo,durable \
DB_URL=jdbc:postgresql://localhost:5432/tessera DB_USER=tessera DB_PASSWORD=tessera \
mvn -f backend/pom.xml spring-boot:run
```

Flyway creates the schema (`positions` hypertable, `sites` with a GiST index,
`geofence_events`, `jobs`). `docker compose up` runs the app with this profile.

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
cd backend && mvn verify   # 66 unit + 23 integration (embedded Redis + in-memory durable, no Docker)
                           # + 5 PostGIS/TimescaleDB ITs, auto-skipped when Docker is absent
cd frontend && npm test    # 20 component/client tests
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
