package com.tessera.risk.streaming;

import java.util.ArrayList;
import java.util.List;

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Encoders;
import org.apache.spark.sql.Row;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tessera.risk.common.model.EventType;
import com.tessera.risk.common.model.RiskTier;
import com.tessera.risk.common.model.TelemetryEvent;
import com.tessera.risk.common.spark.FeatureSchema;
import com.tessera.risk.common.spark.FeatureVector;

import static org.apache.spark.sql.functions.col;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * End-to-end tests of the aggregation, run against a static DataFrame.
 *
 * <p>The input is built with the production {@link TelemetryEvent#toJson()}, not a
 * hand-written JSON fixture, so a change to the event model that the Spark schema
 * does not follow fails here rather than in production as a column of nulls.
 *
 * <p>Windows slide by a minute, so a burst of events belongs to several
 * overlapping windows at once. The assertions target the one window that contains
 * every event — {@code [BASE, BASE + 5min)} — because that is the window whose
 * expected contents can be stated exactly.
 */
class RiskWindowsTest {

    /** 2026-01-01T00:00:00Z, deliberately aligned to a five-minute boundary. */
    private static final long BASE = 1_767_225_600_000L;

    private static final int READINGS_PER_VEHICLE = 300;
    private static final double SEG_A_LIMIT = 50.0;
    private static final double SEG_B_LIMIT = 80.0;

    // VEH-001 on SEG-A: mostly lawful, a few hard brakes, two brief violations.
    private static final int V1_HARD_BRAKES = 6;
    private static final int V1_VIOLATIONS = 2;
    // VEH-002 on SEG-A: markedly worse, and the only vehicle with incidents.
    private static final int V2_HARD_BRAKES = 16;
    private static final int V2_VIOLATIONS = 2;
    private static final int V2_INCIDENTS = 2;

    private static Dataset<Row> segments;
    private static Dataset<Row> vehicles;

    @BeforeAll
    static void aggregate() {
        Dataset<Row> source = SparkTestSupport.session()
                .createDataset(payloads(), Encoders.STRING())
                .toDF("value");
        Dataset<Row> parsed = RiskWindows.parse(source);
        StreamingConfig config = SparkTestSupport.config();
        segments = RiskWindows.segmentWindows(parsed, config).cache();
        vehicles = RiskWindows.vehicleWindows(parsed, config).cache();
    }

    @Test
    @DisplayName("malformed and incomplete payloads are dropped, not fatal")
    void malformedPayloadsAreDropped() {
        List<String> mixed = new ArrayList<>(List.of(
                "this is not json at all",
                "{\"driverId\":\"DRV-009\",\"speedKph\":40}",   // no vehicleId
                "{}"));
        mixed.add(reading("VEH-100", "DRV-100", RiskTier.SAFE, "SEG-C",
                60.0, 30.0, EventType.NORMAL, BASE));

        Dataset<Row> parsed = RiskWindows.parse(SparkTestSupport.session()
                .createDataset(mixed, Encoders.STRING()).toDF("value"));

        assertThat(parsed.count()).isEqualTo(1);
        assertThat(parsed.first().<String>getAs(RiskWindows.VEHICLE_ID)).isEqualTo("VEH-100");
    }

    @Test
    @DisplayName("event time comes from the payload, not from arrival order")
    void windowsFollowEventTime() {
        Row window = vehicleRow("VEH-001");
        assertThat(window.<Long>getAs(RiskWindows.WINDOW_START)).isEqualTo(BASE);
        assertThat(window.<Long>getAs(RiskWindows.WINDOW_END)).isEqualTo(BASE + 300_000L);
    }

    @Test
    @DisplayName("per-vehicle event counts are exact")
    void vehicleCountsAreExact() {
        Row one = vehicleRow("VEH-001");
        assertThat(one.<Long>getAs(RiskWindows.READING_COUNT)).isEqualTo(READINGS_PER_VEHICLE);
        assertThat(one.<Long>getAs(RiskWindows.HARD_BRAKE_COUNT)).isEqualTo(V1_HARD_BRAKES);
        assertThat(one.<Long>getAs(RiskWindows.SPEED_VIOLATION_COUNT)).isEqualTo(V1_VIOLATIONS);
        assertThat(one.<Long>getAs(RiskWindows.INCIDENT_COUNT)).isZero();

        Row two = vehicleRow("VEH-002");
        assertThat(two.<Long>getAs(RiskWindows.HARD_BRAKE_COUNT)).isEqualTo(V2_HARD_BRAKES);
        assertThat(two.<Long>getAs(RiskWindows.SPEED_VIOLATION_COUNT)).isEqualTo(V2_VIOLATIONS);
        assertThat(two.<Long>getAs(RiskWindows.INCIDENT_COUNT)).isEqualTo(V2_INCIDENTS);
    }

    @Test
    @DisplayName("incidents are counted apart from hard brakes, never twice")
    void incidentsAreNotDoubleCounted() {
        Row two = vehicleRow("VEH-002");
        long classified = two.<Long>getAs(RiskWindows.HARD_BRAKE_COUNT)
                + two.<Long>getAs(RiskWindows.SPEED_VIOLATION_COUNT)
                + two.<Long>getAs(RiskWindows.INCIDENT_COUNT);
        assertThat(classified).isLessThan(two.<Long>getAs(RiskWindows.READING_COUNT));
        assertThat(classified).isEqualTo(V2_HARD_BRAKES + V2_VIOLATIONS + V2_INCIDENTS);
    }

    @Test
    @DisplayName("driver and tier travel with the vehicle through the aggregation")
    void identityIsCarriedThrough() {
        Row one = vehicleRow("VEH-001");
        assertThat(one.<String>getAs(RiskWindows.DRIVER_ID)).isEqualTo("DRV-001");
        assertThat(one.<String>getAs(RiskWindows.RISK_TIER)).isEqualTo(RiskTier.SAFE.name());
        assertThat(vehicleRow("VEH-002").<String>getAs(RiskWindows.RISK_TIER))
                .isEqualTo(RiskTier.RISKY.name());
    }

    @Test
    @DisplayName("the Spark risk score agrees with the documented formula")
    void sparkScoreMatchesJavaFormula() {
        // The score is published to HBase and drives alerting, so the expression
        // Spark evaluates and the formula RiskScoring documents have to be the same
        // thing. They are written twice, which is exactly why this is asserted.
        for (Row row : vehicles.collectAsList()) {
            double expected = RiskScoring.vehicleRiskScore(
                    row.getAs(RiskWindows.HARD_BRAKE_COUNT),
                    row.getAs(RiskWindows.SPEED_VIOLATION_COUNT),
                    row.getAs(RiskWindows.INCIDENT_COUNT),
                    row.getAs(RiskWindows.READING_COUNT));
            // The cast is load-bearing: as(String, Object...) is varargs, so without
            // it Java infers T = Object[] for getAs and the String is cast to an array.
            String vehicleId = row.getAs(RiskWindows.VEHICLE_ID);
            assertThat(row.<Double>getAs(RiskWindows.RISK_SCORE))
                    .as("score for %s", vehicleId)
                    .isCloseTo(expected, within(1e-9));
        }
    }

    @Test
    @DisplayName("the risky vehicle scores above the safe one")
    void scoresRankVehiclesCorrectly() {
        assertThat(vehicleRow("VEH-002").<Double>getAs(RiskWindows.RISK_SCORE))
                .isGreaterThan(vehicleRow("VEH-001").<Double>getAs(RiskWindows.RISK_SCORE));
        assertThat(vehicleRow("VEH-003").<Double>getAs(RiskWindows.RISK_SCORE)).isZero();
    }

    @Test
    @DisplayName("speed aggregates reflect the readings")
    void speedAggregatesAreCorrect() {
        Row three = vehicleRow("VEH-003");
        assertThat(three.<Double>getAs(RiskWindows.AVG_SPEED_KPH)).isCloseTo(70.0, within(1e-6));
        assertThat(three.<Double>getAs(RiskWindows.MAX_SPEED_KPH)).isCloseTo(70.0, within(1e-6));
        // 70 in an 80 zone.
        assertThat(three.<Double>getAs(RiskWindows.AVG_OVER_LIMIT_RATIO))
                .isCloseTo(70.0 / SEG_B_LIMIT, within(1e-6));

        assertThat(vehicleRow("VEH-001").<Double>getAs(RiskWindows.MAX_SPEED_KPH))
                .isCloseTo(60.0, within(1e-6));
    }

    @Test
    @DisplayName("a segment window aggregates every vehicle that crossed it")
    void segmentAggregatesAcrossVehicles() {
        Row segA = segmentRow("SEG-A");
        assertThat(segA.<Long>getAs(RiskWindows.READING_COUNT)).isEqualTo(2L * READINGS_PER_VEHICLE);
        assertThat(segA.<Long>getAs(RiskWindows.VEHICLE_COUNT)).isEqualTo(2L);
        assertThat(segA.<Double>getAs(RiskWindows.SPEED_LIMIT_KPH)).isCloseTo(SEG_A_LIMIT, within(1e-6));
        assertThat(segA.<Long>getAs(RiskWindows.HARD_BRAKE_COUNT))
                .isEqualTo(V1_HARD_BRAKES + V2_HARD_BRAKES);
        assertThat(segA.<Long>getAs(RiskWindows.INCIDENT_COUNT)).isEqualTo(V2_INCIDENTS);
    }

    @Test
    @DisplayName("compliance measures readings at or under the posted limit")
    void complianceRatioIsCorrect() {
        // On SEG-A only the violations and incidents exceed 50 kph.
        long overLimit = V1_VIOLATIONS + V2_VIOLATIONS + V2_INCIDENTS;
        long readings = 2L * READINGS_PER_VEHICLE;
        assertThat(segmentRow("SEG-A").<Double>getAs(RiskWindows.COMPLIANCE_RATIO))
                .isCloseTo((double) (readings - overLimit) / readings, within(1e-9));
        // Every reading on SEG-B is lawful.
        assertThat(segmentRow("SEG-B").<Double>getAs(RiskWindows.COMPLIANCE_RATIO))
                .isCloseTo(1.0, within(1e-9));
    }

    @Test
    @DisplayName("the segment carrying the incidents has the higher risk index")
    void segmentRiskIndexRanksSegments() {
        assertThat(segmentRow("SEG-A").<Double>getAs(RiskWindows.RISK_INDEX))
                .isGreaterThan(segmentRow("SEG-B").<Double>getAs(RiskWindows.RISK_INDEX));
        assertThat(segmentRow("SEG-B").<Double>getAs(RiskWindows.RISK_INDEX)).isZero();
    }

    @Test
    @DisplayName("sliding windows overlap, so one burst appears in several of them")
    void windowsOverlap() {
        // Five-minute windows advancing a minute at a time: a 300-second burst is
        // seen by nine of them. If this ever equalled one, the slide would have been
        // lost and the aggregation would have become tumbling.
        long windowsForVehicle = vehicles.filter(col(RiskWindows.VEHICLE_ID).equalTo("VEH-001"))
                .count();
        assertThat(windowsForVehicle).isEqualTo(9);
    }

    @Test
    @DisplayName("vehicle windows carry every column the model's features need")
    void vehicleWindowsSatisfyTheFeatureContract() {
        // The model is fitted to features the batch job derives from Parquet and
        // scored on features derived here from Kafka. Both derivations go through
        // FeatureVector, so they cannot disagree on arithmetic — but only if this
        // aggregation actually produces the inputs it needs. A column missing here
        // would reach the model as a null feature, and a null feature does not throw:
        // the model keeps returning confident probabilities that mean nothing.
        assertThat(vehicles.columns())
                .containsAll(java.util.Arrays.asList(FeatureSchema.AGGREGATE_COLUMNS));
    }

    @Test
    @DisplayName("the feature derivation runs on a real windowed aggregate")
    void featuresDeriveFromTheAggregation() {
        // Proves the contract end to end rather than by column name alone: if an
        // input were the wrong type, or the derivation disagreed with the aggregate's
        // shape, this is where it surfaces.
        Row row = FeatureVector.withFeatures(vehicles)
                .filter(col(RiskWindows.VEHICLE_ID).equalTo("VEH-001")
                        .and(col(RiskWindows.WINDOW_START).equalTo(BASE)))
                .first();

        assertThat(row.<Double>getAs(FeatureSchema.HARD_BRAKE_RATE))
                .isCloseTo((double) V1_HARD_BRAKES / READINGS_PER_VEHICLE, within(1e-9));
        assertThat(row.<Double>getAs(FeatureSchema.RISK_TIER_ORDINAL))
                .isEqualTo((double) RiskTier.SAFE.ordinal());
        // BASE is midnight UTC, so the window starts in hour zero.
        assertThat(row.<Double>getAs(FeatureSchema.HOUR_OF_DAY)).isEqualTo(0.0);
        assertThat(row.<Double>getAs(FeatureSchema.MOVING_RATIO)).isEqualTo(1.0);
    }

    private static Row vehicleRow(String vehicleId) {
        return vehicles
                .filter(col(RiskWindows.VEHICLE_ID).equalTo(vehicleId)
                        .and(col(RiskWindows.WINDOW_START).equalTo(BASE)))
                .first();
    }

    private static Row segmentRow(String segmentId) {
        return segments
                .filter(col(RiskWindows.SEGMENT_ID).equalTo(segmentId)
                        .and(col(RiskWindows.WINDOW_START).equalTo(BASE)))
                .first();
    }

    /**
     * Three vehicles, five minutes of driving, one reading a second.
     *
     * <p>Behavioural events are placed at the end of each vehicle's run so the
     * counts are obvious from the constants rather than from arithmetic.
     */
    private static List<String> payloads() {
        List<String> out = new ArrayList<>();
        out.addAll(run("VEH-001", "DRV-001", RiskTier.SAFE, "SEG-A", SEG_A_LIMIT,
                40.0, V1_HARD_BRAKES, 45.0, V1_VIOLATIONS, 60.0, 0, 0.0));
        out.addAll(run("VEH-002", "DRV-002", RiskTier.RISKY, "SEG-A", SEG_A_LIMIT,
                30.0, V2_HARD_BRAKES, 35.0, V2_VIOLATIONS, 62.0, V2_INCIDENTS, 70.0));
        out.addAll(run("VEH-003", "DRV-003", RiskTier.AVERAGE, "SEG-B", SEG_B_LIMIT,
                70.0, 0, 0.0, 0, 0.0, 0, 0.0));
        return out;
    }

    private static List<String> run(String vehicleId, String driverId, RiskTier tier,
                                    String segmentId, double limit, double cruiseKph,
                                    int hardBrakes, double brakeKph,
                                    int violations, double violationKph,
                                    int incidents, double incidentKph) {
        List<String> out = new ArrayList<>(READINGS_PER_VEHICLE);
        int normal = READINGS_PER_VEHICLE - hardBrakes - violations - incidents;
        for (int i = 0; i < READINGS_PER_VEHICLE; i++) {
            long ts = BASE + i * 1000L;
            if (i < normal) {
                out.add(reading(vehicleId, driverId, tier, segmentId, limit, cruiseKph,
                        EventType.NORMAL, ts));
            } else if (i < normal + hardBrakes) {
                out.add(reading(vehicleId, driverId, tier, segmentId, limit, brakeKph,
                        EventType.HARD_BRAKE, ts));
            } else if (i < normal + hardBrakes + violations) {
                out.add(reading(vehicleId, driverId, tier, segmentId, limit, violationKph,
                        EventType.SPEED_VIOLATION, ts));
            } else {
                out.add(reading(vehicleId, driverId, tier, segmentId, limit, incidentKph,
                        EventType.INCIDENT, ts));
            }
        }
        return out;
    }

    private static String reading(String vehicleId, String driverId, RiskTier tier,
                                  String segmentId, double limit, double speedKph,
                                  EventType type, long ts) {
        double severity = type == EventType.NORMAL ? 0.0 : 0.7;
        return new TelemetryEvent(vehicleId, driverId, tier, 42.36, -71.06,
                speedKph, 90.0, segmentId, limit, type, severity, ts).toJson();
    }
}
