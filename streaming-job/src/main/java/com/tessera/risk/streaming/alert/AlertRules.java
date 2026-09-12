package com.tessera.risk.streaming.alert;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;

import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.expressions.WindowSpec;

import com.tessera.risk.common.model.RiskAlert;
import com.tessera.risk.common.model.RiskTier;
import com.tessera.risk.streaming.RiskWindows;
import com.tessera.risk.streaming.StreamingConfig;
import com.tessera.risk.streaming.ml.RiskModel;

import static org.apache.spark.sql.functions.col;
import static org.apache.spark.sql.functions.row_number;

/**
 * Turns a micro-batch of vehicle windows into alerts (FR-4.1).
 *
 * <h2>One window per vehicle — the one with the most evidence</h2>
 * Windows slide by a minute and are five minutes wide, so one vehicle is in five
 * overlapping windows at once and a naive threshold filter would raise five alerts
 * about the same driving.
 *
 * <p>The obvious way to collapse that is to keep the newest window, and it is
 * wrong. Every open window for a vehicle already contains the vehicle's latest
 * reading — that is what makes it open. They differ only in how far <em>back</em>
 * each one reaches. So the newest window is not more current than the others; it
 * is the one that has just opened, holding perhaps twenty seconds of driving,
 * while the oldest still-open window holds nearly five minutes ending at the same
 * instant.
 *
 * <p>That matters because the risk score is a rate. Over a twenty-second sample a
 * vehicle that brakes twice scores as though it had braked thirty times in five
 * minutes, and the score was calibrated against five-minute rates. Ranking by
 * reading count therefore picks the longest view that still ends now, which is
 * both the most current answer available and the one the calibration assumes.
 * {@code alertMinReadings} is a floor beneath that, for the case where a vehicle
 * has only just started reporting.
 *
 * <p>The incident rule is exempt from the floor. An incident is an observation,
 * not a rate, and it does not become less true for having happened in a window
 * that has only just opened.
 *
 * <h2>Two rules, not one</h2>
 * The score threshold catches a sustained pattern — repeated braking, persistent
 * speeding — which is the case the composite score is built for. But a score is a
 * rate, and a single incident inside an otherwise quiet window barely moves it. An
 * incident is exactly the event this system exists to surface, so it gets its own
 * unconditional rule rather than being left to a threshold that would swallow it.
 */
public final class AlertRules {

    private static final String RANK = "rank";

    private AlertRules() {
    }

    /**
     * Alerts for this batch, one window per vehicle, already thresholded.
     *
     * <p>Collected to the driver deliberately. This runs after the filter, so it is
     * at most one row per vehicle in the fleet — tens of rows, not millions — and
     * having them as ordinary Java objects is what lets the publisher apply a
     * cooldown across batches, which no stateless Spark sink could do.
     */
    public static List<RiskAlert> evaluate(Dataset<Row> vehicleBatch, StreamingConfig config, long now) {
        WindowSpec bestEvidenceFirst = org.apache.spark.sql.expressions.Window
                .partitionBy(col(RiskWindows.VEHICLE_ID))
                // Most readings first: the longest view that still ends at the
                // present moment. Window start only breaks ties, which happens when
                // a vehicle has been reporting for less than one full window and
                // several of its windows hold the same readings.
                .orderBy(col(RiskWindows.READING_COUNT).desc(),
                        col(RiskWindows.WINDOW_START).desc());

        // The model column exists only once a model has been trained, so the rule is
        // added conditionally rather than referencing a column that may not be there.
        Column thresholdsCrossed = col(RiskWindows.RISK_SCORE).geq(config.alertScoreThreshold())
                .or(col(RiskWindows.INCIDENT_COUNT).geq(config.alertIncidentFloor()));
        if (Arrays.asList(vehicleBatch.columns()).contains(RiskModel.POSITIVE_PROBABILITY)) {
            thresholdsCrossed = thresholdsCrossed.or(
                    col(RiskModel.POSITIVE_PROBABILITY).geq(config.alertProbabilityThreshold()));
        }

        List<Row> rows = vehicleBatch
                // Before ranking, not after: filtering afterwards would discard the
                // vehicle entirely whenever its top-ranked window happened to be
                // sparse, instead of falling back to the one behind it.
                .filter(col(RiskWindows.READING_COUNT).geq(config.alertMinReadings())
                        .or(col(RiskWindows.INCIDENT_COUNT).geq(config.alertIncidentFloor())))
                .withColumn(RANK, row_number().over(bestEvidenceFirst))
                .filter(col(RANK).equalTo(1))
                .filter(thresholdsCrossed)
                .collectAsList();

        List<RiskAlert> alerts = new ArrayList<>(rows.size());
        for (Row row : rows) {
            alerts.add(toAlert(row, config, now));
        }
        return alerts;
    }

