# Phase 4 — Dispatcher search & trajectory replay

Scope from SRS §8: *Dispatcher search and geocoding (FR-6), incident-review
replay (FR-5).* This is the last functional phase; Phase 5 is conditional
(NFR-4).

## Address geocoding (FR-6.1, FR-6.2)

`com.tessera.fleet.geocoding`:

| Piece | Responsibility |
|-------|----------------|
| `GeocodingService` | Nominatim `/search` (jsonv2). Requires ≥ 3 chars; spaces upstream calls to `min-interval-ms` (public Nominatim is ~1 req/s); LRU cache with a 10-min TTL; sends the required `User-Agent`; any failure → empty list + `lastCallFailed` (graceful degradation — the map click still works). |
| `UrlFetcher` | A one-method HTTP-GET seam so the service is unit-testable without a network; the real bean is `HttpUrlFetcher`. |
| `GeocodeController` | `GET /api/geocode?q=&limit=` → `{query, results, degraded}`. |

`GeocodeResult` carries `lat`/`lon`, so a picked suggestion feeds the
nearest-vehicle search with no second round trip (FR-6.2). Point
`tessera.geocoding.base-url` at a self-hosted Nominatim to remove the rate limit.

## Fuzzy customer-site search (FR-6.3)

`DurableStore.searchSites(query, limit)`:

- **PostgreSQL** (`PostgresDurableStore`): SRS §3.2's full-text (`tsvector`,
  migration `V3`) **plus `pg_trgm`** for typo tolerance — `ILIKE` OR
  `plainto_tsquery` OR `similarity(name, ?) > 0.2`, ranked by
  `GREATEST(similarity, ts_rank)`.
- **In-memory** (`InMemoryDurableStore`): `TextSimilarity` — a trigram (Jaccard)
  score boosted for prefix / substring / token hits, punctuation- and
  case-insensitive.

`SiteSearchService` delegates; `GET /api/sites/search?q=` returns `SiteView`s
(the geocoded search box shows a "Customer sites" group alongside "Addresses").

## Trajectory replay (FR-5.1, FR-5.2)

- `DurableStore.trajectory(vehicleId, fromMs, toMs)` — ordered positions from the
  durable history (SRS §3.1). In-memory: filter + sort; Postgres: indexed range
  scan on `(vehicle_id, ts)`.
- `TrajectoryService` — `forDay(vehicleId, LocalDate)` (UTC day) or `between(...)`;
  stride-samples down to `tessera.replay.max-points` (default 3000), always
  keeping the first and last fix; reports `totalPoints` + `sampled`.
- `ReplayController` — `GET /api/replay/vehicles`, `GET /api/replay/trajectory?vehicleId=&date=YYYY-MM-DD`
  (or `from`/`to`).
- Frontend `ReplayView` (new **Replay** nav tab): vehicle + date pickers, a
  Leaflet map that draws the path polyline and auto-fits its bounds, and
  `PlaybackControls` — play/pause, a scrub slider, 1×/8×/32× speed, and a moving
  marker whose tooltip shows the speed at that point.

## Wiring changes

- `JobController.create` accepts an optional `route` (already in Phase 3's model);
  the search box passes the typed address as `destinationAddress`.
- `LiveMapView` extracts `startJobAt(lat, lon, address?)`, shared by a map click
  and a search pick; `MapCanvas` gained a `FlyTo` child that recenters on the
  picked point.
- `DataSourceService` discloses Nominatim (public vs self-hosted) per FR-7.

## Tests

`mvn verify` — **82 unit + 26 integration**, green; **7 PostGIS ITs skipped**
(no Docker). `npm test` — **27**.

- unit: `GeocodingServiceTest` (parse jsonv2, < 3 chars = no upstream, LRU cache,
  ≥ interval spacing, `User-Agent` sent, failure → empty + degraded),
  `TextSimilarityTest` (prefix > substring > token > unrelated, typo tolerance,
  punctuation folding), `TrajectoryServiceTest` (day bounds, vehicle filter,
  stride-sample keeps first/last), `InMemoryDurableStore` search + trajectory.
- integration: `SearchReplayApiIT` — geocode (stubbed `UrlFetcher`) needs auth
  then returns suggestions with real coords; fuzzy site search finds a site
  despite a typo; replay lists vehicles and returns a trajectory.
- Docker-gated: `PostgisDurableStoreDockerIT` — `pg_trgm` extension + `search_tsv`
  column present, full-text + trigram search, ordered trajectory query.
- frontend: `AddressSearchBox` (no query < 3 chars, grouped results after
  debounce, picked coords), `PlaybackControls` (scrub, play toggle, speed,
  Replay-at-end).

## Manual verification (this build)

Ran the stack (`demo`): typed "faneuil hall boston" → Nominatim suggestion →
picked it → map flew to Faneuil Hall and the nearest shortlist appeared;
"comon depo" (typo) → "Common Depot" in the site group; Replay tab → SIM-001 /
today → 234-point path drawn, Play advanced the marker (25/234 after 3 s at 8×),
speed tooltip on the head marker.

## Notes / deferred

- FR-6.3 uses in-memory fuzzy matching by default because customer sites are a
  bounded set held in the geofence engine's memory; the specced PostgreSQL FTS
  path exists and is Docker-IT-tested for scale.
- The public-Nominatim rate limit makes per-keystroke autocomplete deliberately
  conservative (400 ms debounce + 3-char minimum + cache); a self-hosted
  Nominatim lifts it.
- Replay reads positions accumulated in the durable store during a running
  session; the demo's synthetic history back-fills jobs/visits but not positions,
  so replay shows the live simulator's path.
