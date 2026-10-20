# Tessera — Final Verification Report

**Date:** 2026-10-16
**Branch:** `feature/javafx-complete` (all changes are uncommitted local files)
**Machine:** Apple M3, 8 cores, macOS (Darwin 25.6), built-in Retina display
**Toolchain:** Maven 3.10.0; default JDK = Homebrew OpenJDK 27 (JavaFX 25.0.2); also verified on OpenJDK 21.0.12.1 (JavaFX 21.0.2)

This report replaces the previous one. That report listed 143 passing tests, but the source in the working tree no longer compiled on the machine's default JDK, and the map the user saw was broken. Everything below was re-measured on the current code.

**Second iteration (same day):** vehicles clustered around Gas Works Park because the simulation's whole road network was a 0.27 km² patch around the park, and vehicles random-walked on it. The simulation now drives trips across a 9 × 10.6 km central-Seattle road network. Cause, changes and measurements are in [§8](#8-city-scale-simulation-second-iteration); §4.1 and §6 are updated accordingly.

---

## 1. Launch

```bash
cd ~/Desktop/Tessera
mvn javafx:run          # or ./run.sh
```

The app opens on the **Live Map**. Press **Start**, pick a fleet size (20 / 100 / 1,000 / 10,000) and a playback speed in the toolbar, and click a vehicle for details. `Cmd+1` … `Cmd+8` switch pages. Map tiles need internet access; everything else works offline.

Optional environment variables:

| Variable | Purpose |
|---|---|
| `TESSERA_TILE_URL` | Use another XYZ tile server, e.g. `https://tiles.example.org/{z}/{x}/{y}.png` |
| `TESSERA_TOUR_DIR` | Developer tool: drive the UI through 30 states and write a PNG of each (see §5.3), then exit |
| `TESSERA_TOUR_STEPS` | Restrict the tour to comma-separated step prefixes, e.g. `01,02,21` |

---

## 2. Root causes found

### 2.1 The broken map (blank rectangles, partial map, dark unreadable streets)

I reproduced this with a standalone WebView probe that loads the map page and snapshots what JavaFX actually renders (screenshots in `docs/screenshots/root-cause-*.jpg`).

| # | Cause | Evidence | Fix |
|---|---|---|---|
| 1 | **Leaflet's 3D CSS transforms (`translate3d`) are composited incorrectly by JavaFX's WebKit.** The DOM reported all 24 tiles as loaded, but only a few scattered tiles were painted; the rest of the map stayed blank. | `root-cause-before-dark-filter-3d.jpg` (dark build) and `root-cause-light-tiles-still-3d.jpg` (light tiles, same defect) vs `root-cause-after-L_DISABLE_3D.jpg` | Set Leaflet's documented `L_DISABLE_3D = true` before Leaflet loads, so it positions tiles with 2D offsets. |
| 2 | **The window ran a stale build.** The sources had been switched to light tiles, but `target/classes` still held the dark version, with `filter: invert(1) hue-rotate(180deg) brightness(0.7) contrast(1.3)` on the tile pane. | `diff` of `src/main/resources/map/map.html` against `target/classes/map/map.html` before rebuilding | The filter is gone; the map uses plain OpenStreetMap Standard tiles. |
| 3 | **Leaflet was loaded from the unpkg CDN at runtime.** In the probe, the WebView's request for the CDN script stalled for about 11 minutes, leaving `L` undefined and the page blank. Java's own HTTP client fetched the same URL in 0.4 s. | Probe log (`map=none any3d=noL`) | Leaflet 1.9.4 (BSD-2-Clause) is bundled under `src/main/resources/map/vendor/leaflet/`; there are no runtime CDN dependencies. |
| 4 | **Every zoom blanked the map until new tiles arrived.** Without 3D support Leaflet has no animated zoom: every zoom goes through `_resetView`, whose `viewprereset` event makes tile layers discard all tiles, and tile levels are never scaled. | Probe with new tile requests forced to hang: blank with stock Leaflet, fully covered with the fix | `GridLayer._setZoomTransform` now scales levels with a 2D transform (which this WebKit renders correctly), and the tile layer ignores `viewprereset`. The previous zoom level stays visible, scaled, until new tiles replace it (`zoom-transition.jpg`). |
| 5 | Over-zoomed overlays were sized for the old container after the shared map moved between pages. | Code inspection | The map re-measures itself (`invalidateSize`) after re-parenting and resizing (debounced), and before every fit. |