    private static RiskAlert toAlert(Row row, StreamingConfig config, long now) {
        double riskScore = doubleAt(row, RiskWindows.RISK_SCORE);
        long hardBrakes = longAt(row, RiskWindows.HARD_BRAKE_COUNT);
        long violations = longAt(row, RiskWindows.SPEED_VIOLATION_COUNT);
        long incidents = longAt(row, RiskWindows.INCIDENT_COUNT);
        long readings = longAt(row, RiskWindows.READING_COUNT);

        return new RiskAlert(
                row.getAs(RiskWindows.VEHICLE_ID),
                row.getAs(RiskWindows.DRIVER_ID),
                tierOf(row.getAs(RiskWindows.RISK_TIER)),
                longAt(row, RiskWindows.WINDOW_START),
                longAt(row, RiskWindows.WINDOW_END),
                riskScore,
                hardBrakes,
                violations,
                incidents,
                readings,
                reason(config, riskScore, hardBrakes, violations, incidents, readings,
                        probabilityAt(row)),
                now);
    }

    /**
     * A sentence an operator can act on without opening the dashboard.
     *
     * <p>The incident case is stated first and on its own: when both rules fire,
     * the incident is the thing that matters, and burying it in a list of counts
     * would make the alert read like every other alert.
     */
    static String reason(StreamingConfig config, double riskScore,
                         long hardBrakes, long violations, long incidents, long readings,
                         OptionalDouble modelProbability) {
        if (incidents >= config.alertIncidentFloor()) {
            return String.format(Locale.ROOT,
                    "%d incident%s in the last window (risk score %.0f)",
                    incidents, incidents == 1 ? "" : "s", riskScore);
        }
        // When it was the model that fired, say so. Quoting a rule score of 20 on an
        // alert the rules did not raise would read as a contradiction, and an
        // operator would reasonably stop trusting the message.
        if (modelProbability.isPresent()
                && modelProbability.getAsDouble() >= config.alertProbabilityThreshold()) {
            return String.format(Locale.ROOT,
                    "Model predicts an incident with %.0f%% confidence "
                            + "(%d hard brake%s in %d readings)",
                    100.0 * modelProbability.getAsDouble(),
                    hardBrakes, hardBrakes == 1 ? "" : "s", readings);
        }
        // The reading count is part of the sentence, not an afterthought: the score
        // is a rate, so "12 hard brakes" means something very different over one
        // minute than over five, and an operator reading only the message would
        // otherwise have no way to tell which they are looking at.
        return String.format(Locale.ROOT,
                "Risk score %.0f from %d hard brake%s and %d speed violation%s in %d readings",
                riskScore, hardBrakes, hardBrakes == 1 ? "" : "s",
                violations, violations == 1 ? "" : "s", readings);
    }

    private static RiskTier tierOf(String name) {
        try {
            return name == null ? RiskTier.AVERAGE : RiskTier.valueOf(name);
        } catch (IllegalArgumentException e) {
            // An unknown tier is a producer-side change, not a reason to drop an
            // alert about a vehicle that may be about to crash.
            return RiskTier.AVERAGE;
        }
    }

    /** The model's confidence, or empty when no model contributed to this batch. */
    private static OptionalDouble probabilityAt(Row row) {
        if (!Arrays.asList(row.schema().fieldNames()).contains(RiskModel.POSITIVE_PROBABILITY)) {
            return OptionalDouble.empty();
        }
        int index = row.fieldIndex(RiskModel.POSITIVE_PROBABILITY);
        return row.isNullAt(index)
                ? OptionalDouble.empty()
                : OptionalDouble.of(row.getDouble(index));
    }

    private static double doubleAt(Row row, String column) {
        int index = row.fieldIndex(column);
        return row.isNullAt(index) ? 0.0 : ((Number) row.get(index)).doubleValue();
    }

    private static long longAt(Row row, String column) {
        int index = row.fieldIndex(column);
        return row.isNullAt(index) ? 0L : ((Number) row.get(index)).longValue();
    }
}
