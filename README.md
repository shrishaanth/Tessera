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
- **Routing**: A* shortest path on grid road graphs
- **Benchmarking**: Comparative throughput/latency harness with CSV and text chart output

## Requirements

- JDK 17+
- Maven 3.8+ (optional — direct `javac` works too)

## Build

```bash
mvn clean compile
```

## Run

### Server
```bash
java -cp target/classes:lib/netty-*.jar com.geotracker.ServerMain
```

### Simulator
```bash
java -cp target/classes:lib/netty-*.jar com.geotracker.SimulatorMain
```

### Full Demo (server + simulator + dashboard)
```bash
java -cp target/classes:lib/netty-*.jar com.geotracker.DemoMain
```

### Benchmark
```bash
java -cp target/classes com.geotracker.benchmark.BenchmarkHarness
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

## Benchmark Results

| Index | Throughput (ops/sec) |
|-------|---------------------|
| NaiveIndex | ~80K |
| GridIndex | ~174K |
| CowQuadtree | ~318K |

## License

Student project
