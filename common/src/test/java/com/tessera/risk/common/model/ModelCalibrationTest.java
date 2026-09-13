package com.tessera.risk.common.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests for the probability correction.
 *
 * <p>The anchor cases are the real training split (3,050 incident windows against
 * 30,766 quiet ones) and the per-tier numbers the dashboard was showing before the
 * fix. They are asserted against the incident rates measured directly from the
 * data, because the point of the correction is that the number an operator reads
 * means what it says.
 */
class ModelCalibrationTest {

    private static final ModelCalibration REAL_SPLIT =
            new ModelCalibration("logistic regression", 0.60, 3_050, 30_766);

    @Test
    @DisplayName("the risky tier's 73% becomes the one-in-five it really is")
    void correctsTheRiskyTier() {
        // Measured incident rate for RISKY drivers: 22.5%.
        assertThat(REAL_SPLIT.correct(0.73)).isCloseTo(0.21, within(0.01));
    }

    @Test
    @DisplayName("the safe tier's 32% becomes the few percent it really is")
    void correctsTheSafeTier() {
        // Measured incident rate for SAFE drivers: 4.4%.
        assertThat(REAL_SPLIT.correct(0.32)).isCloseTo(0.045, within(0.005));
    }

    @Test
    @DisplayName("ordering is preserved, so no ranking or decision changes")
    void correctionIsMonotonic() {
        double previous = -1;
        for (double p = 0.0; p <= 1.0; p += 0.05) {
            double corrected = REAL_SPLIT.correct(p);
            assertThat(corrected).isGreaterThanOrEqualTo(previous);
            previous = corrected;
        }
    }

    @Test
    @DisplayName("certainty stays certain")
    void endpointsAreFixed() {
        assertThat(REAL_SPLIT.correct(0.0)).isEqualTo(0.0);
        assertThat(REAL_SPLIT.correct(1.0)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a balanced training set needs no correction")
    void balancedPriorIsIdentity() {
        // With as many positives as negatives the weighting did nothing, so neither
        // should the correction — a check that the formula is the right one and not
        // merely one that happens to shrink numbers.
        ModelCalibration balanced = new ModelCalibration("m", 0.5, 1_000, 1_000);
        for (double p : new double[] {0.1, 0.37, 0.5, 0.82}) {
            assertThat(balanced.correct(p)).isCloseTo(p, within(1e-12));
        }
    }

    @Test
    @DisplayName("the decision threshold can be read as a chance")
    void thresholdTranslates() {
        // The model flags at raw 0.60, which is a real chance of about 13% —
        // roughly one and a half times the base rate of one in eleven.
        assertThat(REAL_SPLIT.correctedThreshold()).isCloseTo(0.129, within(0.002));
    }

    @Test
    @DisplayName("out-of-range input is clamped rather than producing nonsense")
    void clampsInput() {
        assertThat(REAL_SPLIT.correct(-0.2)).isEqualTo(0.0);
        assertThat(REAL_SPLIT.correct(1.4)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a split missing a class is refused")
    void requiresBothClasses() {
        assertThatThrownBy(() -> new ModelCalibration("m", 0.5, 0, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelCalibration("m", 0.5, 100, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("it survives a round trip through its file format")
    void jsonRoundTrip() {
        ModelCalibration back = ModelCalibration.fromJson(REAL_SPLIT.toJson());
        assertThat(back).isEqualTo(REAL_SPLIT);
        // Derived values are recomputed, not stored, so they cannot go stale.
        assertThat(REAL_SPLIT.toJson()).doesNotContain("priorOdds");
    }
}
