package com.tessera.risk.batch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests for the combiner {@code reduceByKey} folds with.
 *
 * <p>Spark applies a combiner in whatever order and grouping the partitioning
 * happens to produce, and it does so partly before the shuffle and partly after.
 * A combiner that is not associative therefore gives different answers on different
 * runs of the same data — a failure that is invisible in a single-partition test
 * and impossible to reproduce in production. That property is asserted here
 * directly rather than being assumed from the code.
 */
class WindowAggregateTest {

    @Test
    @DisplayName("merging is associative, so partition boundaries cannot change the result")
    void mergingIsAssociative() {
        WindowAggregate a = reading("NORMAL", 30.0, 50.0, 0.0);
        WindowAggregate b = reading("HARD_BRAKE", 44.0, 50.0, 0.6);
        WindowAggregate c = reading("INCIDENT", 61.0, 50.0, 0.9);

        WindowAggregate leftFirst = a.merge(b).merge(c);
        WindowAggregate rightFirst = a.merge(b.merge(c));

        assertThat(leftFirst.readings()).isEqualTo(rightFirst.readings());
        assertThat(leftFirst.speedSum()).isCloseTo(rightFirst.speedSum(), within(1e-9));
        assertThat(leftFirst.maxSpeed()).isEqualTo(rightFirst.maxSpeed());
        assertThat(leftFirst.hardBrakes()).isEqualTo(rightFirst.hardBrakes());
        assertThat(leftFirst.incidents()).isEqualTo(rightFirst.incidents());
        assertThat(leftFirst.maxSeverity()).isEqualTo(rightFirst.maxSeverity());
    }

    @Test
    @DisplayName("counts and extremes survive a merge")
    void countsAndExtremesCombine() {
        WindowAggregate merged = reading("HARD_BRAKE", 40.0, 50.0, 0.5)
                .merge(reading("NORMAL", 20.0, 50.0, 0.0))
                .merge(reading("INCIDENT", 70.0, 50.0, 0.95))
                .merge(reading("SPEED_VIOLATION", 62.0, 50.0, 0.3));

        assertThat(merged.readings()).isEqualTo(4);
        assertThat(merged.hardBrakes()).isEqualTo(1);
        assertThat(merged.speedViolations()).isEqualTo(1);
        assertThat(merged.incidents()).isEqualTo(1);
        assertThat(merged.maxSpeed()).isEqualTo(70.0);
        assertThat(merged.maxSeverity()).isEqualTo(0.95);
    }

    @Test
    @DisplayName("averages are derived at the end, not carried through the merge")
    void averagesAreDerived() {
        // A mean of means would be wrong unless every group were the same size, which
        // partitions never guarantee. Carrying the sum and dividing once is why.
        WindowAggregate merged = reading("NORMAL", 10.0, 50.0, 0.0)
                .merge(reading("NORMAL", 20.0, 50.0, 0.0))
                .merge(reading("NORMAL", 60.0, 50.0, 0.0));

        assertThat(merged.avgSpeedKph()).isCloseTo(30.0, within(1e-9));
        assertThat(merged.avgOverLimitRatio()).isCloseTo(0.6, within(1e-9));
    }

    @Test
    @DisplayName("readings with no known speed limit are excluded from the ratio")
    void unknownLimitsAreExcludedNotZeroed() {
        // Counting them as zero would drag the mean down and understate how fast the
        // vehicle was travelling relative to the law.
        WindowAggregate merged = reading("NORMAL", 50.0, 50.0, 0.0)
                .merge(reading("NORMAL", 40.0, 0.0, 0.0));

        assertThat(merged.overLimitReadings()).isEqualTo(1);
        assertThat(merged.avgOverLimitRatio()).isCloseTo(1.0, within(1e-9));
    }

    @Test
    @DisplayName("a parked vehicle is distinguishable from a quiet one")
    void movingRatioSeparatesParkedFromCalm() {
        WindowAggregate parked = reading("NORMAL", 0.0, 50.0, 0.0)
                .merge(reading("NORMAL", 0.0, 50.0, 0.0));
        WindowAggregate driving = reading("NORMAL", 35.0, 50.0, 0.0)
                .merge(reading("NORMAL", 40.0, 50.0, 0.0));

        assertThat(parked.movingRatio()).isZero();
        assertThat(driving.movingRatio()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("rates over an empty aggregate are zero, not NaN")
    void emptyAggregateDoesNotDivideByZero() {
        WindowAggregate empty = new WindowAggregate(
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, "DRV-001", "SAFE");

        assertThat(empty.avgSpeedKph()).isZero();
        assertThat(empty.hardBrakeRate()).isZero();
        assertThat(empty.movingRatio()).isZero();
        assertThat(empty.avgOverLimitRatio()).isZero();
    }

    private static WindowAggregate reading(String eventType, double speedKph,
                                           double limitKph, double severity) {
        return WindowAggregate.of(eventType, speedKph, limitKph, severity, "DRV-001", "SAFE");
    }
}
