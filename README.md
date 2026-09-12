# Tessera Risk

A real-time fleet driver-behaviour risk analytics pipeline. Simulated vehicles drive
a real OpenStreetMap road network; the system detects unsafe driving in motion,
aggregates it into windowed risk metrics with Spark, stores those in HBase, and
predicts which vehicles are trending toward a safety incident in the next few
minutes.

Built as the final package for **Big Data and Modern Databases**. The full
requirements specification is in `../srs/Tessera_Risk_SRS.docx`.

> **All vehicle data is simulated.** Vehicles are driven along a real road network
> under real posted speed limits and enforced physical acceleration and braking
> limits. No physical vehicles, telematics feed, or personal data are involved.
> No public dataset of commercial fleet telemetry exists, which is why the data is
> generated rather than collected.

## Architecture

```
Event Producer ──▶ Kafka ──▶ Spark Structured Streaming ──▶ HBase ──▶ Reporting API ──▶ Dashboard
                                      ▲
                                      │ loads trained model
Offline Generator ──▶ Parquet ──▶ Batch RDD Job ──▶ Spark MLlib ──┘
```

The live path and the training path are decoupled on purpose: the historical
archive used for model training is generated offline in bulk rather than by
archiving the live stream, so a large corpus can be produced in one pass instead
of waiting for wall-clock time to accumulate.

## Stack

| Layer | Technology |
|---|---|
| Language, build | Java 17, Maven (multi-module) |
| Ingest | Apache Kafka 3.7 (KRaft mode) |
| Processing | Apache Spark 3.5 — Structured Streaming, Core/RDD, SQL, MLlib |
| Analytical store | Apache HBase 2.5 (wide-column) |
| Cold storage | Apache Parquet |
| Reporting | Spring Boot (REST + WebSocket) |
| Dashboard | React, TypeScript, Leaflet |
| Orchestration | Docker Compose |

Java 17 rather than 21: Spark 3.5 officially supports Java 8/11/17, and Java 21
support only arrived in Spark 4.0.

## Modules

| Module | Status | Purpose |
|---|---|---|
| `common` | ✅ | Road network, geodesy, shared event model |
| `event-producer` | ✅ | Fleet simulation and Kafka telemetry publishing |
| `streaming-job` | ✅ | Windowed aggregation, HBase writes, alerting |
| `batch-job` | planned | RDD feature engineering over the Parquet archive |
| `ml-training` | planned | MLlib classifier training and evaluation |
| `reporting-api` | planned | HBase reads for the dashboard |
| `frontend` | carried over | Leaflet dashboard, to be re-themed for risk |

## Running it

Start the infrastructure:

```bash
docker compose up -d
```

This brings up Kafka (KRaft mode — no separate ZooKeeper, since HBase already
embeds one) and HBase standalone, and creates the two topics. Allow roughly
4–6 GB of container memory.

Build, then start the producer:

```bash
mvn -q install -DskipTests
java -jar event-producer/target/event-producer-1.0.0.jar
```

Then the streaming job, which reads the topic, aggregates it and writes to HBase:

```bash
docker compose --profile pipeline up streaming-job
```

Configuration is entirely by environment variable — `KAFKA_BOOTSTRAP_SERVERS`,
`TESSERA_VEHICLE_COUNT`, `TESSERA_TICK_MILLIS`, `TESSERA_WINDOW_MINUTES`,
`TESSERA_ALERT_SCORE_THRESHOLD`, and the thresholds in `SimulatorConfig` and
`StreamingConfig`. Nothing is hard-coded.

To see what landed in HBase:

```bash
docker compose --profile pipeline run --rm --entrypoint java streaming-job -cp /opt/tessera/streaming-job-1.0.0.jar com.tessera.risk.streaming.hbase.HBaseDump vehicle_risk 10
```

`HBaseDump` decodes the binary cell values and takes a table name and a row limit;
try `segment_metrics` or `driver_profile` too. Because the row keys carry a
reversed timestamp, an unsorted scan comes back newest-first.

