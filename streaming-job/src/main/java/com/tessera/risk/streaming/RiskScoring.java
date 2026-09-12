package com.tessera.risk.streaming;

import org.apache.spark.sql.Column;

import static org.apache.spark.sql.functions.least;
import static org.apache.spark.sql.functions.lit;

/**
 * The composite risk score, and the segment risk index.
 *
 * <p>Both are <em>rate</em>-based rather than count-based. A count would make the
 * score depend on how much data happened to land in the window: a vehicle parked
 * for four of the five minutes would score low purely because it contributed few
 * readings, and a window straddling a producer restart would score differently
 * from an identical window that did not. Dividing by the reading count removes
 * that, so two windows are comparable regardless of how many samples each holds.
 *
 * <h2>Calibration</h2>
 * The weights are set against the simulator's measured behaviour rather than
 * picked to look tidy. Over an hour of generated telemetry the per-tier rates are:
 *
 * <pre>
 *   tier      precursor rate   incident rate
 *   SAFE          2.78%           0.200%
 *   AVERAGE       6.29%           0.356%
 *   RISKY        11.95%           0.729%
 * </pre>
 *
 * Applying the weights below to those rates yields roughly 19 / 39 / 77 out of
 * 100. That spread is the point: the score has to separate the tiers clearly
 * without the worst drivers pinning at the ceiling, or the dashboard shows every
 * risky vehicle as identically red and the alert threshold stops discriminating.
 *
 * <p>A score is deliberately <em>not</em> a probability and is not the model's
 * output. It is a transparent, explainable rule that works from the first window
 * onward, including before any model exists. The learned prediction is a separate
 * column in the same HBase row, so the two can be compared rather than confused.
 */
public final class RiskScoring {

    /**
     * Weight per unit of hard-brake rate. Hard braking is the most common
     * precursor, so its weight is the lowest of the three: a single hard brake
     * should register, not dominate.
     */
    public static final double W_HARD_BRAKE = 4.0;

    /**
     * Weight per unit of speed-violation rate. Higher than hard braking because a
     * violation is a <em>sustained</em> choice held over several seconds, not a
     * single reaction to circumstances.
     */
    public static final double W_SPEED_VIOLATION = 15.0;

    /**
     * Weight per unit of incident rate. An order of magnitude above the
     * precursors: an incident is the outcome the whole system exists to avoid.
     */
    public static final double W_INCIDENT = 40.0;

    /** Scores are reported on a 0-100 scale for display. */
    public static final double SCALE = 100.0;

    private RiskScoring() {
    }

    /**
     * Vehicle risk score in {@code [0,100]}.
     *
     * <p>The plain-Java twin of {@link #vehicleRiskScoreColumn()}. Keeping both
     * lets a test assert that the Spark expression and the stated formula agree,
     * which is the only way to be sure the published numbers match the documented
     * definition.
     *
     * @param readings total readings in the window; 0 yields a score of 0
     */
    public static double vehicleRiskScore(long hardBrakes, long violations, long incidents, long readings) {
        if (readings <= 0) {
            return 0.0;
        }
        double weighted = W_HARD_BRAKE * ((double) hardBrakes / readings)
                + W_SPEED_VIOLATION * ((double) violations / readings)
                + W_INCIDENT * ((double) incidents / readings);
        return SCALE * Math.min(1.0, weighted);
    }

    /**
     * Segment risk index in {@code [0,100]}: the same weighting applied to every
     * vehicle that crossed the segment.
     *
     * <p>It answers a different question from the vehicle score — "is this stretch
     * of road provoking unsafe behaviour?" rather than "is this driver behaving
     * unsafely?" — which is why a junction repeatedly producing hard braking shows
     * up here even when no individual driver looks bad.
     */
    public static double segmentRiskIndex(long hardBrakes, long violations, long incidents, long readings) {
        return vehicleRiskScore(hardBrakes, violations, incidents, readings);
    }

    /** Spark expression form of {@link #vehicleRiskScore}, over the aggregate columns. */
    public static Column vehicleRiskScoreColumn() {
        return riskColumn(RiskWindows.HARD_BRAKE_COUNT, RiskWindows.SPEED_VIOLATION_COUNT,
                RiskWindows.INCIDENT_COUNT, RiskWindows.READING_COUNT);
    }

    /** Spark expression form of {@link #segmentRiskIndex}, over the aggregate columns. */
    public static Column segmentRiskIndexColumn() {
        return vehicleRiskScoreColumn();
    }

    private static Column riskColumn(String hardBrakes, String violations, String incidents, String readings) {
        Column total = new Column(readings);
        // Guard the divisor rather than the result: a window with no readings
        // cannot occur in a group-by output, but a defensive divide-by-zero here
        // would produce NaN and poison every downstream comparison silently.
        Column safeTotal = org.apache.spark.sql.functions
                .when(total.leq(0), lit(1L)).otherwise(total);
        Column weighted = new Column(hardBrakes).divide(safeTotal).multiply(W_HARD_BRAKE)
                .plus(new Column(violations).divide(safeTotal).multiply(W_SPEED_VIOLATION))
                .plus(new Column(incidents).divide(safeTotal).multiply(W_INCIDENT));
        return least(lit(1.0), weighted).multiply(SCALE);
    }
}
