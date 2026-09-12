package com.tessera.risk.archive;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * Tunables for offline archive generation, all overridable by environment variable.
 *
 * @param outputPath   directory the Parquet archive is written to
 * @param days         simulated days to generate
 * @param startDate    first simulated date, ISO-8601; fixed so runs are reproducible
 * @param parallelism  fleet slices simulated concurrently; also files per date
 * @param writeMode    {@code overwrite} to replace an existing archive, {@code append} to add
 */
public record ArchiveConfig(
        String outputPath,
        int days,
        LocalDate startDate,
        int parallelism,
        String writeMode) implements Serializable {

    public static ArchiveConfig fromEnvironment() {
        return new ArchiveConfig(
                envString("TESSERA_ARCHIVE_PATH", "/data/archive/telemetry"),
                // Seven days is about 14.5 million readings, which yields roughly
                // 48,000 labelled training rows once bucketed into five-minute
                // windows. Enough for a classifier to have something to fit, and
                // small enough to regenerate while waiting.
                envInt("TESSERA_ARCHIVE_DAYS", 7),
                LocalDate.parse(envString("TESSERA_ARCHIVE_START_DATE", "2026-01-05")),
                // How many fleet slices simulate at once. Vehicles are independent,
                // so this is free parallelism, and it is also the number of Parquet
                // files written per date — four files of a couple of hundred thousand
                // rows each, which is large enough for the columnar layout to pay for
                // its footers without producing a file per chunk.
                envInt("TESSERA_ARCHIVE_PARALLELISM", 4),
                envString("TESSERA_ARCHIVE_WRITE_MODE", "overwrite"));
    }

    /** Total simulated seconds across every day. */
    public long totalTicks() {
        return (long) days * 86_400L;
    }

    private static String envString(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v.trim();
    }

    private static int envInt(String key, int fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : Integer.parseInt(v.trim());
    }
}
