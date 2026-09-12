package com.tessera.risk.archive;

import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SaveMode;
import org.apache.spark.sql.SparkSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.tessera.risk.common.road.RoadNetwork;
import com.tessera.risk.common.road.RoadNetworkLoader;
import com.tessera.risk.common.spark.TelemetrySchema;
import com.tessera.risk.simulation.FleetSimulator;
import com.tessera.risk.simulation.SimulatorConfig;

import static org.apache.spark.sql.functions.expr;

/**
 * Builds the historical telemetry archive the model is trained on.
 *
 * <h2>Why the archive is generated rather than captured</h2>
 * The obvious alternative is to let the streaming job archive what passes through
 * it. That would take a week of wall-clock time to produce a week of training
 * data, and every change to the event thresholds would cost another week. Driving
 * the same simulation offline produces the same data in a couple of minutes.
 *
 * <p>It is only legitimate because the physics and the event classification live
 * in the {@code simulation} module, which the live producer also drives. If the
 * two had separate implementations, a model fitted here would be fitted to data
 * the live stream does not actually produce — and nothing would report that.
 *
 * <h2>How it parallelises</h2>
 * The fleet is split into slices and each slice is simulated in its own task. That
 * is sound because vehicles in this model never interact: no traffic, no queueing,
 * no collisions. Each one plans a route and reacts to road geometry and its own
 * speed alone, so a partitioned run reproduces a whole-fleet run exactly — a
 * property {@code FleetSimulatorTest} asserts reading by reading rather than
 * leaving to argument.
 *
 * <p>Each task streams its readings through {@link FleetSliceIterator} rather than
 * collecting them, so memory stays flat however long the archive is.
 *
 * <h2>Partitioning</h2>
 * Written partitioned by simulated date, so the batch job can read a subset
 * without touching the rest. The date is derived from the event timestamp rather
 * than supplied alongside it, which makes it impossible for a row to land in a
 * partition that disagrees with its own clock.
 */
public final class ArchiveMain {

    private static final Logger log = LoggerFactory.getLogger(ArchiveMain.class);

    private static final int SECONDS_PER_DAY = 86_400;

    private ArchiveMain() {
    }

    public static void main(String[] args) {
        ArchiveConfig config = ArchiveConfig.fromEnvironment();
        SimulatorConfig simulatorConfig = SimulatorConfig.fromEnvironment();
        long expectedRows = config.totalTicks() * simulatorConfig.vehicleCount();

        log.info("Tessera Risk archive generator");
        log.info("  output      {}", config.outputPath());
        log.info("  simulating  {} day(s) from {}, {} vehicles at {} ms/tick",
                config.days(), config.startDate(), simulatorConfig.vehicleCount(),
                simulatorConfig.tickMillis());
        log.info("  volume      {} readings across {} slice(s)",
                String.format("%,d", expectedRows), config.parallelism());
        log.info("ALL TELEMETRY IS SIMULATED. Vehicles are driven along a real "
                + "OpenStreetMap road network under real posted speed limits and enforced "
                + "physical acceleration and braking limits. No physical vehicles, "
                + "telematics feed or personal data are involved.");

        SparkSession spark = SparkSession.builder()
                .appName("tessera-risk-archive-generator")
                .master(envString("SPARK_MASTER_URL", "local[*,4]"))
                .config("spark.sql.session.timeZone", "UTC")
                // Parquet's default is snappy already, but stating it means the
                // archive's encoding is a decision on the record rather than
                // whatever the runtime happened to default to.
                .config("spark.sql.parquet.compression.codec", "snappy")
                .getOrCreate();

        long startedAt = System.currentTimeMillis();
        write(spark, config, simulatorConfig);

        // Read the footers back rather than trusting the arithmetic. Parquet stores
        // row counts in its metadata, so this costs almost nothing and it is the
        // only thing here that actually proves the archive holds what it should.
        long written = spark.read().parquet(config.outputPath()).count();
        Duration elapsed = Duration.ofMillis(System.currentTimeMillis() - startedAt);

        log.info("Wrote {} readings to {} in {}m {}s",
                String.format("%,d", written), config.outputPath(),
                elapsed.toMinutes(), elapsed.toSecondsPart());
        if (written != expectedRows) {
            log.warn("Expected {} readings but the archive holds {}",
                    String.format("%,d", expectedRows), String.format("%,d", written));
        }
        spark.stop();
    }

    /** Simulate the whole period across parallel fleet slices and write it out. */
    static void write(SparkSession spark, ArchiveConfig config, SimulatorConfig simulatorConfig) {
        List<int[]> slices = sliceFleet(simulatorConfig.vehicleCount(), config.parallelism());
        long startMillis = config.startDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        long totalTicks = config.totalTicks();
        String roadResource = envString("TESSERA_ROAD_NETWORK", "roadgraph/roadgraph.json");

        JavaSparkContext sc = JavaSparkContext.fromSparkContext(spark.sparkContext());

        // One partition per slice, so each task owns a disjoint set of vehicles.
        JavaRDD<Row> readings = sc.parallelize(slices, slices.size())
                .mapPartitions(sliceIterator -> {
                    if (!sliceIterator.hasNext()) {
                        return Collections.<Row>emptyIterator();
                    }
                    int[] slice = sliceIterator.next();
                    // Built on the executor: RoadNetwork is large and not
                    // serializable, and loading it from the classpath here costs
                    // less than shipping it would.
                    RoadNetwork network = RoadNetworkLoader.fromClasspath(roadResource);
                    FleetSimulator simulator = new FleetSimulator(
                            network, simulatorConfig, slice[0], slice[1]);
                    return new FleetSliceIterator(
                            simulator, startMillis, simulatorConfig.tickMillis(), totalTicks);
                });

        Dataset<Row> archive = spark
                .createDataFrame(readings, TelemetrySchema.TELEMETRY)
                // Derived, never supplied: a row cannot land in a partition that
                // contradicts its own timestamp.
                .withColumn("date", expr("to_date(timestamp_millis(ts))"));

        archive.write()
                .mode(SaveMode.valueOf(capitalise(config.writeMode())))
                .partitionBy("date")
                .parquet(config.outputPath());
    }

    /**
     * Divide {@code vehicleCount} vehicles into at most {@code slices} contiguous
     * ranges, as evenly as they divide.
     */
    static List<int[]> sliceFleet(int vehicleCount, int slices) {
        int effective = Math.max(1, Math.min(slices, vehicleCount));
        List<int[]> ranges = new ArrayList<>(effective);
        int base = vehicleCount / effective;
        int remainder = vehicleCount % effective;
        int from = 0;
        for (int i = 0; i < effective; i++) {
            // The first `remainder` slices take one extra vehicle, so no slice is
            // ever more than one vehicle larger than another.
            int size = base + (i < remainder ? 1 : 0);
            ranges.add(new int[] {from, from + size});
            from += size;
        }
        return ranges;
    }

    private static String capitalise(String mode) {
        return Character.toUpperCase(mode.charAt(0))
                + mode.substring(1).toLowerCase(java.util.Locale.ROOT);
    }

    private static String envString(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v.trim();
    }
}
