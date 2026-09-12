package com.tessera.risk.common.spark;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;

import com.tessera.risk.common.model.RiskTier;

import static org.apache.spark.sql.functions.col;
import static org.apache.spark.sql.functions.lit;
import static org.apache.spark.sql.functions.when;

/**
 * Derives the model's features from windowed aggregates — the one place it happens.
 *
 * <p>Called by the batch job on Parquet aggregates and by the streaming job on live
 * micro-batch aggregates. Both get the identical arithmetic, which is what keeps a
 * model trained offline meaningful when scored online. See {@link FeatureSchema}
 * for why that matters more than it looks.
 */
public final class FeatureVector {

    private FeatureVector() {
    }

    /**
     * Add the derived feature columns to a frame of windowed aggregates.
     *
     * <p>The input must carry {@link FeatureSchema#AGGREGATE_COLUMNS}; anything else
     * it holds is left untouched, so identity columns and labels pass straight
     * through.
     *
     * @throws IllegalArgumentException if an aggregate column is missing, rather
     *         than letting the feature silently evaluate to null
     */
    public static Dataset<Row> withFeatures(Dataset<Row> aggregates) {
        requireAggregateColumns(aggregates);

        Column readings = col(FeatureSchema.READING_COUNT);
        // Guard the divisor, not the result. A window with no readings cannot come
        // out of a group-by, but dividing by zero would yield NaN, and NaN
        // propagates silently through a feature vector into a meaningless score.
        Column safeReadings = when(readings.leq(0), lit(1L)).otherwise(readings);

        return aggregates
                .withColumn(FeatureSchema.HARD_BRAKE_RATE,
                        col(FeatureSchema.HARD_BRAKE_COUNT).divide(safeReadings))
                .withColumn(FeatureSchema.SPEED_VIOLATION_RATE,
                        col(FeatureSchema.SPEED_VIOLATION_COUNT).divide(safeReadings))
                .withColumn(FeatureSchema.INCIDENT_RATE,
                        col(FeatureSchema.INCIDENT_COUNT).divide(safeReadings))
                .withColumn(FeatureSchema.HOUR_OF_DAY, hourOfDay())
                .withColumn(FeatureSchema.RISK_TIER_ORDINAL, riskTierOrdinal());
    }

    /**
     * Hour of day in UTC, from the window start.
     *
     * <p>Computed from the epoch directly rather than through a timestamp
     * conversion, so it cannot pick up a session time zone. A model trained on
     * UTC hours and scored on local hours would be quietly wrong for most of the
     * day.
     */
    private static Column hourOfDay() {
        return col(FeatureSchema.WINDOW_START)
                .divide(lit(3_600_000L)).cast("long")
                .mod(lit(24L)).cast("double");
    }

    /**
     * Risk tier as an ordinal.
     *
     * <p>Built as an explicit {@code when} chain rather than with a StringIndexer.
     * An indexer assigns codes by observed frequency, so the same tier can receive
     * a different number in the training set than in a micro-batch that happens to
     * contain a different mix of drivers — the classic way this feature silently
     * inverts between training and serving.
     */
    private static Column riskTierOrdinal() {
        Column tier = col(FeatureSchema.RISK_TIER);
        Column expression = when(tier.equalTo(lit(RiskTier.SAFE.name())),
                lit((double) RiskTier.SAFE.ordinal()));
        for (RiskTier value : RiskTier.values()) {
            if (value != RiskTier.SAFE) {
                expression = expression.when(tier.equalTo(lit(value.name())),
                        lit((double) value.ordinal()));
            }
        }
        // An unrecognised tier lands between the extremes rather than being treated
        // as the safest or the riskiest, either of which claims more than is known.
        return expression.otherwise(lit((double) RiskTier.AVERAGE.ordinal()));
    }

    private static void requireAggregateColumns(Dataset<Row> aggregates) {
        List<String> present = Arrays.asList(aggregates.columns());
        List<String> missing = new ArrayList<>();
        for (String required : FeatureSchema.AGGREGATE_COLUMNS) {
            if (!present.contains(required)) {
                missing.add(required);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "Cannot derive features: aggregate column(s) " + missing
                            + " are absent. Present: " + present);
        }
    }
}