### 2.2 The project did not build or start on the default JDK

The default `java` on this machine is now JDK 27. `javafx.web` needs the `jdk.jsobject` module, which was removed from the JDK in 24, so JavaFX 21 fails with `package netscape.javascript does not exist` at compile time and `Module jdk.jsobject not found` at launch.

- New Maven profile `jfx-modern`, activated automatically on JDK 24 and later, selects JavaFX 25.0.2, which ships `org.openjfx:jdk-jsobject`. JDK 21–23 keep JavaFX 21.0.2.
- `javafx-maven-plugin` 0.0.8 puts only modules named `javafx.*` on the module path, so it now runs on the class path (`runtimePathOption=CLASSPATH`) through the new `com.geotracker.TesseraLauncher`. As a side effect, JavaFX logs one `Unsupported JavaFX configuration: classes were loaded from 'unnamed module'` warning at startup. It is expected and harmless.

### 2.3 Backend defects found while verifying the UI

| Defect | Symptom | Fix | Regression test |
|---|---|---|---|
| Quadtree bounds smaller than the road graph | The quadtree silently ignores points outside its root box. The OSM graph reaches lng −122.3364 and lat 47.6455/47.6509, beyond the configured box, so those vehicles were missing from range/radius queries and geofence checks. | Index bounds are derived from the road graph plus CSV area, with a 10% margin. | `indexBoundsCoverTheWholeRoadGraph`, `everyTrackedVehicleIsFoundByAFullExtentRangeQuery` |
| Generated vehicles "teleporting" | Speeds up to 131 km/h appeared in the vehicle table. Distance left over at a node was carried onto the next edge as a *fraction* of that edge's length. | Leftover distance is carried in metres. | `GraphVehicleMotionTest` (2 tests), `generatedSpeedsArePhysicallyPlausible` |
| Speed and heading depended on who called `getAllVehicles()` | Each UI pane overwrote the shared "previous position", so speed flickered to 0. Speed also scaled with the playback multiplier, and heading used `atan2(dLng, dLat)` (about 10° wrong at 47.6° N). | Telemetry is derived on the simulation thread in simulated time; heading uses the great-circle bearing. | `speedDoesNotScaleWithPlaybackMultiplier`, `headingMatchesDirectionOfTravel`, `GeoUtilsGeodesyTest` |
| Radius query missed vehicles at the edge | The bounding-box pre-filter used 111,320 m/° while the distance check uses the 6,371 km sphere (~111,195 m/°). | Exact spherical bounding box. | `radiusQueryIncludesVehiclesRightAtTheEdge`, `radiusBoundingBoxContainsTheWholeCircle` |
| Ghost vehicles after changing fleet size | Going from 1,000 to 100 vehicles left 900 stale entries in the index, map and counts. | The index is cleared (on the indexer threads) when the fleet changes, on a new run, and on reset. | `shrinkingTheFleetRemovesVehiclesFromTheIndex`, `clearingAnIndexerPublishesAnEmptySnapshot` |
| Spurious geofence EXIT events after restart | The engine remembered which vehicles were inside zones from the previous run. | `GeofenceEngine.resetState()` runs on new run, reset and fleet change. | `resetStatePreventsSpuriousExitEventsAfterARestart` |
| Built-in zones invisible on the map | Only user zones were sent to the map. | `getBuiltInZones()`; all zones are drawn, with occupancy. | `builtInZonesAreExposedForDisplay` |
| `GridIndex.rangeQuery` returned a superset | Every vehicle in boundary cells was returned, and the differential test tolerated it. | Exact containment check; the differential test now requires equality. | `gridRangeQueryReturnsOnlyPointsInsideTheBox`, `SpatialDifferentialTest` |
| `GridIndex.nearest` could return a non-nearest point | The search stopped at the first non-empty ring. | It keeps searching while a closer point is still possible. | `gridNearestMatchesBruteForce`, `gridNearestCornerCaseAcrossRings` |
| Quadtree leaf could throw `Leaf capacity exceeded` | Inserting more than 32 vehicles at identical coordinates (e.g. parked on one node) threw, which would kill that shard's indexer thread. | The leaf grows; the indexer also survives a bad update. | `quadtreeLeafAcceptsManyVehiclesAtIdenticalCoordinates` |
| Full deep copy on every publish | Up to about 250 copies of a whole shard per second at 10,000 vehicles: 18.8 ms mean queue latency, heap churning between 68 and 553 MB. | Nodes are already immutable (path copying), so publish shares the root in O(1). | `publishedQuadtreeSnapshotIsUnaffectedByLaterWrites`, `publishedHamtSnapshotIsUnaffectedByLaterWrites`, plus the existing snapshot/concurrency tests |
| Trailing updates not published | Fewer than 50 updates followed by an idle queue stayed invisible until the next update. | The indexer publishes on idle once the interval has passed. | `indexerPublishesTrailingUpdatesWhenTheQueueGoesIdle` |
| Generated vs. indexed rate jitter (±20%) | 1 s windows alias with the 200 ms simulation tick. | Both rates are averaged over the same 3 s window. | `sampledRatesAgreeWithTheConfiguredFleet` |

