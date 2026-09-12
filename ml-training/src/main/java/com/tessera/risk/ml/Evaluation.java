package com.tessera.risk.ml;

import java.util.Locale;

/**
 * A confusion matrix and the metrics derived from it.
 *
 * <h2>Why accuracy is reported but not led with</h2>
 * Roughly nine in ten windows are negative, so a model that predicts "no incident"
 * unconditionally scores about 91% accuracy while catching nothing at all. Accuracy
 * on this data is close to a measure of the class balance, not of the model. It is
 * printed for completeness and because the naive baseline's accuracy is the number
 * a reader will instinctively compare against — which is exactly why the baseline
 * is printed beside it.
 *
 * <p>Precision and recall are the metrics that mean something, and they trade off:
 * an operator flooded with false alarms stops reading them, and a model that misses
 * incidents is not doing its job. Both are shown rather than collapsed into F1
 * alone, since which one matters more is an operational decision, not a modelling
 * one.
 *
 * @param areaUnderPR  precision-recall AUC, or NaN for a rule with no score to rank
 *                     by. The right summary metric under heavy imbalance: unlike
 *                     ROC AUC it is not flattered by the large negative class.
 */
public record Evaluation(
        String name,
        long truePositives,
        long falsePositives,
        long trueNegatives,
        long falseNegatives,
        double areaUnderPR,
        double areaUnderROC) {

    public long total() {
        return truePositives + falsePositives + trueNegatives + falseNegatives;
    }

    public long actualPositives() {
        return truePositives + falseNegatives;
    }

    public long predictedPositives() {
        return truePositives + falsePositives;
    }

    public double accuracy() {
        long n = total();
        return n == 0 ? 0.0 : (double) (truePositives + trueNegatives) / n;
    }

    /** Of the windows flagged, the fraction that really were followed by an incident. */
    public double precision() {
        long flagged = predictedPositives();
        return flagged == 0 ? 0.0 : (double) truePositives / flagged;
    }

    /** Of the incidents that happened, the fraction the model saw coming. */
    public double recall() {
        long actual = actualPositives();
        return actual == 0 ? 0.0 : (double) truePositives / actual;
    }

    public double f1() {
        double p = precision();
        double r = recall();
        return p + r == 0 ? 0.0 : 2 * p * r / (p + r);
    }

    /**
     * Recall divided by the rate at which the model flags windows.
     *
     * <p>How much better than guessing this is. A rule that flags everything reaches
     * perfect recall and a lift of 1.0; anything above 1.0 is finding real signal.
     * Reported because a high recall on its own says nothing — it can always be
     * bought by flagging more.
     */
    public double lift() {
        long n = total();
        if (n == 0 || predictedPositives() == 0) {
            return 0.0;
        }
        double flaggedRate = (double) predictedPositives() / n;
        double baseRate = (double) actualPositives() / n;
        return baseRate == 0 ? 0.0 : precision() / baseRate;
    }

    /** One aligned row for the comparison table. */
    public String toTableRow() {
        return String.format(Locale.ROOT,
                "  %-26s %8.3f %9.3f %7.3f %7.3f %7.2f %9s %9s",
                name, accuracy(), precision(), recall(), f1(), lift(),
                format(areaUnderPR), format(areaUnderROC));
    }

    public static String tableHeader() {
        return String.format(Locale.ROOT,
                "  %-26s %8s %9s %7s %7s %7s %9s %9s",
                "model", "accuracy", "precision", "recall", "F1", "lift", "PR-AUC", "ROC-AUC");
    }

    private static String format(double value) {
        return Double.isNaN(value) ? "-" : String.format(Locale.ROOT, "%.3f", value);
    }

    /** Confusion matrix, spelled out, because the four counts are what everything above rests on. */
    public String confusionMatrix() {
        return String.format(Locale.ROOT,
                "  %s: predicted incident %,d of %,d windows | "
                        + "caught %,d of %,d | missed %,d | false alarms %,d",
                name, predictedPositives(), total(),
                truePositives, actualPositives(), falseNegatives, falsePositives);
    }
}
