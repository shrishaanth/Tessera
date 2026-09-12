package com.tessera.risk.batch;

import java.io.Serializable;

/**
 * Tunables for the batch feature job, all overridable by environment variable.
 *
 * @param archivePath   Parquet archive to read
 * @param featuresPath  where labelled training rows are written
 * @param windowSeconds tumbling feature window width
 * @param minReadings   readings a window needs to be usable
 */
public record BatchConfig(
        String archivePath,
        String featuresPath,
        int windowSeconds,
        long minReadings) implements Serializable {

    public static BatchConfig fromEnvironment() {
        return new BatchConfig(
                envString("TESSERA_ARCHIVE_PATH", "/data/archive/telemetry"),
                envString("TESSERA_FEATURES_PATH", "/data/features"),
                // Matches the streaming job's window width, so a feature vector
                // computed live has the same meaning as one the model was fitted to.
                // A model trained on five-minute rates and scored on one-minute rates
                // would be wrong in a way nothing would report.
                envInt("TESSERA_FEATURE_WINDOW_SECONDS", 300),
                // Nine tenths of a full window. Only the first and last window of the
                // archive can fall short, and their rates would rest on too little
                // evidence to be worth training on.
                envLong("TESSERA_FEATURE_MIN_READINGS", 270L));
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
}
