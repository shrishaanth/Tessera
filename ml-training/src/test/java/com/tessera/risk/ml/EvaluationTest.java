package com.tessera.risk.ml;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests for the reported metrics.
 *
 * <p>These are the numbers the project's conclusions rest on, and a metric computed
 * wrongly does not look wrong — it looks like a result. The cases below are chosen
 * to be ones where precision and recall pull apart, so that swapping them, or
 * dividing by the wrong total, changes the answer.
 */
class EvaluationTest {

    @Test
    @DisplayName("precision and recall divide by different totals")
    void precisionAndRecallAreNotInterchangeable() {
        // Flagged 100 windows, 25 of them right. 50 incidents actually happened.
        // Precision is 25/100, recall is 25/50 — deliberately different numbers, so
        // a swapped denominator cannot pass.
        Evaluation e = new Evaluation("test", 25, 75, 900, 25, Double.NaN, Double.NaN);

        assertThat(e.predictedPositives()).isEqualTo(100);
        assertThat(e.actualPositives()).isEqualTo(50);
        assertThat(e.precision()).isCloseTo(0.25, within(1e-9));
        assertThat(e.recall()).isCloseTo(0.50, within(1e-9));
    }

    @Test
    @DisplayName("a model that flags nothing scores zero, not one")
    void flaggingNothingIsNotPerfectPrecision() {
        // The majority-class baseline. Precision is undefined when nothing is
        // flagged, and reporting it as 1.0 — vacuously true, nothing was wrong —
        // would make the baseline look like the best model in the table.
        Evaluation e = new Evaluation("always negative", 0, 0, 900, 100,
                Double.NaN, Double.NaN);

        assertThat(e.precision()).isZero();
        assertThat(e.recall()).isZero();
        assertThat(e.f1()).isZero();
        // And yet its accuracy is 90%, which is the whole reason accuracy is not
        // the metric this project reports its conclusions on.
        assertThat(e.accuracy()).isCloseTo(0.90, within(1e-9));
    }

    @Test
    @DisplayName("flagging everything reaches perfect recall and no lift")
    void flaggingEverythingHasNoLift() {
        // Recall can always be bought by flagging more, which is why recall is never
        // quoted on its own. Lift is what says whether the flagging was selective:
        // a rule that flags every window is right exactly as often as the base rate.
        Evaluation e = new Evaluation("always positive", 100, 900, 0, 0,
                Double.NaN, Double.NaN);

        assertThat(e.recall()).isEqualTo(1.0);
        assertThat(e.lift()).isCloseTo(1.0, within(1e-9));
    }

    @Test
    @DisplayName("lift measures precision against the base rate")
    void liftComparesAgainstTheBaseRate() {
        // 1000 windows, 100 positive — a base rate of 10%. Flagging 200 and getting
        // 50 right is 25% precision, which is 2.5 times better than guessing.
        Evaluation e = new Evaluation("model", 50, 150, 750, 50, Double.NaN, Double.NaN);

        assertThat(e.total()).isEqualTo(1000);
        assertThat(e.precision()).isCloseTo(0.25, within(1e-9));
        assertThat(e.lift()).isCloseTo(2.5, within(1e-9));
    }

    @Test
    @DisplayName("F1 sits between precision and recall")
    void f1IsTheHarmonicMean() {
        Evaluation e = new Evaluation("model", 25, 75, 900, 25, Double.NaN, Double.NaN);
        // Harmonic, not arithmetic: 2*0.25*0.5/0.75, which is below the arithmetic
        // mean of 0.375 because it punishes the weaker of the two.
        assertThat(e.f1()).isCloseTo(0.3333333333, within(1e-9));
        assertThat(e.f1()).isLessThan((e.precision() + e.recall()) / 2);
    }

    @Test
    @DisplayName("an empty evaluation does not divide by zero")
    void emptyEvaluationIsSafe() {
        Evaluation e = new Evaluation("empty", 0, 0, 0, 0, Double.NaN, Double.NaN);
        assertThat(e.accuracy()).isZero();
        assertThat(e.precision()).isZero();
        assertThat(e.recall()).isZero();
        assertThat(e.f1()).isZero();
        assertThat(e.lift()).isZero();
    }

    @Test
    @DisplayName("a missing AUC prints as a dash, not as zero")
    void missingAucIsNotReportedAsZero() {
        // Baselines are single rules with nothing to rank by, so they have no AUC.
        // Printing 0.000 would read as "ranked terribly" rather than "not applicable"
        // and would make the model's AUC look better than it is by comparison.
        Evaluation e = new Evaluation("baseline", 10, 10, 10, 10, Double.NaN, Double.NaN);
        assertThat(e.toTableRow()).contains(" -");
        assertThat(e.toTableRow()).doesNotContain("0.000 ");
    }
}