> Connecting to HBase from the host (an IDE, say) needs `127.0.0.1  hbase` in your
> hosts file — HBase hands clients a hostname, not an IP.

### Running it without knocking HBase over

Standalone HBase runs its master and region server as ordinary JVMs alongside an
embedded ZooKeeper, and the region server **aborts itself if its ZooKeeper session
expires**. A long enough GC pause does that, which makes memory pressure look like
a cluster failure: ZooKeeper, REST and Thrift stay up and listening while the
master and region server are simply gone.

Three settings exist because of that failure, and are worth leaving alone:

- `TESSERA_MAX_OFFSETS_PER_TRIGGER` (2000) caps how much backlog one micro-batch
  may take. It is backpressure, not throughput. Left high, the first batch after a
  restart swallows the whole topic — one run turned a 3-hour backlog into 16,000
  window rows in a single batch and paused HBase long enough to kill it. Catch-up
  still runs about eight times faster than the producer emits.
- The `hbase` health check probes ports rather than running `hbase shell status`.
  The shell check starts a JVM every 15 seconds and could not finish inside its own
  timeout while the job was writing, so the service flapped to unhealthy while
  serving normally.
- The Spark master is `local[2,4]`, not `local[2]`. In local mode Spark **ignores
  `spark.task.maxFailures`** and hardcodes it to 1; only the `local[N,F]` form sets
  a failure budget. Without the `4`, one retryable HBase error — a region being
  reassigned — fails a task, which fails the batch, which terminates the query.

Allow the WSL 2 VM about 8 GB (`~/.wslconfig`). The job also waits for
`isTableAvailable` on every table before starting, because a reachable master is
not yet a writable cluster: after a restart it answers schema questions well before
the region server has been assigned its regions.

### Java version

The build targets Java 17 and **Spark needs a Java 17 runtime**. Java 24 removed
the Security Manager outright, and Hadoop 3.3's `UserGroupInformation` still calls
`Subject.getSubject`, which now throws — so Spark 3.5 cannot start on a JDK newer
than 23. Compiling on a newer JDK works, because `maven.compiler.release` targets
17 regardless; only the JVM that *runs* Spark matters.

If your default JDK is 24 or newer, install a 17 alongside it — nothing is
replaced — and tell Maven where it is:

```bash
winget install EclipseAdoptium.Temurin.17.JDK
```

Then add a `jdk` toolchain for version 17 to `~/.m2/toolchains.xml`. The parent POM
has a profile that activates only on JDK 24+ and selects that toolchain for
compilation and for the forked test JVMs, so `mvn test` works without changing your
system default. On a machine already running Java 17 the profile stays dormant and
none of this applies.

Two related details, both already handled:

- `streaming-job` passes a list of `--add-opens` flags to its test JVM. Spark
  reaches into JDK internals by reflection — Tungsten's off-heap memory goes
  through `sun.nio.ch.DirectBuffer` — which the module system has denied by default
  since Java 16. `spark-submit` adds these itself, so the containerised job needs no
  equivalent; a JVM that embeds Spark directly does. Without them the first Spark
  call fails as `Could not initialize class org.apache.spark.storage.StorageUtils$`,
  which names none of the above.
- The stack pins `apache/spark:3.5.3-scala2.12-java17-ubuntu`, not the bare `3.5.3`
  tag. That one defaults to a **Java 11** runtime, which cannot load the records in
  the shared event model.

## Tests

```bash
mvn test
```

The simulator tests are the important ones. They assert that the generated motion
is physically plausible — no teleports, bounded speed, reported speed consistent
with actual displacement — and, just as importantly, that risky drivers genuinely
produce more unsafe behaviour than safe ones. If that correlation did not hold, no
model downstream could beat a trivial baseline and the pipeline would be measuring
noise.

