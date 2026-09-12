package com.tessera.risk.streaming;

import java.util.List;

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tessera.risk.common.model.RiskAlert;
import com.tessera.risk.common.model.RiskTier;
import com.tessera.risk.streaming.alert.AlertRules;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for which windows raise an alert.
 *
 * <p>The input here is a hand-built frame in the shape {@code vehicleWindows}
 * produces, rather than the output of a real aggregation. The rules are what is
 * under test, and deriving the input from an aggregation would make a failure
 * ambiguous between the two.
 */
class AlertRulesTest {

    private static final long BASE = 1_767_225_600_000L;
    private static final long NOW = BASE + 600_000L;

    private static final StructType SCHEMA = new StructType()
            .add(RiskWindows.VEHICLE_ID, DataTypes.StringType)
            .add(RiskWindows.DRIVER_ID, DataTypes.StringType)
            .add(RiskWindows.RISK_TIER, DataTypes.StringType)
            .add(RiskWindows.READING_COUNT, DataTypes.LongType)
            .add(RiskWindows.HARD_BRAKE_COUNT, DataTypes.LongType)
            .add(RiskWindows.SPEED_VIOLATION_COUNT, DataTypes.LongType)
            .add(RiskWindows.INCIDENT_COUNT, DataTypes.LongType)
            .add(RiskWindows.RISK_SCORE, DataTypes.DoubleType)
            .add(RiskWindows.WINDOW_START, DataTypes.LongType)
            .add(RiskWindows.WINDOW_END, DataTypes.LongType);

    @Test
    @DisplayName("the window with the most evidence wins, not the newest one")
    void fullestWindowWins() {
        // Both windows are open, so both already contain the vehicle's latest
        // reading; the newer one simply reaches less far back. Its 95 is a rate over
        // 100 readings, while the 20 over 280 readings is the calibrated
        // five-minute view of the same driving, ending at the same instant.
        Dataset<Row> batch = frame(
                window("VEH-001", RiskTier.RISKY, 20.0, 0, BASE, 280L),
                window("VEH-001", RiskTier.RISKY, 95.0, 0, BASE + 240_000L, 100L));

        assertThat(AlertRules.evaluate(batch, SparkTestSupport.config(), NOW)).isEmpty();
    }

    @Test
    @DisplayName("equally full windows fall back to the newest")
    void equallyFullWindowsPreferTheNewest() {
        // Before a vehicle has been reporting for a full window, several of its
        // windows hold the same readings. The tie-break keeps that deterministic.
        Dataset<Row> batch = frame(
                window("VEH-001", RiskTier.RISKY, 90.0, 0, BASE, 200L),
                window("VEH-001", RiskTier.RISKY, 5.0, 0, BASE + 240_000L, 200L));

        assertThat(AlertRules.evaluate(batch, SparkTestSupport.config(), NOW)).isEmpty();
    }

    @Test
    @DisplayName("a score above the threshold raises one alert")
    void scoreThresholdRaisesAnAlert() {
        Dataset<Row> batch = frame(window("VEH-002", RiskTier.RISKY, 70.0, 0, BASE));

        List<RiskAlert> alerts = AlertRules.evaluate(batch, SparkTestSupport.config(), NOW);

        assertThat(alerts).hasSize(1);
        RiskAlert alert = alerts.get(0);
        assertThat(alert.vehicleId()).isEqualTo("VEH-002");
        assertThat(alert.riskTier()).isEqualTo(RiskTier.RISKY);
        assertThat(alert.riskScore()).isEqualTo(70.0);
        assertThat(alert.raisedAt()).isEqualTo(NOW);
        assertThat(alert.readingCount()).isEqualTo(300);
        // The sample size is in the message, because a rate without its denominator
        // is not something an operator can act on.
        assertThat(alert.reason()).contains("hard brake").contains("300 readings");
    }

