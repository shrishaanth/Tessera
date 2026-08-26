# Tessera

Real-Time Geospatial Tracking Engine — Core v4

Single-node, in-memory spatial tracking with Copy-On-Write Quadtree, HAMT, TCP ingestion, live dashboard, geofencing, A* routing, and comparative benchmarking.

## Features

- **Spatial Indexing**: Naive, Grid, and Copy-On-Write Quadtree implementations
- **Persistent Hash Map**: HAMT for O(1) vehicle lookups
- **TCP Ingestion**: Netty 4.x server with binary protocol and sharding
- **Deterministic Simulator**: Reproducible vehicle movement for benchmarking
- **Live Dashboard**: Swing-based real-time visualization with zoom, pan, geofence overlay, and route rendering
- **Geofencing**: Ray-casting point-in-polygon with ENTER/EXIT alerts
- **Routing**: A* shortest path on grid road graphs, visualized on the dashboard
- **Benchmarking**: Comparative throughput/latency harness with CSV and text chart output

## Requirements

- JDK 17+
- Maven 3.8+ (optional — direct `javac` works too)

## Build

```bash
mvn clean compile
```

## Run

### Full Demo (server + simulator + dashboard)

The demo starts the Netty ingestion server on port 9090, runs a vehicle simulator with 200 vehicles, and opens a Swing dashboard.

```bash
java -cp target/classes:lib/netty-*.jar com.geotracker.DemoMain
```

On Windows, use semicolons for the classpath:

```bash
java -cp "target\classes;lib\netty-*.jar" com.geotracker.DemoMain
```

Close the dashboard window to stop the application.

### Benchmark

```bash
java -cp target/classes com.geotracker.benchmark.BenchmarkHarness
```

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
├── routing/         # RoadGraph, AStarRouter
├── simulator/       # VehicleSimulator, SimulatorClient
└── util/            # RingBuffer, Config
```

## Configuration

Key settings in `Config.java`:

| Setting | Default | Description |
|---------|---------|-------------|
| `PORT` | 9090 | Netty server port |
| `SHARDS` | 4 | Number of shards |
| `VEHICLE_COUNT` | 1000 | Benchmark vehicle count |
| `DEMO_VEHICLE_COUNT` | 200 | Demo vehicle count |
| `UPDATE_RATE_HZ` | 5.0 | Simulation update frequency |
| `PUBLISH_MAX_DIRTY` | 50 | Max dirty updates before publish |
| `PUBLISH_INTERVAL_MS` | 20 | Max publish interval |

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