The streaming tests run the real aggregations against a static DataFrame, so
windowing is verified without standing up a broker. Three of them guard failures
that would otherwise be silent: that the Spark risk-score expression agrees with
the documented Java formula, that the Spark schema still matches the
`TelemetryEvent` record (a renamed field yields a column of nulls, not an error),
and that the score keeps separating the driver tiers.

> Spark's tests need a Java 17 runtime — see **Java version** above. With the
> toolchain configured, `mvn test` runs all 58 on any JDK.

To inspect the event distribution directly:

```bash
cd event-producer
mvn -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt
java -cp "target/classes;target/test-classes;$(cat target/cp.txt)" \
  com.tessera.risk.producer.DistributionDiagnostic 3600
```

## How unsafe behaviour is generated

Risky driving is **emergent, not injected**. Each driver has a risk tier that
scales only one thing: the chance of starting a *speeding episode*. The route's
braking profile is computed for the lawful speed limit, so a vehicle travelling
above it arrives at corners and stops needing to brake harder than the plan
assumed — and that is the hard brake. Brake hard enough, or brake hard while
already speeding, and it is an incident.

This matters for the machine learning. Because the precursors and the incidents
share a single cause, precursor density genuinely predicts incidents — which is
what makes the prediction task learnable for a real reason rather than being an
uncorrelated coin flip dressed up as a model.

## What the streaming job computes

Two independent streaming queries read `vehicle.telemetry` and aggregate it into
five-minute event-time windows sliding every minute:

| Query | Grouped by | Produces |
|---|---|---|
| `segment-metrics` | road segment | average speed, distinct vehicles, posted limit, compliance ratio, precursor and incident counts, risk index |
| `vehicle-risk` | vehicle | hard-brake / violation / incident counts, average and peak speed, mean speed-over-limit ratio, composite risk score |

Both run in **update** mode rather than append. Append emits a window only once
the watermark has passed its end, so a five-minute window would first reach the
dashboard about seven minutes after the driving it describes. Update emits each
window as it fills, and since an HBase `Put` is an upsert keyed by entity and
window start, the row refines until the window closes.

The risk score is a **rate**, not a count, so windows with different amounts of
data stay comparable — a vehicle parked for four of five minutes is not made to
look safe by contributing few readings. Its weights are calibrated against the
simulator's measured per-tier rates and separate the three driver tiers to roughly
24 / 42 / 82 out of 100; `RiskScoringTest` asserts that separation, because if it
collapsed the alert threshold would fire for everyone or for no one and nothing
would visibly break.

### HBase data model

Three tables. The two time-series tables use a reversed timestamp in the row key —
`entityId#(Long.MAX_VALUE - windowStart)` — so the newest window for an entity is
the *first* row in its range, making "latest state" a one-row scan instead of a
walk through its whole history.

| Table | Row key | Column families |
|---|---|---|
| `segment_metrics` | `segmentId#reverseTs` | `cf_traffic`, `cf_risk` |
| `vehicle_risk` | `vehicleId#reverseTs` | `cf_behavior`, `cf_score` |
| `driver_profile` | `vehicleId` | `cf_profile` |

Families follow access patterns, not topics: HBase reads only the families a query
touches, so the map view never pays for the scoring columns and vice versa. The
time-series tables keep one version per cell — the row key already carries time,
and a second copy of that dimension would be the one nothing can query by.

Alerts go to `vehicle.alerts` with a per-vehicle cooldown. A vehicle over the
threshold stays over it for minutes, and an alert every micro-batch for one
ongoing situation is how alerting gets muted and then ignored.

## Project history

This repository previously held a live vehicle dispatch application. Two tags mark
the earlier states, both still recoverable:

- `v1-live-dispatch` — dispatch platform on Redis, PostgreSQL/PostGIS, TimescaleDB
- `v2-real-data-feed` — the same, with a real MBTA GTFS-Realtime feed and a
  physically validated trajectory generator

Only the road network and the geodesy helpers carry forward into Tessera Risk.