    @Test
    @DisplayName("a single incident alerts even when the score is low")
    void anIncidentAlwaysAlerts() {
        // One incident in a five-minute window barely moves a rate-based score, and
        // it is precisely the event the system exists to surface.
        Dataset<Row> batch = frame(window("VEH-003", RiskTier.SAFE, 12.0, 1, BASE));

        List<RiskAlert> alerts = AlertRules.evaluate(batch, SparkTestSupport.config(), NOW);

        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).incidentCount()).isEqualTo(1);
        assertThat(alerts.get(0).reason()).contains("incident");
    }

    @Test
    @DisplayName("ordinary driving raises nothing")
    void quietDrivingDoesNotAlert() {
        Dataset<Row> batch = frame(
                window("VEH-004", RiskTier.SAFE, 18.0, 0, BASE),
                window("VEH-005", RiskTier.AVERAGE, 42.0, 0, BASE));

        assertThat(AlertRules.evaluate(batch, SparkTestSupport.config(), NOW)).isEmpty();
    }

    @Test
    @DisplayName("one alert per vehicle, never one per overlapping window")
    void oneAlertPerVehicle() {
        // Five-minute windows sliding by a minute put VEH-006 in three of them at
        // once. Without collapsing, an operator would get three notifications about
        // one vehicle having one bad stretch.
        Dataset<Row> batch = frame(
                window("VEH-006", RiskTier.RISKY, 80.0, 0, BASE, 300L),
                window("VEH-006", RiskTier.RISKY, 82.0, 0, BASE + 60_000L, 240L),
                window("VEH-006", RiskTier.RISKY, 88.0, 0, BASE + 120_000L, 180L),
                window("VEH-007", RiskTier.RISKY, 91.0, 0, BASE + 120_000L, 300L));

        List<RiskAlert> alerts = AlertRules.evaluate(batch, SparkTestSupport.config(), NOW);

        assertThat(alerts).hasSize(2);
        assertThat(alerts).extracting(RiskAlert::vehicleId)
                .containsExactlyInAnyOrder("VEH-006", "VEH-007");
        // The 300-reading window, not the highest-scoring one.
        assertThat(alerts).filteredOn(a -> a.vehicleId().equals("VEH-006"))
                .first()
                .extracting(RiskAlert::riskScore)
                .isEqualTo(80.0);
    }

    @Test
    @DisplayName("a barely-filled window cannot alert on its score alone")
    void sparseWindowsAreNotEvenRanked() {
        // A window holding fifteen seconds of data is below the floor entirely, so
        // it is dropped before ranking rather than competing on reading count. Its
        // score of 95 is an artefact of the small denominator; the 70 over a full
        // window is the honest number.
        Dataset<Row> batch = frame(
                window("VEH-008", RiskTier.RISKY, 70.0, 0, BASE, 300L),
                window("VEH-008", RiskTier.RISKY, 95.0, 0, BASE + 120_000L, 15L));

        List<RiskAlert> alerts = AlertRules.evaluate(batch, SparkTestSupport.config(), NOW);

        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).riskScore()).isEqualTo(70.0);
        assertThat(alerts.get(0).windowStart()).isEqualTo(BASE);
    }

    @Test
    @DisplayName("an incident in a sparse window still alerts")
    void incidentsIgnoreTheMinimumSampleSize() {
        // An incident is an observation, not a rate. Waiting a minute to report one
        // because the window is young would defeat the point of the system.
        Dataset<Row> batch = frame(window("VEH-009", RiskTier.SAFE, 8.0, 1, BASE, 12L));

        assertThat(AlertRules.evaluate(batch, SparkTestSupport.config(), NOW))
                .extracting(RiskAlert::vehicleId)
                .containsExactly("VEH-009");
    }

    private static Dataset<Row> frame(Row... rows) {
        return SparkTestSupport.session().createDataFrame(List.of(rows), SCHEMA);
    }

    private static Row window(String vehicleId, RiskTier tier, double riskScore,
                              long incidents, long windowStart) {
        return window(vehicleId, tier, riskScore, incidents, windowStart, 300L);
    }

    private static Row window(String vehicleId, RiskTier tier, double riskScore,
                              long incidents, long windowStart, long readings) {
        return RowFactory.create(
                vehicleId, vehicleId.replace("VEH", "DRV"), tier.name(),
                readings, 5L, 2L, incidents, riskScore, windowStart, windowStart + 300_000L);
    }
}
