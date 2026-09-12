package com.tessera.risk.streaming;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Calibration tests for the risk score.
 *
 * <p>These are not arithmetic checks — they assert the properties the rest of the
 * system relies on. If the score stopped separating the driver tiers, the alert
 * threshold would either fire for everyone or for no one, and the dashboard would
 * colour the whole fleet the same. That failure is silent: the pipeline would keep
 * running and keep producing numbers.
 *
 * <p>The inputs below are the simulator's measured per-tier rates scaled to a
 * 300-reading window, which is one five-minute window at one reading per second.
 */
class RiskScoringTest {

    /** Readings in a five-minute window at 1 Hz. */
    private static final long WINDOW_READINGS = 300;

    // Measured rates, split between hard brakes and violations in the ratio the
    // simulator actually produces (roughly 94:6).
    private static final long SAFE_HARD_BRAKES = 8;
    private static final long SAFE_VIOLATIONS = 0;
    private static final long SAFE_INCIDENTS = 1;

    private static final long AVERAGE_HARD_BRAKES = 18;
    private static final long AVERAGE_VIOLATIONS = 1;
    private static final long AVERAGE_INCIDENTS = 1;

    private static final long RISKY_HARD_BRAKES = 34;
    private static final long RISKY_VIOLATIONS = 2;
    private static final long RISKY_INCIDENTS = 2;

    @Test
    @DisplayName("the score separates the three driver tiers")
    void tiersAreSeparated() {
        double safe = score(SAFE_HARD_BRAKES, SAFE_VIOLATIONS, SAFE_INCIDENTS);
        double average = score(AVERAGE_HARD_BRAKES, AVERAGE_VIOLATIONS, AVERAGE_INCIDENTS);
        double risky = score(RISKY_HARD_BRAKES, RISKY_VIOLATIONS, RISKY_INCIDENTS);

        assertThat(safe).isLessThan(average);
        assertThat(average).isLessThan(risky);
        // A gap of at least fifteen points between neighbours: enough that ordinary
        // variation between windows cannot reorder the tiers.
        assertThat(average - safe).isGreaterThan(15.0);
        assertThat(risky - average).isGreaterThan(15.0);
    }

    @Test
    @DisplayName("the default alert threshold sits between average and risky driving")
    void thresholdDiscriminates() {
        double threshold = StreamingConfig.fromEnvironment().alertScoreThreshold();
        assertThat(score(SAFE_HARD_BRAKES, SAFE_VIOLATIONS, SAFE_INCIDENTS))
                .isLessThan(threshold);
        assertThat(score(AVERAGE_HARD_BRAKES, AVERAGE_VIOLATIONS, AVERAGE_INCIDENTS))
                .isLessThan(threshold);
        assertThat(score(RISKY_HARD_BRAKES, RISKY_VIOLATIONS, RISKY_INCIDENTS))
                .isGreaterThan(threshold);
    }

    @Test
    @DisplayName("risky driving does not pin at the ceiling")
    void riskyDrivingLeavesHeadroom() {
        // If the worst ordinary driver already scored 100, nothing worse could be
        // distinguished from them and the top of the scale would carry no meaning.
        assertThat(score(RISKY_HARD_BRAKES, RISKY_VIOLATIONS, RISKY_INCIDENTS))
                .isLessThan(95.0);
    }

    @Test
    @DisplayName("the score is bounded to 0..100 even for absurd input")
    void scoreIsBounded() {
        assertThat(score(0, 0, 0)).isEqualTo(0.0);
        assertThat(RiskScoring.vehicleRiskScore(WINDOW_READINGS, WINDOW_READINGS,
                WINDOW_READINGS, WINDOW_READINGS)).isEqualTo(100.0);
    }

    @Test
    @DisplayName("a window with no readings scores zero rather than dividing by zero")
    void emptyWindowScoresZero() {
        assertThat(RiskScoring.vehicleRiskScore(0, 0, 0, 0)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("the score is a rate, so window length does not change it")
    void scoreIsIndependentOfWindowLength() {
        double short_ = RiskScoring.vehicleRiskScore(4, 1, 0, 150);
        double long_ = RiskScoring.vehicleRiskScore(8, 2, 0, 300);
        assertThat(short_).isCloseTo(long_, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("one incident outweighs one violation, which outweighs one hard brake")
    void severityOrderingHolds() {
        double oneBrake = RiskScoring.vehicleRiskScore(1, 0, 0, WINDOW_READINGS);
        double oneViolation = RiskScoring.vehicleRiskScore(0, 1, 0, WINDOW_READINGS);
        double oneIncident = RiskScoring.vehicleRiskScore(0, 0, 1, WINDOW_READINGS);
        assertThat(oneBrake).isLessThan(oneViolation);
        assertThat(oneViolation).isLessThan(oneIncident);
    }

    private static double score(long hardBrakes, long violations, long incidents) {
        return RiskScoring.vehicleRiskScore(hardBrakes, violations, incidents, WINDOW_READINGS);
    }
}
