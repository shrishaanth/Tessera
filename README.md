# Tessera

Real-Time Geospatial Tracking Engine — Core v4

Single-node, in-memory spatial tracking with Copy-On-Write Quadtree, HAMT, TCP ingestion, live web map, geofencing, A* routing, and comparative benchmarking.

## Features

- **Spatial Indexing**: Naive, Grid, and Copy-On-Write Quadtree implementations
- **Persistent Hash Map**: HAMT for O(1) vehicle lookups
- **TCP Ingestion**: Netty 4.x server with binary protocol and sharding
- **Deterministic Simulator**: Reproducible vehicle movement for benchmarking
- **Live Web Map**: Javalin + Leaflet real-time map with WebSocket positions, route requests, and live geofence alerts
- **Geofencing**: Ray-casting point-in-polygon with ENTER/EXIT alerts
- **Routing**: A* shortest path on grid and OSM-derived road graphs
- **Benchmarking**: Comparative throughput/latency harness with CSV and text chart output

## Requirements

- JDK 17+
- Maven 3.8+ (optional — direct `javac` works too)
- Python 3.8+ (only for the one-off OSM data-prep script)

## Build

```bash
mvn clean compile
```

## Run

### Live Web Map Demo (default)

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
└── roadgraph/       # OSM-derived road graph JSON (osm-roadgraph.json)
scripts/
└── fetch_osm.py     # One-off Overpass API data-prep script
```

## Configuration

Key settings in `Config.java`:

| Setting | Default | Description |
|---------|---------|-------------|
| `PORT` | 9090 | Netty server port |
| `WEB_PORT` | 8081 | Javalin web map port |
| `SHARDS` | 4 | Number of shards |
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

The OSM road graph is fetched once using the Overpass API and serialized to `src/main/resources/roadgraph/osm-roadgraph.json`. It is not fetched at runtime.

To refresh the graph for a different area, edit the bounding box in `scripts/fetch_osm.py` and run:

```bash
python scripts/fetch_osm.py
```

## Benchmark Results

Sample results from a local run (machine-dependent):

| Index | Throughput (ops/sec) | Update p50 (ms) | Query p50 (ms) |
|-------|---------------------|-----------------|----------------|
| NaiveIndex | ~59K | 0.00 | 0.01 |
| GridIndex | ~216K | 0.00 | 0.00 |
| CowQuadtree | ~372K | 0.00 | 0.00 |

- Vehicle count: 1000
- Operations: 10000
- Benchmark measures update throughput and range-query latency.
- HAMT is used for O(1) vehicle lookups, not as a spatial-index competitor in this benchmark.
- Results vary by machine and JVM state. Run the benchmark harness for current measurements.

## License

Student project
