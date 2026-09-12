package com.tessera.risk.ml;

import java.io.Serializable;

/**
 * Tunables for model training, all overridable by environment variable.
 *
 * @param featuresPath  labelled training rows written by the batch job
 * @param modelPath     where the winning pipeline is persisted
 * @param trainFraction fraction of the <em>time range</em> used for training
 * @param treeCount     trees in the random forest
 * @param maxDepth      maximum tree depth
 * @param seed          fixed so a run is reproducible
 */
public record TrainingConfig(
        String featuresPath,
        String modelPath,
        double trainFraction,
        int treeCount,
        int maxDepth,
        long seed) implements Serializable {

    public static TrainingConfig fromEnvironment() {
        return new TrainingConfig(
                envString("TESSERA_FEATURES_PATH", "/data/features"),
                envString("TESSERA_MODEL_PATH", "/data/model"),
                // A fraction of time, not of rows. See TrainingMain for why the split
                // is chronological rather than random.
                envDouble("TESSERA_TRAIN_FRACTION", 0.7),
                envInt("TESSERA_TREE_COUNT", 120),
                // Shallow on purpose. There are ten features and a few tens of
                // thousands of rows, and the signal is a moderate shift in precursor
                // rates rather than a sharp rule. Deep trees would fit the training
                // split's noise and the held-out numbers would be worse, not better.
                envInt("TESSERA_MAX_DEPTH", 6),
                envLong("TESSERA_SEED", 20260912L));
    }

    private static String envString(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v.trim();
    }

    private static int envInt(String key, int fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : Integer.parseInt(v.trim());
    }

    private static long envLong(String key, long fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : Long.parseLong(v.trim());
    }

    private static double envDouble(String key, double fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : Double.parseDouble(v.trim());
    }
}
