# Tessera

**Real-Time Geospatial Tracking Engine — Core v4**

Tessera is a real-time geospatial tracking engine. Vehicles stream position updates to a Netty TCP server that uses a binary protocol and sharded ingestion. Positions are held in memory in a Copy-On-Write Quadtree, with a persistent HAMT for fast vehicle lookup. A live Leaflet map of an OpenStreetMap road graph shows the fleet, raises ENTER/EXIT alerts through ray-casting geofences, and computes A* shortest routes. A built-in benchmark harness compares naive, grid and quadtree indexes on throughput and latency.

The main way to use it is the **JavaFX desktop application** (`mvn javafx:run`): a fleet dashboard, a live street map, a vehicle table, routing, geofencing, spatial search, performance monitoring and settings, all driven by the same in-process backend. See [Desktop application](#desktop-application-recommended).

**Stack:** Java 21, JavaFX (WebView), Netty 4, Javalin, Leaflet and OpenStreetMap, Jackson, Maven, JUnit 5.

---

## Table of contents

1. [What problem it solves](#what-problem-it-solves)
2. [How it works](#how-it-works)
3. [Components in detail](#components-in-detail)
4. [Design decisions and trade-offs](#design-decisions-and-trade-offs)
5. [Limitations](#limitations)
6. [Features](#features)
7. [Requirements, build and run](#requirements)
8. [Web map API](#web-map-api)
9. [Project structure](#project-structure)
10. [Configuration](#configuration)
11. [OSM data preparation](#osm-data-preparation)
12. [Benchmark results](#benchmark-results)

---

## What problem it solves

A fleet of vehicles reports its position several times a second. Two kinds of work then compete for the same data:

- **Writes:** thousands of position updates per second, each of which moves a vehicle from one place to another.
- **Reads:** "which vehicles are inside this box?", "which one is nearest to this point?", "has anyone entered this zone?", "what is the shortest route from here to there?"

If readers and writers share one mutable structure, they must take locks, and a slow query stalls ingestion (or ingestion stalls the map). Tessera is built around one idea: **readers never block writers, and writers never block readers.** Each shard has a single writer thread that mutates a private working copy of the index. At intervals it publishes an immutable snapshot, and every reader works against the latest published snapshot.

## How it works

```
 vehicle / simulator
        │  32-byte binary frames over TCP (port 9090)
        ▼
 ┌─────────────────────┐
 │ Netty ingestion      │  decode, validate (drop NaN / out-of-area)
 └─────────┬───────────┘
           │  shard = vehicleId mod SHARDS
           ▼
 ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐
 │ RingBuf 0│ │ RingBuf 1│ │ RingBuf 2│ │ RingBuf 3│   one bounded buffer per shard
 └────┬─────┘ └────┬─────┘ └────┬─────┘ └────┬─────┘
      ▼            ▼            ▼            ▼
 IndexerThread  IndexerThread  IndexerThread  IndexerThread   single writer per shard
  CowQuadtree    CowQuadtree    CowQuadtree    CowQuadtree    (where vehicles are)
  HamtIndex      HamtIndex      HamtIndex      HamtIndex      (id → last position)
      │            │            │            │
      └──── publish immutable SpatialSnapshot (volatile reference swap) ────┐
                                                                             ▼
                                   readers: range query · nearest · geofences · routing
                                                                             │
                                                  Javalin: REST + WebSocket → Leaflet map
```

The path of one update, step by step:

1. A client sends a **32-byte frame**: `vehicleId` (8 bytes), `x` (8), `y` (8), `timestamp` (8).
2. `PositionUpdateDecoder` reads the frame and discards it if a coordinate is `NaN`/infinite or falls outside both the synthetic map and the configured OSM area.
3. `ShardRouter` places the update in the ring buffer of shard `vehicleId mod SHARDS`. All updates for one vehicle therefore go to the same shard and stay in order.
4. That shard's `IndexerThread` pops the update, looks up the vehicle's previous position in the HAMT, moves it in the quadtree (remove old point, insert new one), and records the new position in the HAMT.
5. After `PUBLISH_MAX_DIRTY` updates or `PUBLISH_INTERVAL_MS` milliseconds, whichever comes first, the thread **publishes**: it freezes the current state as a `SpatialSnapshot` and swaps it in through a `volatile` reference.
6. Readers (the web server, the geofence engine, the benchmark) always read the most recently published snapshot, without taking a lock.

## Components in detail

### Ingestion (`ingestion/`)
- **`NettyIngestionServer`** listens on TCP port 9090 and runs the decoder pipeline.
- **`PositionUpdateDecoder`** implements the fixed 32-byte binary protocol and the validation described above.
- **`ShardRouter`** owns one ring buffer per shard and routes by `vehicleId mod SHARDS`.

### Ring buffer (`util/RingBuffer`)
A bounded circular buffer between the network thread and each indexer. When it is full, the **oldest** entry is overwritten instead of blocking. That is a deliberate choice for live tracking: a stale position is worth less than a fresh one, and a burst must never stall the socket. The cost is that sustained overload silently drops old updates.

### Copy-On-Write Quadtree (`index/CowQuadtree`, `QuadTreeNode`)
- A region quadtree that splits a leaf into four children when it holds more than `QUADTREE_LEAF_CAPACITY` (32) points, up to a maximum depth of 20.
- Operations: `insert`, `remove`, `update`, `rangeQuery(bbox)` and `nearest(x, y)`.
- It keeps a `workingRoot` (touched only by the writer) and a `publishedRoot` (a `volatile` reference read by everyone else). Nodes are immutable: `insert` and `remove` copy only the path from the root to the changed leaf. `publish()` therefore just hands the current working root to readers, and a reader in the middle of a query keeps seeing a consistent tree while the writer carries on. (Earlier versions deep-copied the whole tree on every publish; with 10,000 vehicles that tripled ingest latency and dominated GC.)

### HAMT (`index/HamtIndex`)
A hash array mapped trie with branching factor 32 (5 bits per level), keyed by vehicle id. It answers "where is vehicle X right now?" without scanning the quadtree. The indexer uses it to find the old position it must remove from the quadtree on every update, and the web layer uses it for vehicle detail lookups. It is published together with the quadtree as part of the same `SpatialSnapshot`, so both views of the world are consistent with each other.

### Indexer thread (`index/IndexerThread`)
One thread per shard; the only writer to that shard's quadtree and HAMT. It drains the ring buffer, applies updates, and decides when to publish (by count or by time).

### Index implementations for comparison (`index/NaiveIndex`, `GridIndex`)
- **`NaiveIndex`**: a flat collection scanned linearly. The baseline.
- **`GridIndex`**: a uniform grid of cells. Fast and simple, but cell size is a fixed guess and dense areas degrade it.
- All three implement the common `SpatialIndex` interface so the benchmark can swap them freely.

### Geofencing (`geofence/`)
- **`RayCaster`** implements ray-casting point-in-polygon.
- **`GeofenceEngine`** holds the static zones and any user-defined zones. For each zone it first uses the zone's bounding box to ask the quadtree for candidate vehicles, then runs the exact polygon test only on those. It remembers who was inside last time and emits **ENTER** and **EXIT** events when membership changes, with a 3-second per-vehicle-per-zone cooldown so a vehicle riding the boundary does not flood the feed. Zones can be created, updated and deleted at runtime, and can be limited to chosen vehicles.

### Routing (`routing/`)
- **`RoadGraph`** loads the OSM-derived road network (nodes and weighted edges) from a JSON resource.
- **`AStarRouter`** computes shortest paths with A* using straight-line distance as the heuristic (haversine on the OSM graph, Euclidean on the synthetic grid). `GeoUtils` holds the distance helpers.
- A vehicle's route is computed from its current position to a requested destination and returned as a list of nodes plus total cost.

### Simulator (`simulator/`)
- **`VehicleSimulator`** is a deterministic, seeded generator, so benchmark runs are reproducible.
- **`TrajectorySimulator`** replays pre-recorded trajectories (`vehicle_trajectories.csv`) so demo vehicles follow real roads instead of jumping around.
- **`SimulatorClient`** sends frames over TCP exactly as an external client would, which means the demo exercises the real ingestion path.

### Web layer (`web/WebServer`, `resources/public/`)
A Javalin server exposes the REST endpoints and two WebSockets, and serves the Leaflet page. The browser draws vehicles from the position stream, shows geofences, and lets you click a vehicle and a destination to request a route.

### Benchmark harness (`benchmark/`)
`BenchmarkHarness` runs the same workload against each index, using `LatencyRecorder` to capture p50/p95/p99/mean latency, and writes `benchmark-results.csv` and `benchmark-chart.txt`.

## Design decisions and trade-offs

| Decision | Why | What it costs |
|---|---|---|
| Single writer per shard, immutable snapshots for readers | No locks on the read path; consistent queries | Readers see data that is up to one publish interval old (20 ms by default) |
| Publish by count **or** time | Bounds both snapshot cost and staleness | Two knobs to tune |
| Shard by `vehicleId mod N` | One vehicle's updates stay ordered, shards scale across cores | A query must visit every shard |
| Drop-oldest ring buffer | Ingestion never blocks; fresh data wins | Overload loses old updates silently |
| Quadtree rather than grid | Adapts to uneven density | More complex; publishing copies the tree |
| HAMT beside the quadtree | O(1)-style id lookup, and the old position is needed to move a vehicle | Extra memory and a second structure to keep consistent |
| Road graph prepared offline | No network dependency at runtime; reproducible | Area is fixed until the data script is re-run |

## Limitations

Stated plainly so the numbers below are not over-read:

- **Single node, in-memory only.** There is no persistence, replication or recovery; a restart loses all state.
- **Simulated fleet.** Positions come from the built-in simulator, not real GPS devices. Benchmarks measure the index data structures, not network or end-to-end throughput.
- **Area.** The desktop simulation covers central Seattle (about 9 × 10.6 km, Ballard/Interbay to Montlake/University District, Pioneer Square to Green Lake), not the whole city. The web demo still uses the original few-block graph around Gas Works Park.
- **Benchmark scope.** It measures update throughput and range-query latency for 1,000 vehicles and 10,000 operations, on one machine. Results vary by machine and JVM warm-up.
- **Snapshot cost.** Publishing is O(1) (structure sharing), but every update still allocates a new root-to-leaf path, so very large fleets put steady pressure on the garbage collector.
- **Map tiles.** Both the desktop app and the web demo draw OpenStreetMap tiles, which need internet access. The desktop app falls back to the bundled road network when tiles fail and says so on the map.
- **Generated trips.** Desktop-simulation vehicles drive shortest-path trips between random road points 1.5–7 km apart, at 85–100% of the posted limit, on motor-vehicle roads only, obeying one-way streets. There is no congestion, signals, turn delay or acceleration: this is deterministic test traffic, not recorded or realistic traffic.

## Features

- **Spatial Indexing**: Naive, Grid, and Copy-On-Write Quadtree implementations
- **Persistent Hash Map**: HAMT for O(1) vehicle lookups
- **TCP Ingestion**: Netty 4.x server with binary protocol and sharding
- **Deterministic Simulator**: Reproducible vehicle movement for benchmarking
- **Desktop application**: JavaFX app with an embedded Leaflet street map (OpenStreetMap tiles), a live fleet of 20 to 10,000 vehicles driving trips across a central-Seattle road network (58,423 nodes, about 1,200 km of road), vehicle table, A* routing with a Dijkstra cross-check, geofence drawing (plus a labelled demo that routes vehicles into a zone), verified spatial search and live performance charts
- **Live Web Map**: Javalin + Leaflet real-time map with WebSocket positions, route requests, and live geofence alerts
- **Geofencing**: Ray-casting point-in-polygon with ENTER/EXIT alerts
- **Routing**: A* shortest path on grid and OSM-derived road graphs
- **Benchmarking**: Comparative throughput/latency harness with CSV and text chart output

## Requirements

- JDK 21+ (tested with JDK 21 and JDK 27; Maven picks a matching JavaFX version automatically)
- Maven 3.8+
- Python 3.8+ (only for the one-off OSM data-prep script)

## Build

```bash
mvn clean compile
```

## Run

### Desktop application (recommended)

```bash
mvn javafx:run
```

or `./run.sh`, which checks the toolchain and compiles first. The app opens on the Live Map: press **Start** to run the simulation, choose a fleet size (20 / 100 / 1,000 / 10,000) and a playback speed in the toolbar, and click a vehicle for details. `Cmd+1` … `Cmd+8` switch pages. Map tiles come from tile.openstreetmap.org (internet needed); the rest works offline.

On JDK 24 and later, `javafx.web` needs the `jdk.jsobject` module, which is no longer part of the JDK. The `jfx-modern` Maven profile (activated automatically on JDK 24+) switches to JavaFX 25.0.2, which ships it. The JavaFX plugin runs the app on the class path through `com.geotracker.TesseraLauncher`. JavaFX logs one "Unsupported JavaFX configuration" warning about this at startup; it is expected and harmless.

### Live Web Map Demo

The default demo starts the Netty ingestion server on port 9090, runs a vehicle simulator with 200 vehicles on a real OSM road graph, and serves a live Leaflet map on port 8081.

```bash
java -cp "target\classes;lib\*" com.geotracker.WebDemoMain
```

On Linux/macOS, use colons for the classpath:

```bash
java -cp "target/classes:lib/*" com.geotracker.WebDemoMain
```

Then open `http://localhost:8081`.

To use the legacy synthetic grid instead of the OSM graph:

```bash
java -cp "target\classes;lib\*" com.geotracker.WebDemoMain --grid
```

### Legacy Swing Dashboard

The original Swing dashboard is still available via `DemoMain`:

```bash
java -cp "target\classes;lib\*" com.geotracker.DemoMain
```

### Benchmark

```bash
java -cp target/classes com.geotracker.benchmark.BenchmarkHarness
```

## Web Map API

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/` | GET | Serves the Leaflet map page |
| `/api/geofences` | GET | Returns zone polygons and bounding boxes as JSON |
| `/api/route?vehicleId=X&destX=Y&destY=Z` | GET | Returns A* route nodes and total cost |
| `/ws/positions` | WS | Live vehicle position broadcast (200ms interval) |
| `/ws/events` | WS | Live geofence ENTER/EXIT events |

## Tests

```bash
mvn clean test
```

Unit tests cover the quadtree, ring buffer, geofencing, routing, simulator and web layer, plus two integration tests (`IntegrationJUnitTest`, `NetworkIntegrationJUnitTest`) that drive the real ingestion path.

## Project Structure

```
src/main/java/com/geotracker/
├── benchmark/       # BenchmarkHarness, LatencyRecorder
├── dashboard/       # SwingDashboard, ConsoleDashboard
├── geofence/        # GeofenceEngine, RayCaster
├── index/           # CowQuadtree, GridIndex, NaiveIndex, HamtIndex, IndexerThread
├── ingestion/       # NettyIngestionServer, PositionUpdateDecoder, ShardRouter
├── model/           # PositionUpdate, Position, BoundingBox, RouteResult, ZoneEvent
├── routing/         # RoadGraph, AStarRouter, GeoUtils
├── simulator/       # VehicleSimulator, SimulatorClient, VehicleSpawnHelper
├── util/            # RingBuffer, Config
└── web/             # Javalin WebServer (REST + WebSocket)
src/main/resources/
├── public/          # Leaflet frontend (index.html, app.js)
└── roadgraph/       # OSM-derived road graphs: seattle-central-roadgraph.json.gz (desktop app), osm-roadgraph.json (web demo)
scripts/
├── build_city_roadgraph.py  # Builds the central-Seattle drivable road graph from Overpass
└── fetch_osm.py             # Original one-off script for the small Gas Works graph
```

## Configuration

Key settings in `Config.java`:

| Setting | Default | Description |
|---------|---------|-------------|
| `PORT` | 9090 | Netty server port |
| `WEB_PORT` | 8081 | Javalin web map port |
| `SHARDS` | 4 | Number of shards |
| `RING_BUFFER_SIZE` | 65536 | Entries per shard buffer |
| `QUADTREE_LEAF_CAPACITY` | 32 | Points a leaf holds before splitting |
| `VEHICLE_COUNT` | 1000 | Benchmark vehicle count |
| `DEMO_VEHICLE_COUNT` | 200 | Demo vehicle count |
| `UPDATE_RATE_HZ` | 5.0 | Simulation update frequency |
| `PUBLISH_MAX_DIRTY` | 50 | Max dirty updates before publish |
| `PUBLISH_INTERVAL_MS` | 20 | Max publish interval |
| `AREA_MIN_LAT` | 47.646 | OSM area south boundary |
| `AREA_MAX_LAT` | 47.650 | OSM area north boundary |
| `AREA_MIN_LNG` | -122.334 | OSM area west boundary |
| `AREA_MAX_LNG` | -122.330 | OSM area east boundary |
| `AREA_NAME` | Gas Works Park, Seattle, WA | Human-readable area name |
| `ROADGRAPH_RESOURCE` | `/roadgraph/osm-roadgraph.json` | Classpath resource for OSM graph |

## OSM Data Preparation

Road graphs are fetched once from the Overpass API and stored as resources; nothing is fetched at runtime.

**Desktop simulation:** `src/main/resources/roadgraph/seattle-central-roadgraph.json.gz`, built by

```bash
python3 scripts/build_city_roadgraph.py                        # fetches from Overpass
python3 scripts/build_city_roadgraph.py --input overpass.json  # or from a saved response
```

It keeps motor-vehicle roads only (motorway to residential; no footways, paths, cycleways, steps, service roads or private/no-access roads), applies one-way rules, drops reversible lanes, and keeps the largest strongly connected component so every destination is reachable. Area, filter, OSM timestamp, licence and statistics are stored in the file. The bundled extract is from OSM data of 2026-10-16.

**Web demo:** `src/main/resources/roadgraph/osm-roadgraph.json`, from `scripts/fetch_osm.py`. This is a few blocks around Gas Works Park and includes footpaths.

To refresh the graph for a different area, edit the bounding box in `scripts/fetch_osm.py` and run:

```bash
python scripts/fetch_osm.py
```

Map data © OpenStreetMap contributors.

## Benchmark Results

Two sample local runs (machine-dependent; the second is committed as `benchmark-results.csv`):

| Index | Throughput (ops/sec), run 1 | Throughput (ops/sec), run 2 | p50 update/query latency |
|-------|-----------------------------|-----------------------------|--------------------------|
| NaiveIndex | ~59K | ~92K | < 0.01 ms |
| GridIndex | ~216K | ~338K | < 0.01 ms |
| CowQuadtree | ~372K | ~400K | < 0.01 ms |

- Vehicle count: 1000
- Operations: 10000
- The benchmark measures update throughput and range-query latency.
- The ordering is stable across runs (quadtree > grid > naive); the absolute numbers move with the machine and JVM state, so quote the ordering and the ratio, not a single figure.
- HAMT is used for vehicle lookups, not as a spatial-index competitor in this benchmark.
- Run the harness yourself for current measurements.

## License

Student project
