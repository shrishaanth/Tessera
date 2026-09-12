package com.tessera.fleet.reporting;

/**
 * Filter for a reporting query (FR-4.2). Any field may be {@code null}.
 *
 * @param fromEpochMs inclusive start of the period, or {@code null} for "from the first record"
 * @param toEpochMs   exclusive end of the period, or {@code null} for "now"
 * @param siteId      restrict to this customer site
 */
public record ReportFilter(Long fromEpochMs, Long toEpochMs, String siteId) {

    public long from(long fallback) {
        return fromEpochMs != null ? fromEpochMs : fallback;
    }

    public long to(long fallback) {
        return toEpochMs != null ? toEpochMs : fallback;
    }
}
