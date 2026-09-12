package com.tessera.risk.batch;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tessera.risk.common.model.EventType;
import com.tessera.risk.common.model.RiskTier;
import com.tessera.risk.common.model.TelemetryEvent;
import com.tessera.risk.common.spark.FeatureSchema;
import com.tessera.risk.common.spark.FeatureVector;
import com.tessera.risk.common.spark.TelemetrySchema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests for the labelled training set.
 *
 * <p>The label is the part worth testing hardest. It is built by joining the window
 * aggregate against a copy of itself shifted one window back, which is compact but
 * not obvious, and a mistake in it — an off-by-one in either direction — would
 * produce a training set that looks entirely reasonable. Shifting the wrong way
 * would label each window with the incidents it already contains, and the resulting
 * model would score beautifully while predicting the past.
 */
class FeatureExtractionTest {

    private static final int WINDOW_SECONDS = 300;
    private static final long WINDOW_MILLIS = WINDOW_SECONDS * 1000L;
    private static final long MIN_READINGS = 3;
    private static final long BASE = 1_767_225_600_000L; // 2026-01-01T00:00:00Z

    private static SparkSession spark;

    @BeforeAll
    static void startSpark() {
        spark = SparkSession.builder()
                .appName("tessera-risk-batch-tests")
                .master("local[2]")
                .config("spark.sql.session.timeZone", "UTC")
                .config("spark.sql.shuffle.partitions", "2")
                .config("spark.ui.enabled", "false")
                .getOrCreate();
        spark.sparkContext().setLogLevel("WARN");
    }

    @Test
    @DisplayName("a window is labelled by the window that follows it")
    void labelComesFromTheFollowingWindow() {
        // Window 0 is calm, window 1 holds an incident, window 2 is calm again.
        // So window 0 must be positive and window 1 negative — the opposite of what
        // labelling a window with its own contents would give.
        List<Row> rows = extract(
                window("VEH-001", 0, 4, 0),
                window("VEH-001", 1, 4, 1),
                window("VEH-001", 2, 4, 0));

        Map<Long, Double> labels = labelsByWindow(rows);
        assertThat(labels).containsEntry(BASE, 1.0);
        assertThat(labels).containsEntry(BASE + WINDOW_MILLIS, 0.0);
    }

    @Test
    @DisplayName("the last window is dropped, because nothing follows it")
    void theFinalWindowCannotBeLabelled() {
        List<Row> rows = extract(
                window("VEH-001", 0, 4, 0),
                window("VEH-001", 1, 4, 0),
                window("VEH-001", 2, 4, 0));

        // Three windows, two labelled rows: the inner join drops the one with no
        // successor rather than inventing a negative label for it.
        assertThat(rows).hasSize(2);
        assertThat(labelsByWindow(rows)).doesNotContainKey(BASE + 2 * WINDOW_MILLIS);
    }

    @Test
    @DisplayName("one vehicle's incidents never label another's driving")
    void vehiclesAreKeptApart() {
        // VEH-002 has an incident in window 1; VEH-001 does not. If the key were the
        // window alone rather than the pair, VEH-001's window 0 would be labelled
        // positive by a vehicle it has nothing to do with.
        List<Row> rows = extract(
                window("VEH-001", 0, 4, 0),
                window("VEH-001", 1, 4, 0),
                window("VEH-002", 0, 4, 0),
                window("VEH-002", 1, 4, 1));

        Map<String, Double> byVehicle = rows.stream()
                .filter(r -> r.<Long>getAs(FeatureSchema.WINDOW_START) == BASE)
                .collect(Collectors.toMap(
                        r -> r.getAs(FeatureSchema.VEHICLE_ID),
                        r -> r.getAs(FeatureSchema.LABEL)));

        assertThat(byVehicle).containsEntry("VEH-001", 0.0);
        assertThat(byVehicle).containsEntry("VEH-002", 1.0);
    }

