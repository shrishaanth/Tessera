package com.tessera.risk.common.spark;

import java.util.List;

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tessera.risk.common.model.RiskTier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests for the one place features are derived.
 *
 * <p>This code is applied to the Parquet archive when the model is fitted and to
 * live micro-batches when it is scored. Everything asserted here is a property both
 * sides depend on, and the failures it guards are all silent: a null feature, a NaN
 * propagating through a vector, or a tier mapped to a different number at serving
 * time than at training time. None of them throws; all of them make the model's
 * output meaningless while it keeps reporting confident probabilities.
 */
class FeatureVectorTest {

    /** 2026-01-01T07:00:00Z — deliberately not midnight, so the hour is a real test. */
    private static final long SEVEN_AM_UTC = 1_767_250_800_000L;

    private static SparkSession spark;

    @BeforeAll
    static void startSpark() {
        spark = SparkSession.builder()
                .appName("tessera-risk-common-tests")
                .master("local[2]")
                .config("spark.sql.session.timeZone", "UTC")
                .config("spark.sql.shuffle.partitions", "2")
                .config("spark.ui.enabled", "false")
                .getOrCreate();
        spark.sparkContext().setLogLevel("WARN");
    }

    @Test
    @DisplayName("a missing aggregate column fails loudly instead of yielding nulls")
    void missingColumnsAreRejected() {
        // The whole point. Spark would happily let withColumn reference an absent
        // column only at analysis time, and a subtler version of this mistake — a
        // renamed column that still resolves — produces nulls the model cannot
        // distinguish from real zeroes.
        StructType incomplete = new StructType()
                .add(FeatureSchema.READING_COUNT, DataTypes.LongType)
                .add(FeatureSchema.HARD_BRAKE_COUNT, DataTypes.LongType);
        Dataset<Row> frame = spark.createDataFrame(
                List.of(RowFactory.create(300L, 6L)), incomplete);

        assertThatThrownBy(() -> FeatureVector.withFeatures(frame))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(FeatureSchema.MOVING_RATIO)
                .hasMessageContaining("absent");
    }

    @Test
    @DisplayName("rates divide the counts by the window's readings")
    void ratesAreComputedOverTheWindow() {
        Row row = derive(300L, 6L, 3L, 1L, RiskTier.SAFE, SEVEN_AM_UTC);

        assertThat(row.<Double>getAs(FeatureSchema.HARD_BRAKE_RATE))
                .isCloseTo(6.0 / 300, within(1e-12));
        assertThat(row.<Double>getAs(FeatureSchema.SPEED_VIOLATION_RATE))
                .isCloseTo(3.0 / 300, within(1e-12));
        assertThat(row.<Double>getAs(FeatureSchema.INCIDENT_RATE))
                .isCloseTo(1.0 / 300, within(1e-12));
    }

    @Test
    @DisplayName("a window with no readings yields zero, not NaN")
    void zeroReadingsDoNotProduceNaN() {
        // NaN propagates silently through a feature vector and out the other side as
        // a prediction, so the divisor is guarded rather than the result.
        Row row = derive(0L, 0L, 0L, 0L, RiskTier.SAFE, SEVEN_AM_UTC);

        assertThat(row.<Double>getAs(FeatureSchema.HARD_BRAKE_RATE)).isZero();
        assertThat(row.<Double>getAs(FeatureSchema.HARD_BRAKE_RATE)).isNotNaN();
        assertThat(row.<Double>getAs(FeatureSchema.INCIDENT_RATE)).isNotNaN();
    }

    @Test
    @DisplayName("hour of day comes from the epoch, not from a session time zone")
    void hourOfDayIsUtc() {
        // A model trained on UTC hours and scored on local hours would be wrong for
        // most of the day, and the feature would look perfectly reasonable either way.
        assertThat(derive(300L, 0L, 0L, 0L, RiskTier.SAFE, SEVEN_AM_UTC)
                .<Double>getAs(FeatureSchema.HOUR_OF_DAY)).isEqualTo(7.0);
        assertThat(derive(300L, 0L, 0L, 0L, RiskTier.SAFE, SEVEN_AM_UTC + 12 * 3_600_000L)
                .<Double>getAs(FeatureSchema.HOUR_OF_DAY)).isEqualTo(19.0);
    }

    @Test
    @DisplayName("each tier maps to its own ordinal, fixed by the enum")
    void tiersMapToStableOrdinals() {
        // Fixed by the enum rather than assigned by a StringIndexer, which would code
        // tiers by observed frequency and could number them differently in a
        // micro-batch than in the training set.
        for (RiskTier tier : RiskTier.values()) {
            assertThat(derive(300L, 0L, 0L, 0L, tier, SEVEN_AM_UTC)
                    .<Double>getAs(FeatureSchema.RISK_TIER_ORDINAL))
                    .as("ordinal for %s", tier)
                    .isEqualTo((double) tier.ordinal());
        }
    }

    @Test
    @DisplayName("an unrecognised tier lands in the middle, not at an extreme")
    void unknownTierIsNotTreatedAsSafestOrRiskiest() {
        Row row = deriveWithTier(300L, "PLATINUM", SEVEN_AM_UTC);
        assertThat(row.<Double>getAs(FeatureSchema.RISK_TIER_ORDINAL))
                .isEqualTo((double) RiskTier.AVERAGE.ordinal());
    }

    @Test
    @DisplayName("columns the derivation does not own pass through untouched")
    void unrelatedColumnsSurvive() {
        // Identity columns and the label ride alongside the features; the batch job
        // depends on that to write a training row in one pass.
        Row row = derive(300L, 6L, 0L, 0L, RiskTier.SAFE, SEVEN_AM_UTC);
        assertThat(row.<String>getAs(FeatureSchema.VEHICLE_ID)).isEqualTo("VEH-001");
        assertThat(row.<Double>getAs(FeatureSchema.LABEL)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("every feature the contract names is present after derivation")
    void everyFeatureColumnIsProduced() {
        Dataset<Row> derived = FeatureVector.withFeatures(aggregates(
                300L, 6L, 0L, 0L, RiskTier.SAFE.name(), SEVEN_AM_UTC));
        assertThat(derived.columns())
                .containsAll(java.util.Arrays.asList(FeatureSchema.FEATURE_COLUMNS));
    }

    private static Row derive(long readings, long hardBrakes, long violations, long incidents,
                              RiskTier tier, long windowStart) {
        return FeatureVector.withFeatures(aggregates(
                readings, hardBrakes, violations, incidents, tier.name(), windowStart)).first();
    }

    private static Row deriveWithTier(long readings, String tier, long windowStart) {
        return FeatureVector.withFeatures(
                aggregates(readings, 0L, 0L, 0L, tier, windowStart)).first();
    }

    /** One row carrying exactly {@link FeatureSchema#AGGREGATE_ROW}. */
    private static Dataset<Row> aggregates(long readings, long hardBrakes, long violations,
                                           long incidents, String tier, long windowStart) {
        return spark.createDataFrame(
                List.of(RowFactory.create(
                        "VEH-001", "DRV-001", tier, windowStart, readings,
                        hardBrakes, violations, incidents,
                        35.0, 52.0, 0.7, 0.8, 0.95, 1.0)),
                FeatureSchema.AGGREGATE_ROW);
    }
}
