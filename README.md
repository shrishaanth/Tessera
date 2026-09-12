# Tessera Fleet

Real-Time Fleet Monitoring & Operations Platform. A live map, geofencing and
dwell-time reporting for a small fleet (20–200 vehicles). Positions come from a
**pre-generated, physically validated trajectory dataset** (default) or a **real
public GTFS-Realtime feed**. Built to the SRS in `../srs/`.

The system is two cooperating layers (SRS §2.1):

- **Live layer** — an in-memory geospatial index (Redis) holding every vehicle's
  current position and status. Serves the live map with no disk I/O in the path.
- **Durable layer** — PostgreSQL + PostGIS + TimescaleDB for history and
  reporting. Written asynchronously (write-behind), never in the critical path.
  Behind a `DurableStore` seam with an in-memory default, so the system runs with
  no database and the live layer is unaffected if the database is down (NFR-3).

> **Scope note.** The dispatch feature — one-click job assignment,
> nearest-available-vehicle ranking, and the on-time-arrival report — was removed,
> along with the old live simulator whose vehicles wandered at random and
> teleported across the map. Positions now come from a **pre-generated,
> physically validated trajectory dataset** (default) or a **real GTFS-Realtime
> feed**, and the product keeps only the features that data actually supports.

## Features

**Live map (SRS §8, FR-1)**

| Req | Delivered |
|-----|-----------|
| FR-1.1 | Live map, vehicles colour-coded by status (active / on site / offline) |
| FR-1.2 | Positions refresh in the UI within ~1 s of ingestion (WebSocket push) |
| FR-1.3 | Status filter on the map |
| FR-1.4 | Vehicle detail: current site, recent geofence events, status history |
| FR-7 | Data-source transparency panel; the substitute feed disclosed plainly |
| NFR-7 | All API and WebSocket endpoints require an authenticated session |

**Geofencing & durable persistence (FR-3)**

| Req | Delivered |
|-----|-----------|
| FR-3.1 | Define a customer site as a polygon (draw on map) or a centre + radius |
| FR-3.2 | Automatic enter/exit detection per position fix, timestamped, recorded |
| FR-3.3 | Dwell time computed and stored on each exit |
| FR-3.4 | Debounced boundary transitions — a crossing that reverses within the window is ignored |
| FR-3.5 | Alert when dwell exceeds a per-site (or default) threshold; Alerts feed + acknowledge |
| SRS §3.1 | Every position and geofence event written durably via a bounded write-behind queue |
| SRS §2.5 / NFR-3 | Queue-full or DB-down → drop + count, health degraded; the live layer keeps running |

**Operations reporting (FR-4)**

| Req | Delivered |
|-----|-----------|
| FR-4.2 | Average dwell time per site, filterable by site and date range |
| FR-4.3 | Trend indicator vs the immediately preceding period of equal length |
| FR-4.4 | The report is marked **provisional** with a banner until a data-sufficiency gate is met (min collection days + min recorded site visits — the Appendix B open item, defined in `tessera.reporting.*`) |
| SRS §5.3 | Reporting served request/response, not real-time |

**Search & trajectory replay (FR-6, FR-5)**

| Req | Delivered |
|-----|-----------|
| FR-6.1 | Address autocomplete via Nominatim (SRS §5.2), server-side rate-limited + cached |
| FR-6.2 | A picked suggestion carries real coordinates → the map flies straight to the point |
| FR-6.3 | Fuzzy search of known customer site names (PostgreSQL FTS + `pg_trgm` in prod, in-memory trigram otherwise) |
| FR-5.1 | Ops-manager Replay view: pick a vehicle + a date → its full recorded path drawn on the map (stride-sampled for the browser) |
| FR-5.2 | Playback: play/pause, scrub slider, 1×/8×/32× speed, a moving marker showing speed |

Phase 5 (Elasticsearch / wide-column storage) is conditional on NFR-4's
measured-load trigger and is out of scope until then.

## Layout

```
backend/    Spring Boot 3.4 (Java 21). Live layer, ingestion, geofencing, REST + WebSocket.
            com.tessera.fleet.dataset — trajectory generator + reader.
frontend/   Vite + React + TypeScript. Operations dashboard (Leaflet + OSM).
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

Open <http://localhost:8090>. Sign in with `dispatch` / `dispatch` (or `ops` /
`ops`). 24 vehicles start driving their rounds immediately; the `demo` profile
also seeds a few customer sites and back-fills synthetic site-visit history so the
dwell report renders straight away.

### Without Docker (local dev)

Terminal 1 — Redis (uses the binary bundled in the test dependency; run
`mvn -q -f backend/pom.xml test-compile` once first to download it):

```bash
powershell -ExecutionPolicy Bypass -File infra/scripts/run-local-redis.ps1
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
in-memory durable store (history lost on restart; the live layer is unaffected).

## Live position feed (SRS §2.6, FR-7)

Two sources ship. Select with `TESSERA_POSITION_SOURCE`.

### `DATASET` (default) — generated, physically validated trajectories