### 2.4 Trajectory data that does not match the street map

The 20 CSV trajectories (`vehicle_trajectories.csv`) were generated on a regular 10 × 10 lattice. Measured against the OSM road graph, their points are a median 33 m from the nearest road and 174 m at the 90th percentile; on the real basemap they cross blocks, Gas Works Park and Lake Union. The default trajectory source is therefore now **Road graph**: every vehicle follows OSM edges. The CSV replay is still available under *Settings → Simulation*, where this limitation is stated. `DatasetValidationTest` still validates the CSV file.

---

## 3. What changed

**Map (`src/main/resources/map/`)**: `map.html`, `tessera-map.js`, `tessera-map.css`, and bundled Leaflet.
- OpenStreetMap Standard tiles, unfiltered and light. The `User-Agent` identifies the app (`… Tessera/1.0`), attribution is always visible, and the attribution link opens in the system browser.
- Failed tiles are retried up to three times (1 s / 3 s / 8 s backoff). The retry uses a URL fragment, so it re-requests the same HTTP URL and adds no cache-busting.
- Tile failures are diagnosed: Java fetches the first failing tiles itself and reports the real cause (HTTP status, DNS, timeout). After repeated failures with no successful load, the map automatically switches to the bundled road network with an explicit banner and a Retry button. The road network is also selectable as an offline basemap.
- All vehicles are drawn in one canvas layer: every one of 10,000 vehicles is rendered, in 7–18 ms per frame as measured on this machine. Up to 2,500 vehicles at zoom ≥ 16 are drawn as white-ringed markers with heading arrows; larger fleets as dots. Selection, search-result rings and hit-testing for clicks are included.
- Initial view fits the road network (the simulation area). *Fit all vehicles* and *Fit road network* controls; scale bar; zoom control.
- Overlays: geofences with labels and occupancy, the route with A/B pins and snap connectors, search areas, draft polygons, and the road graph (off by default; drawn as merged polylines with Leaflet's SVG renderer, see §8.5).

**Desktop UI (`src/main/java/com/geotracker/fx/`)**: light theme (`styles/tessera.css`) with a sidebar for Dashboard, Live Map, Vehicles, Routing, Geofencing, Spatial Search, Performance and Settings. One `MapPane` (WebView) is shared and moved into whichever page needs it. A background `LiveData` sampler builds fleet snapshots and map payloads off the FX thread and never queues more than one frame. `MapPayloads` holds all coordinate-order conversions (backend x = lng, Leaflet = [lat, lng]) and is unit-tested.

**Backend**: see §2.3. New: `DijkstraRouter` (reference cost for the routing page's optimality check), `ApplicationService.planRoute`, verified radius/box queries (index result compared with a brute-force scan of the same snapshot), `sampleMetrics`, trajectory-source setting, stale threshold, and index clearing.

---

## 4. Automated verification

### 4.1 Test suite

`mvn clean test` on JDK 27 (final run, after the second iteration): **206 tests, 0 failures, 0 errors, 0 skipped**, in 41 test classes. That is the 187 from the first iteration plus 19 for the city-scale simulation (listed in §8.6). The first iteration's 187 were the previous 143 tests (two of them updated for the new default trajectory source, and the grid assertion in `SpatialDifferentialTest` tightened to equality) plus 44 new ones:

- `ApplicationServiceRegressionTest` (17): index bounds, speeds, heading, fleet shrink, reset, pause/resume/stop semantics, radius and box queries against brute force while vehicles move, A* = Dijkstra on 60 random OSM pairs, windowed rates, trajectory source.
- `IndexCorrectnessRegressionTest` (8), `GraphVehicleMotionTest` (2), `GeoUtilsGeodesyTest` (5), `MapPayloadsTest` (7), `GeofenceEngineStateTest` (3), and 2 added to `ApplicationServiceTest`.

Existing coverage still passing includes network ingestion (`NetworkIntegrationJUnitTest`), concurrency (`ConcurrencyStressTest`, `ConcurrentReaderJUnitTest`, `SnapshotConsistencyJUnitTest`), A* vs. Dijkstra (`DijkstraReferenceTest`), geofence differential tests and dataset validation.

### 4.2 Pipeline measurements (headless, one JVM per fleet size, steady state over 10 s)

| Fleet | Generated | Indexed | Queue → index latency (mean / max) |
|---|---|---|---|
| 20 | 100 /s | 101 /s | 1.1 / 3 ms |
| 100 | 500 /s | 500 /s | 1.4 / 2 ms |
| 1,000 | 5,003 /s | 5,003 /s | 1.8 / 4 ms |
| 10,000 | 49,729 /s | 49,784 /s | 7.4 / 19 ms |

Every vehicle updates at 5 Hz, so the generated rate is 5 × fleet. At 10,000 vehicles, before the O(1) publish fix, the same harness measured 18.8 ms mean and 79 ms max latency. The trade-off is that a full-extent range query now takes about 0.4–0.5 ms instead of about 0.1 ms (shared nodes are less cache-friendly than a fresh copy). Latency has millisecond resolution because update timestamps are epoch milliseconds. These numbers come from one machine and are not a benchmark claim.

---

## 5. Visual and interactive verification

### 5.1 Method and its limits

macOS screen capture was not available to me (`screencapture` was denied in this session), so **I did not see the real OS window on the display.** Instead, the app has a developer tour (`com.geotracker.fx.UiTour`, enabled by `TESSERA_TOUR_DIR`). It drives the real running application and saves `Scene.snapshot()` of the actual scene graph, including the WebView's rendered map, after each step. I inspected every snapshot. The snapshots are at 1× scale, so they don't show OS window chrome, the menu bar, the mouse cursor or true Retina (2×) rendering.

### 5.2 What was inspected (final run: 25 of 25 states written)

- Live Map: stopped/empty, running with 100, 1,000 and 10,000 vehicles, paused at 10,000 (statuses and buttons consistent), vehicle selected (card and highlight), road-graph overlay, offline basemap, stopped. In every state the map fills the area with no blank regions; tiles: 158 of 158 requested tiles loaded over the run, 0 errors.
- Window sizes: 1024 × 680 (the minimum; the toolbar wraps to two rows and the layers card collapses to a button), 1354 × 848 scene (default) and 1440 × 870 scene (the largest this screen allows; a larger request was clamped by the OS). The map re-measured correctly after each resize.
- Tab switching between all eight pages, with the shared map moving between the Live Map, Routing, Geofencing and Search layouts.
- Dashboard, Vehicles (100 and 10,000 rows), Routing (route plus Dijkstra check), Geofencing, Spatial Search (results verified against brute force), Performance (100 and 10,000 vehicles), Settings.
- Tile failure (`TESSERA_TILE_URL=https://tiles.invalid/…`): 3 retries per tile, Java diagnosis "no connection", automatic switch to the bundled road network with banner and attribution (`offline-fallback.jpg`).

Curated snapshots are in `docs/screenshots/` (3.6 MB of JPEGs; delete them if you do not want them in the repository).

### 5.3 Interaction through the real input path

The tour dispatches JavaFX mouse and scroll events to the WebView, so they travel through WebKit DOM events to Leaflet and back over the JS→Java bridge:

| Interaction | Result |
|---|---|
| Scroll-wheel zoom | Zoom 16 → 17, anchored at the cursor |
| Drag pan (300 × 200 px over ~0.5 s) | Centre moved 0.00322° lng / 0.00145° lat, as expected at zoom 17, with no fling |
| Click a vehicle | Hit-test found vehicle 7; selection and detail card updated |
| Routing: pick origin, then destination on the map | Fields filled, route computed, snaps shown (143 m and 231 m) |
| Geofencing: four clicks, then *Create zone* | 3.0 ha zone created, drawn, occupancy shown, ENTER/EXIT events logged |
| Zoom while new tiles are still loading | Previous zoom level shown scaled; no blank areas |

### 5.4 Other runtime checks

- **JDK 21 and JDK 27:** both build and run (JavaFX 21.0.2 and 25.0.2 respectively) and render the same.
- **Startup:** about 6.5 s from `mvn javafx:run` to the first UI frame (includes Maven start-up); initial tiles average 0.2–0.4 s each from tile.openstreetmap.org.
- **Shutdown:** `Platform.exit` stops the UI sampler, simulation, geofence checks and indexer threads; in the timed run the process exited about 0.5 s later, and every run exited with code 0.
- **FX thread:** the sampler logs any frame that waits more than 1 s for the FX thread or takes more than 250 ms to handle. None were logged in the final runs.

---

## 6. Limitations and behaviour not verified

- **Not seen on the physical display.** Verification used in-app snapshots (§5.1). Real Retina rendering, window chrome, cursor feedback and the native maximise/full-screen buttons were not observed; programmatic resizes up to the screen size were.
- **Gestures.** Trackpad pinch-zoom, momentum scrolling and keyboard shortcuts (`Cmd+1` … `Cmd+8`) were not exercised. Mouse input was synthetic, as described above.
- **Retina sharpness.** OSM raster tiles are 256 px; on a 2× display they are upscaled and slightly soft. Labels stay legible.
- **Tiles need the internet** and are subject to the [OSM tile usage policy](https://operations.osmfoundation.org/policies/tiles/). This light single-user use complies; heavy or distributed use needs your own tile server (`TESSERA_TILE_URL`). The automatic fallback deliberately waits about 5 s after the last successful tile before switching, to avoid flapping on brief errors.
- **Transitional zoom.** While new tiles load after a zoom, the previous level is shown upscaled; thin seams can be visible between scaled tiles for a moment.
- **Generated trips are synthetic** (see §8.7): vehicles drive at a constant share of the posted limit with no congestion, traffic signals, turn delays or acceleration, and pick destinations at random. They are not real traffic.
- **Coverage is central Seattle only**, not the whole city (§8.2). The CSV replay option still uses its synthetic lattice near Gas Works Park.
- **Built-in zone names** ("Gas Works north", "North zone") describe the original demo polygons, which lie north of the park rather than covering it. With vehicles now spread over the city, these two small zones see far fewer events than before.
- **Network ingestion** (Netty TCP server) is verified by `NetworkIntegrationJUnitTest` only; the desktop app does not expose it, since its simulator feeds the shards in process. Its decoder (`PositionUpdateDecoder`) still accepts only the original small Gas Works area or the synthetic 0–1000 grid; this was left unchanged because the city simulation does not use it.
- **Long-running use** was only tested for minutes, not hours.
- **Gaps caused by the Mac sleeping.** Two unattended tour runs showed stalls of 95 s and 466 s. The macOS power log shows the machine entered *Idle Sleep* at 21:53:27 for 467 s, matching the larger gap exactly; the app's own instrumentation showed no blocking on the FX thread. The ~50 s gap seen in the first iteration's first tour was probably the same cause (it was not checked at the time). Later runs used `caffeinate -i` and showed no stalls.
- **Snapshot tool flakiness.** In this sandboxed environment a few snapshot files written by the tour's background encoder were left empty or missing (one directory once). Re-runs wrote them correctly. This affects only the developer tour, not the application.
- **Startup log noise.** `CVDisplayLink… error -6661` lines appeared in every run in this environment, including a minimal WebView program unrelated to Tessera.

---

## 7. Files

Modified: `pom.xml`, `README.md`, `GeofenceEngine`, `CowQuadtree`, `QuadTreeNode`, `HamtIndex`, `GridIndex`, `IndexerThread`, `GeoUtils`, `GraphVehicleGenerator`, `ApplicationService`, two existing tests (`ApplicationServiceTest`, `SpatialDifferentialTest`).

New: `TesseraLauncher`, `routing/DijkstraRouter`, `fx/*` (UI rewritten: `AppShell`, `AppContext`, `Page`, `LiveData`, `MapPane`, `MapPayloads`, `MapWorkspace`, `LiveMapPane`, `SimulationControlPane`, the seven other pages, `UiTour`, `fx/ui/*`), `resources/map/*` (including bundled Leaflet), `resources/styles/tessera.css`, 6 new test classes, `docs/screenshots/`.

Second iteration — modified: `GraphVehicleGenerator` (trip-based movement), `RoadGraph` (optional edge speed, streaming loader, cached geo check), `ApplicationService` (city graph, polylines, network stats, route travel time, geofence demo), `Config`, `MapPayloads`, `tessera-map.js`, `TesseraApp`, `GeofencePane` (demo control), `RoutingPane` and `SettingsPane` (text), `UiTour`, and three assertions in `ApplicationServiceRegressionTest` (§8.6). New: `scripts/build_city_roadgraph.py`, `src/main/resources/roadgraph/seattle-central-roadgraph.json.gz`, `CityRoadNetworkTest`, `CityTripSimulationTest`, `CitySimulationServiceTest`, four screenshots (`city-*.jpg`, `geofence-demo.jpg`). The original `osm-roadgraph.json` is unchanged and still used by the web demo and its tests.

Build outputs under `target/` are tracked in git in this repository and are rewritten by every Maven build; they were already modified before this work started.

---

## 8. City-scale simulation (second iteration)

### 8.1 Why vehicles clustered around Gas Works Park

Measured on the previous road graph (`osm-roadgraph.json`) and generator:

| Cause | Evidence |
|---|---|
| The whole network was tiny | 458 nodes over 445 m × 600 m (0.27 km²): a few blocks around Gas Works Park. Every vehicle, at any fleet size, was confined to it. |
| Most of it was not streets | Matched against current OSM tags: footways 49.5% of edge length, service roads 13.0%, cycleways 6.9%, steps 0.5%; residential/tertiary/secondary/unclassified only 30%. |
| Spawning followed node density | Vehicles started at a uniformly random *node*. Park footpaths have many shape points: 18.1% of all nodes sat in one 100 m × 100 m cell at the park entrance, which holds 6.6% of the road length. |
| Movement was a random walk | A random outgoing edge at every node (61% of nodes are mid-road shape points) and no destinations. Simulating 5 minutes of driving (1.5–4.5 km travelled) left vehicles a **median 98 m** (p90 242 m) from their start. |
| One-way handling | 12 strongly connected components; at dead ends vehicles reversed even along one-way edges. |

### 8.2 New road network and coverage

`src/main/resources/roadgraph/seattle-central-roadgraph.json.gz` (1.6 MB), built by `scripts/build_city_roadgraph.py` from OpenStreetMap via the Overpass API (OSM data timestamp 2026-10-16T15:16:05Z). The response was fetched from the `maps.mail.ru` Overpass mirror because `overpass-api.de` was at capacity at the time, then processed with `--input`. © OpenStreetMap contributors, ODbL 1.0; the attribution and licence are stored in the file and shown in *Settings → Data and attribution*.

- **Area:** bounding box 47.595–47.690 N, 122.405–122.285 W, **about 9.0 km × 10.6 km** (including Lake Union, Portage Bay and parts of Elliott Bay). It includes roads in Ballard (the box ends at 122.405 W, just short of Shilshole), Interbay and eastern Magnolia, Fremont, Phinney Ridge, Wallingford, Green Lake, Roosevelt, Ravenna, the University District, Montlake, Eastlake, Queen Anne, South Lake Union, Belltown, Downtown, Capitol Hill, First Hill, the Central District, Madison Valley, Leschi, Pioneer Square and the north edge of the International District.
- **Not covered:** West Seattle, SODO, Georgetown, Beacon Hill, Rainier Valley, Northgate, Lake City, Crown Hill and anything north of about N 85th St, western Magnolia, Laurelhurst, Sand Point, Madison Park. This is **central Seattle, not the whole city.**
- **Roads kept:** motorway, trunk, primary, secondary, tertiary (and their link roads), unclassified, residential, living_street. Excluded: footways, paths, cycleways, steps, pedestrian streets, tracks, service roads (driveways, alleys, parking aisles), construction/proposed roads, roads with `access`/`vehicle`/`motor_vehicle`/`motorcar` = no or private, and reversible lanes (`oneway=reversible`, e.g. the I-5 express lanes): 61 ways in total besides the class filter.
- **One-way rules:** `oneway=yes/-1`, plus the implied one-way of motorways, motorway links and roundabouts. 22,137 of 104,463 directed edges are one-way.
- **Connectivity:** only the largest strongly connected component is kept (58,423 of 59,435 nodes, 98.3%), so every node can reach every other along legal directions. The simulator also restricts itself to the largest component of whatever graph it is given.
- **Size:** 58,423 nodes, 104,463 directed edges, about 1,198 km of road. Edge speeds come from `maxspeed` tags or Seattle class defaults (32 km/h residential, 40 km/h arterial, up to 97 km/h on I-5). Loads in about 0.17 s.

### 8.3 New movement model (`GraphVehicleGenerator`)

- **Spawn:** a random point on a random road segment, sampled by road length, so vehicles spread evenly over the streets rather than over OSM shape points.
- **Trips:** each vehicle picks a destination 1.5–7 km away in a straight line (sampled by road length) and follows a shortest path there. The route cost is length × a speed factor clamped to 0.75–1.5, so arterials are mildly preferred; A* on a compact array copy of the graph, with an admissible heuristic.
- **Movement:** continuous along road segments in legal directions at 85–100% of the posted limit (a per-vehicle factor); time left over at a node carries into the next segment. On arrival the vehicle waits at the node for at most one 200 ms tick, then continues from there towards a new destination. It never jumps.
- **Performance:** routes needed in the same tick are computed in parallel on the simulation thread, never on the UI thread. Seeded per vehicle, so runs are reproducible.
- **Speed and heading** shown in the UI are still derived from successive positions in simulated time, so they agree with the visible movement.

### 8.4 Measurements (Apple M3; one run each)

| | Old graph (0.27 km²) | City graph |
|---|---|---|
| 1,000 vehicles at spawn: 1 km cells occupied / busiest cell | 2 / 599 | 84 / 25 |
| 1,000 vehicles within 500 m of Gas Works Park, at spawn → after 10 sim-min | 848 → 939 | 4 → 6 |
| Median displacement after 10 simulated minutes | 159 m (new trip model on old graph); 98 m after 5 min with the old random walk | 4,429 m |
| Trips completed in 25 simulated minutes (1,000 / 10,000 vehicles) | — | 2,516 / 25,162 |

Simulation cost on the city graph (headless harness and test output): computing the first routes for 10,000 vehicles takes 1.4–1.8 s on the simulation thread; after that a tick averages 0.3–4 ms (higher with 1-second simulated steps), and the generator holds about 13 MB of heap for 10,000 vehicles. The full pipeline at 10,000 vehicles still indexes about 50,000 updates/s (`VehicleScaleTest`).

### 8.5 Map changes

- The road network is sent to the map once at start-up as 13,343 merged polylines (76,643 points, about 2 MB of JSON; chains between junctions) instead of one segment per edge.
- **Rendering bug found and avoided:** drawing these road layers with Leaflet's *canvas* renderer intermittently triggered an internal JavaFX WebView exception during resizes (`NullPointerException … Texture.assertLocked() because "this.maskTex" is null` in `WCGraphicsPrismContext$ClipLayer`). It was reproduced in 2 of 2 stress runs at zoom 16 and once in a full tour. With the *SVG* renderer (Leaflet clips and simplifies to the viewport), 3 stress runs and all later tours showed none. The road layers therefore use SVG.
- **Fit road network:** with 3D transforms disabled Leaflet allows only whole zoom levels and `fitBounds` always rounds down, which showed the network at zoom 12, using about a third of the map width. The fit now goes one level closer when the network would overflow the map by at most about 5%. The default window opens at zoom 13 with the whole network visible except thin strips at the north and south edges. The home control is labelled *Fit road network*.
- **10,000 vehicles at city zoom:** at zoom 12–14 dots shrink to 1.4–1.9 px and become translucent, so dense areas read as density while streets and labels stay visible (`city-10000-vehicles.jpg`). Smaller fleets are unchanged.

### 8.6 Tests (19 new, 3 assertions updated)

- `CityRoadNetworkTest` (5): extent above 8.5 × 10 km and more than 50,000 nodes; a road within 150 m of six named neighbourhood points (Downtown, Ballard, University District, Capitol Hill, Queen Anne, Fremont); every node in one strongly connected component; edge weights equal haversine length and speeds are positive and plausible; more than 10% one-way edges; source and licence recorded, no footway/path/cycleway in the filter.
- `CityTripSimulationTest` (8): 1,000 spawns occupy at least 60 one-km cells, with at most 50 in any cell and at most 30 within 500 m of Gas Works Park. Every vehicle has a route ending at its destination using only existing directed edges, and the median planned trip is over 1.5 km. Over 10 simulated minutes, no vehicle moves farther per tick than the fastest limit allows, every vehicle's current edge exists in its direction, and positions stay within 0.5 m of that edge. Destinations change only on arrival, at the destination node. Median displacement after 10 minutes is over 1.5 km. A vehicle's natural route through a zone produces ENTER then EXIT. A redirected vehicle reaches its target without jumping. 10,000 vehicles route and tick within budget.
- `CitySimulationServiceTest` (6): the service uses the city network, all nodes routable, more than 1,000 km of road, initial view more than 12 km across. 1,000 running vehicles occupy at least 60 cells, with fewer than 30 near Gas Works Park. Road polylines cover every segment exactly once. Downtown → University District route matches Dijkstra and gives a plausible travel time. The demo refuses when stopped or when no road lies inside the zone (a square in Lake Union). Demo-routed vehicles produce real ENTER and EXIT events, and the demo call itself records nothing (checked while paused).
- Updated because the old random walk's limits no longer apply: `generatedSpeedsArePhysicallyPlausible` and `speedDoesNotScaleWithPlaybackMultiplier` now cap speed at the fastest posted limit (97 km/h) instead of 54.5 km/h, still well below the 100+ km/h jumps they guard against. `routePlansAreOptimalAgainstDijkstra` allows the 1 cm rounding of stored edge weights when comparing cost to haversine distance; the A* = Dijkstra assertion is unchanged.

### 8.7 Visual verification and limits of the model

Checked in the running app with the developer tour (screenshots `city-1000-vehicles.jpg`, `city-10000-vehicles.jpg`, `city-road-network.jpg`, `geofence-demo.jpg`):

- 1,000 vehicles visibly spread across Ballard, Phinney Ridge, Magnolia, Queen Anne, Fremont, Wallingford, Green Lake, the University District, Capitol Hill, Madison Valley, Belltown, Downtown, First Hill and the International District, on streets including SR 99 and I-5.
- The road-graph overlay matches the OSM streets and shows no roads through parks or water.
- The geofence demo (*Geofencing → DEMO → Send 10 vehicles here*) routed vehicles 575, 763, 801, 564, 312, 175, 346, 139, 54 and 686 to "Gas Works north". The events table then showed them entering and leaving, including vehicle 175 crossing "North zone" on its way.
- The full tour (all 30 steps, including routing, geofence drawing, search and the 10,000-vehicle steps) completed with 0 tile errors.

Limits of the model: constant speed per road segment (no acceleration, congestion, traffic lights or turn costs); destinations are random road points, not real origins and destinations; the shortest-path cost is distance-based with a mild arterial preference, not travel time; vehicles wait one tick at each destination. Network edges between OSM nodes are straight, so curves are represented by OSM's own shape points only.
