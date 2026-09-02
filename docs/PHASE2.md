# Phase 2 — Geofencing & durable persistence

Scope from SRS §8: *Customer site geofences, dwell-time capture, async
write-behind to the durable layer (FR-3).*

## What was built

### Durable layer (`com.tessera.fleet.durable`)

| Piece | Responsibility |
|-------|----------------|
| `DurableStore` | The seam (NFR-8). Save positions / geofence events, site CRUD, job write-through, history reads, `healthy()`. |
| `InMemoryDurableStore` | Default. The system runs with no database; history is process-memory only. |
| `PostgresDurableStore` | `JdbcTemplate` + explicit SQL. Geometry crosses as WKT (`ST_GeomFromText` / `ST_AsText`). Activated by the `durable` profile. |
| `DurableConfig` | Builds the DataSource / JdbcTemplate / Flyway by hand (Boot's JDBC auto-config is excluded) so nothing trips when no DB is configured. |
| `WriteBehindService` | Bounded queues + one daemon consumer. Non-blocking `offer*` from the hot path; batch drain; queue-full → drop + count; write failure → retry ×3 then drop, health degraded. Never blocks the producer (SRS §2.5). |
| `DurableHealthIndicator` | Actuator `OUT_OF_SERVICE` (not `DOWN`) when writes are failing — liveness is unaffected (NFR-3). |
| Flyway `V1__durable_schema.sql` | `postgis` + `timescaledb` extensions; `positions` hypertable (+ GiST index); `sites` (GiST); `geofence_events`; `jobs`. |

### Geofencing (`com.tessera.fleet.geofence`)

| Piece | Responsibility |
|-------|----------------|
| `SiteGeometry` | JTS polygon / metric-circle (64-gon) containment + WKT round-trip (lon/lat, SRID 4326). |
| `GeofenceEngine` | Pure per-(vehicle, site) state machine: `OUTSIDE → ENTERING → INSIDE → EXITING`. Debounce (FR-3.4) — a reversal within the window yields no event. Event timestamp and dwell clock use the *first* fix on the new side. Dwell alert once per visit past the threshold (FR-3.5). |
| `GeofenceService` | Feeds each fix to the engine; write-behind + WebSocket-broadcast the events; raise dwell alerts; reflect on-site state into the live layer (→ `ON_SITE`). Loads sites from the durable store on startup. |
| `SiteService` | Site CRUD → durable store → engine reload. |

### Alerts (`com.tessera.fleet.alert`)

`AlertService` — capped in-memory feed, broadcast on raise, acknowledge. Phase 2
raises `DWELL_EXCEEDED`.

### Wiring changes

- `IngestionService` now also calls `geofenceService.onPosition(...)` and
  `writeBehind.offerPosition(...)` for every fix.
- `LiveFleetService` gained `setOnSite` / `clearOnSite`; `Vehicle` carries
  `onSiteId`; `VehicleStatusResolver` already resolved `ON_SITE`.
- `JobService` writes jobs through to the durable store and rehydrates on startup.
- New endpoints: `/api/sites` (CRUD), `/api/alerts` (+ `/{id}/ack`),
  `/api/geofence-events`. `/api/vehicles/{id}` detail adds on-site name + recent
  site visits. `/api/system/status` adds durable + write-behind + geofence stats.
- `DataSourceService` discloses the durable store (in-memory vs PostGIS) per FR-7.

### Frontend

- `LiveStreamProvider` — one WebSocket for the app; exposes vehicles + a geofence
  activity feed + alerts (`fleet` / `geofence` / `alert` frames).
- Live map renders site boundaries (circles + polygons); a **Customer sites**
  panel lists sites and creates them by drawing a polygon or dropping a
  centre+radius; a **recent geofence activity** strip.
- New **Alerts** view (the deck's "Alert / Exception Feed") with acknowledge and
  a topbar unacknowledged badge.
- Vehicle detail shows "on site" and recent site visits.

## Key decisions

- **PostGIS/TimescaleDB is real** for compose / production; the `DurableStore`
  seam means every piece of Phase 2 logic is testable without it.
- **Docker was unavailable on the dev machine** (Docker Desktop crashes on
  startup — an "Inference manager" stale-pipe bug, reproduced with the AI feature
  disabled). So the real-SQL ITs are **Testcontainers, Docker-gated** — they skip
  cleanly here and run in CI; everything else runs on embedded Redis + the
  in-memory durable store.
- Geofence debounce is **time-based** ("minimum dwell before a transition is
  real"), event timestamp = the actual crossing fix, not the confirmation fix.
- Radius sites are materialised as a 64-point metric circle so containment uses
  the one fast point-in-polygon path.

## Tests

`mvn verify` — **52 unit + 19 integration**, green; **4 PostGIS ITs skipped**
(no Docker). `npm test` — **15**.

- unit: `SiteGeometry` (polygon/radius/WKT), `GeofenceEngine` (debounced
  enter/exit, jitter suppression, dwell, one-shot alert, reload), `WriteBehindService`
  (non-blocking, batching, drop-when-full, failing-store degradation + retry),
  `InMemoryDurableStore`, `SiteService`, `AlertService`.
- integration (embedded Redis + in-memory durable): `GeofenceFlowIT` (drive
  in → dwell → alert → drive out, ENTER/EXIT + dwell persisted, `ON_SITE` status,
  jitter produces nothing), `SiteApiIT` (polygon + radius CRUD, auth), 
  `DurablePersistenceIT` (simulator stream reaches the store, health OK).
- Docker-gated: `PostgisDurableStoreDockerIT` — real `postgis` + `timescaledb`
  extensions, `positions` hypertable, WKT round-trip, `ST_Contains`, upserts.
- frontend: `AlertsView`, `SitePanel`, plus the Phase 1 suite.

## Manual verification (this build)

Ran the full stack (backend `PORT=8090` `demo`, local Redis, Vite): 3 seeded
sites shown on the map; simulated vehicles produce live ENTER/EXIT with dwell in
the activity feed; "On site" count in the status filter; a dwell alert appears in
the Alerts view and the topbar badge; acknowledge clears it; `/api/system/status`
shows ~2000 positions written behind with zero drops and `healthy: true`; the
data-source panel discloses the in-memory store honestly.

## Deferred

- On-time %, dwell trends, trend indicators, trajectory replay — Phase 3 / 4.
- Job completion (`COMPLETED` / `completed_at`) is modelled but not yet driven —
  Phase 3 closes the loop when reporting needs it.
- The durable `positions` table has no retention policy yet — add a TimescaleDB
  retention job when Phase 3 defines the data-collection window (Appendix B).