Vehicles replay a pre-generated dataset at 1 Hz. It is **simulated data, disclosed
as such in-product** — but it is generated to be physically plausible rather than
merely random, which is what the previous live simulator got wrong:

- Every position is a point on a real **OpenStreetMap road edge**; a vehicle only
  ever moves onto an adjacent edge of its planned route, so it cannot teleport.
- Vehicles drive **shortest-path closed rounds** — depot → stops → depot — not a
  random walk.
- The speed profile is built from each edge's **real speed limit** plus a corner
  limit, smoothed by a backward pass so braking is always feasible, then
  integrated forward under an **acceleration limit**.
- Vehicles **hold at signalised intersections** and **dwell at customer stops**,
  which is also what makes the geofence dwell data meaningful.
- Every vehicle starts and ends parked at its depot, so the track **loops
  seamlessly**.

The generator refuses to write a dataset that violates those invariants, and
`FleetDatasetPlausibilityTest` re-checks the committed file on every build. The
shipped 24-vehicle, 1-hour track measures:

| Check | Value | Limit |
|---|---|---|
| Largest single-tick displacement | **13.4 m** | 26.3 m |
| Largest tick-to-tick acceleration | **2.60 m/s²** | 3.6 m/s² |
| Top speed | **48.3 km/h** | 90 km/h |
| Ticks with a vehicle in motion | **55.2 %** | ≥ 25 % |
| Loop-seam discontinuity | **0.000 m** | ≤ 1 m |

Regenerate or retune it (after `mvn -f backend/pom.xml compile`):

```bash
mvn -q -f backend/pom.xml dependency:build-classpath -Dmdep.outputFile=target/cp.txt
java -cp "backend/target/classes:$(cat backend/target/cp.txt)" \
  com.tessera.fleet.dataset.FleetDatasetGenerator \
  --vehicles 24 --ticks 3600 --seed 20260909 \
  --out backend/src/main/resources/dataset/fleet-round.ndjson.gz
```

### `GTFS_REALTIME` — a real public transit feed

Genuinely real, continuously-updating positions, defaulting to the **MBTA**'s
free, no-key feed. Per FR-7.2 it is real data, but it comes from **transit
vehicles** standing in for a private fleet's telematics (not publicly available)
— disclosed in-product on the data-source panel.

```bash
TESSERA_POSITION_SOURCE=GTFS_REALTIME \
TESSERA_GTFS_URL=https://cdn.mbta.com/realtime/VehiclePositions.pb \
TESSERA_GTFS_AGENCY="MBTA" \
TESSERA_OFFLINE_AFTER_SECONDS=120 \
# optional: TESSERA_GTFS_KEY=... TESSERA_GTFS_KEY_HEADER=x-api-key
```

> Raise `TESSERA_OFFLINE_AFTER_SECONDS` for a transit feed — its vehicles report
> every ~5–30 s and drop in and out, so the 1 Hz default marks many of them
> offline.

## Durable layer (SRS §3.1)

By default the durable store is **in-memory**. For real persistence — PostgreSQL
+ PostGIS + TimescaleDB — run with the `durable` profile against the compose `db`
service (or any such database):

```bash
SPRING_PROFILES_ACTIVE=demo,durable \
DB_URL=jdbc:postgresql://localhost:5432/tessera DB_USER=tessera DB_PASSWORD=tessera \
mvn -f backend/pom.xml spring-boot:run
```

Flyway creates the schema (`positions` hypertable, `sites` with a GiST index,
`geofence_events`). `docker compose up` runs the app with this profile.

## Address geocoding (FR-6)

Address autocomplete uses **Nominatim** (SRS §5.2). By default it calls the public
`nominatim.openstreetmap.org`, which is rate-limited to ~1 req/s — the backend
throttles and caches accordingly. For production volume, run a self-hosted
Nominatim and point at it:

```bash
NOMINATIM_URL=https://nominatim.internal NOMINATIM_UA="tessera-fleet/1.0 (ops@acme.example)"
```

If geocoding is unavailable the search box says so.

## Test

```bash
cd backend && mvn verify   # 77 unit + 25 integration (embedded Redis + in-memory durable, no Docker)
                           # + 6 PostGIS/TimescaleDB ITs, auto-skipped when Docker is absent
cd frontend && npm test    # 24 component/client tests
```

`FleetDatasetPlausibilityTest` asserts the committed trajectory dataset is
physically sane (no teleports, bounded acceleration, seamless loop). Integration
tests never reach a real feed — `AbstractRedisIntegrationTest` points the position
source at a dead local address so the fleet starts empty.

## Road graph

`backend/src/main/resources/roadgraph/roadgraph.json` is real OSM data (ODbL) for
a downtown-Boston demo area, built by `infra/scripts/build_roadgraph.py`. It is a
disclosed data source and labels the map's area. Rebuild or retarget:

```bash
python infra/scripts/build_roadgraph.py \
  --bbox <south,west,north,east> --name "<Area>" \
  --out backend/src/main/resources/roadgraph/roadgraph.json
```