    @Test
    @DisplayName("a window thinner than the minimum is dropped entirely")
    void thinWindowsAreDropped() {
        // Window 1 has two readings against a minimum of three. It must not appear as
        // a row, and it must not label window 0 either — a window too thin to
        // describe is also too thin to be evidence about its predecessor.
        List<Row> rows = extract(
                window("VEH-001", 0, 4, 0),
                window("VEH-001", 1, 2, 1),
                window("VEH-001", 2, 4, 0));

        assertThat(labelsByWindow(rows)).doesNotContainKey(BASE + WINDOW_MILLIS);
        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("rates are computed over the window, not over the whole archive")
    void ratesAreWindowLocal() {
        // Window 0: four readings, one of them a hard brake.
        List<Row> rows = extract(
                windowWithHardBrakes("VEH-001", 0, 4, 1),
                window("VEH-001", 1, 4, 0));

        Row first = rows.stream()
                .filter(r -> r.<Long>getAs(FeatureSchema.WINDOW_START) == BASE)
                .findFirst().orElseThrow();

        assertThat(first.<Double>getAs(FeatureSchema.HARD_BRAKE_RATE))
                .isCloseTo(0.25, within(1e-9));
        assertThat(first.<Long>getAs(FeatureSchema.READING_COUNT)).isEqualTo(4);
    }

    @Test
    @DisplayName("windows tumble rather than overlap, so no reading is counted twice")
    void windowsDoNotOverlap() {
        List<Row> rows = extract(
                window("VEH-001", 0, 4, 0),
                window("VEH-001", 1, 4, 0),
                window("VEH-001", 2, 4, 0));

        // Twelve readings across three windows. Overlapping windows would count some
        // readings more than once and the total would exceed twelve — and a training
        // row would then share data with its neighbour, leaking across any split.
        long counted = rows.stream()
                .mapToLong(r -> r.<Long>getAs(FeatureSchema.READING_COUNT))
                .sum();
        assertThat(counted).isEqualTo(8); // the two labelled windows, 4 readings each
        assertThat(rows).extracting(r -> r.<Long>getAs(FeatureSchema.WINDOW_START))
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("the risk tier arrives as a feature, never as the label")
    void riskTierIsAFeature() {
        List<Row> rows = extract(
                window("VEH-001", 0, 4, 0),
                window("VEH-001", 1, 4, 0));

        Row row = rows.get(0);
        // SAFE is ordinal 0; the label is independent of it.
        assertThat(row.<Double>getAs(FeatureSchema.RISK_TIER_ORDINAL))
                .isEqualTo((double) RiskTier.SAFE.ordinal());
        assertThat(row.<Double>getAs(FeatureSchema.LABEL)).isEqualTo(0.0);
    }

    /**
     * Runs the extraction and returns the resulting rows.
     *
     * <p>The result is bound to {@link FeatureSchema#AGGREGATE_ROW} and put through
     * the shared feature derivation before collecting,
     * exactly as {@code BatchMain} does. Rows built by {@code RowFactory} carry no
     * schema of their own, so this is both what makes them addressable by column
     * name and a check that the row width and the schema still agree.
     */
    @SafeVarargs
    private static List<Row> extract(List<Row>... windows) {
        List<Row> telemetry = new ArrayList<>();
        for (List<Row> w : windows) {
            telemetry.addAll(w);
        }
        Dataset<Row> frame = spark.createDataFrame(telemetry, TelemetrySchema.TELEMETRY);
        return FeatureVector.withFeatures(spark.createDataFrame(
                        FeatureExtraction.labelledRows(
                                frame.javaRDD(), WINDOW_SECONDS, MIN_READINGS),
                        FeatureSchema.AGGREGATE_ROW))
                .collectAsList();
    }

    private static Map<Long, Double> labelsByWindow(List<Row> rows) {
        return rows.stream().collect(Collectors.toMap(
                r -> r.getAs(FeatureSchema.WINDOW_START),
                r -> r.getAs(FeatureSchema.LABEL)));
    }

    /** {@code readings} readings in window {@code index}, {@code incidents} of them incidents. */
    private static List<Row> window(String vehicleId, int index, int readings, int incidents) {
        return buildWindow(vehicleId, index, readings, incidents, 0);
    }

    private static List<Row> windowWithHardBrakes(String vehicleId, int index,
                                                  int readings, int hardBrakes) {
        return buildWindow(vehicleId, index, readings, 0, hardBrakes);
    }

    private static List<Row> buildWindow(String vehicleId, int index, int readings,
                                         int incidents, int hardBrakes) {
        List<Row> out = new ArrayList<>(readings);
        for (int i = 0; i < readings; i++) {
            EventType type = EventType.NORMAL;
            if (i < incidents) {
                type = EventType.INCIDENT;
            } else if (i < incidents + hardBrakes) {
                type = EventType.HARD_BRAKE;
            }
            long ts = BASE + index * WINDOW_MILLIS + i * 1000L;
            TelemetryEvent event = new TelemetryEvent(
                    vehicleId, vehicleId.replace("VEH", "DRV"), RiskTier.SAFE,
                    42.36, -71.06, 35.0, 90.0, "SEG-00001", 50.0,
                    type, type == EventType.NORMAL ? 0.0 : 0.7, ts);
            out.add(org.apache.spark.sql.RowFactory.create(
                    event.vehicleId(), event.driverId(), event.riskTier().name(),
                    event.lat(), event.lon(), event.speedKph(), event.headingDeg(),
                    event.segmentId(), event.speedLimitKph(), event.eventType().name(),
                    event.severity(), event.ts()));
        }
        return out;
    }
}
