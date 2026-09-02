# Phase 3 — Operations reporting

Scope from SRS §8: *Historical dashboards (FR-4). Not to be built or demonstrated
as meaningful until Phase 2 has run for a real, sufficient data-collection period.*

## What "on-time" means (FR-4.1)

The SRS §7 `Job` entity has no promised/scheduled time, so Phase 3 defines
arrival and lateness from data the system already produces:

- On **assignment**, the job is linked to the customer **site that contains its
  destination**, and an **expected arrival** is recorded = assignment time + the
  road-network ETA computed then (Phase 1's `TravelTimeService`). The driver name
  is captured too (drivers change; reports need the historical one).
- **Arrival** = the debounced geofence **ENTER** at that site (Phase 2). A Spring
  event (`GeofenceEnteredEvent`) carries it from the geofence layer to
  `JobService.recordArrival`, which marks the job `COMPLETED` — no dependency
  cycle between the layers.
- **On time** = actual arrival ≤ expected arrival + `tessera.reporting.on-time-grace-seconds`.

## Data-sufficiency gate (FR-4.4 / SRS Appendix B)

The Appendix B open item is now answered explicitly in `tessera.reporting.*`:

| Property | Default | Meaning |
|---|---|---|
| `min-collection-days` | 14 | days between the oldest durable record and now |
| `min-completed-jobs` | 50 | completed jobs on record before on-time % is trusted |
| `min-site-exits` | 20 | recorded visits before a *site's* dwell average is trusted |

`GET /api/reports/readiness` returns `ready` + the unmet `reasons`. Until it is
ready every report carries `provisional: true` and the UI shows a prominent "not
yet reliable" banner (the figures are still shown, per FR-4.4's wording — *not
presented as reliable*, not hidden).

## Backend (`com.tessera.fleet.reporting`)

| Piece | Responsibility |
|-------|----------------|
| `ReportingService` | Reads bounded fact lists from `DurableStore`, does the grouping (ISO-week buckets, per-site averages) and the period-over-period trend maths here (NFR-4 — no OLAP infra until real load demands it). |
| `ReportingFacts` | `CompletedJobFact`, `SiteVisitFact`, `DataWindow` — the shapes the store returns. |
| `ReportingProperties` | The FR-4.4 thresholds + grace window + `synthetic-history` flag. |
| `ReportModels` | `OnTimeReport`, `DwellReport`, `Readiness`, `Trend`, `WeekPoint`, `SiteDwell`, `FilterOptions`. |
| `ReportController` | `/api/reports/on-time`, `/dwell`, `/readiness`, `/filters`. `from`/`to` epoch millis; default trailing 30 days. |

`DurableStore` gained `completedJobs(from,to)`, `siteVisits(from,to)`,
`reportingWindow()` — implemented in both `InMemoryDurableStore` and
`PostgresDurableStore` (SQL date-range filters). Flyway `V2__reporting_columns.sql`
adds `route`, `site_id`, `driver_name`, `expected_arrival_at`, `actual_arrival_at`
to `jobs`.

## Frontend

`ReportsView` (the deck's "Performance Dashboard"): a filter row (7/30/90-day
preset + route/driver/site selects), two KPI tiles (on-time %, average dwell) each
with a trend chip whose colour is *good/bad* not *up/down* — up is good for
on-time, bad for dwell, always with an arrow + text, never colour alone — an
"On-time % by week" bar list, and an "Average dwell by site" table with inline
bars and an "insufficient data" tag per FR-4.4. The FR-4.4 not-reliable banner and
the demo synthetic-history banner sit above everything.

Visualisation choices follow the `dataviz` skill: single-series bars (one hue, no
legend — the title names the series), bar *lists* rather than SVG charts (a table
is accessible by construction and matches the dense ops-terminal style), status
colours reserved for the trend direction.

## Demo

The `demo` profile back-fills ~5 weeks of synthetic completed jobs and site visits
(on-time % trending upward week over week) so the dashboard renders, and sets
`tessera.reporting.synthetic-history=true` so the UI says the data is synthetic.

## Tests

`mvn verify` — **66 unit + 23 integration**, green; **5 PostGIS ITs skipped** (no
Docker). `npm test` — **20**.

- unit: `ReportingServiceTest` (on-time counting vs grace, route/driver/site
  filters, weekly buckets, period-over-period trend, readiness reasons →
  ready, default 30-day window), `JobArrivalCompletionTest` (assign records
  site + expected arrival + driver; geofence ENTER completes the job; wrong
  site / no job = no-op; late arrival not scored on time), `InMemoryDurableStore`
  reporting reads.
- integration: `ReportApiIT` (auth; readiness false→true as history seeds;
  on-time & dwell aggregate a seeded history; **a real assigned job completes
  when its vehicle drives into the site and then counts in the report**).
- Docker-gated: `PostgisDurableStoreDockerIT` — the reporting SQL against real
  PostGIS/TimescaleDB.
- frontend: `ReportsView` (KPIs, trend chips, FR-4.4 banner, synthetic banner,
  weekly bars, dwell table, refetch on filter change).

## Manual verification (this build)

Ran the stack (`demo`): readiness `ready`, `collectionDays 35`, `completedJobs 538`,
`syntheticHistory true`; on-time 82.9% over 444/30-day jobs with an upward weekly
trend and `▲ 13.8 pts` vs previous period; dwell ~21 min across 3 sites; switching
the date preset to 90d refetched and showed 6 weekly buckets from 69% → 92%.

## Deferred

- Route is a free-text tag on the job (SRS §7 has no route entity); a first-class
  Route/Round entity can come later if scheduling is added.
- No TimescaleDB retention policy yet — add one now that the collection window is
  defined (Appendix B).
- Trajectory replay and address geocoding — Phase 4.
