package com.tessera.fleet.reporting;

/** Row shapes the {@code DurableStore} returns for the reporting layer to aggregate. */
public final class ReportingFacts {

    private ReportingFacts() { }

    /** One recorded site visit — a geofence EXIT with its dwell time (FR-4.2). */
    public record SiteVisitFact(String siteId, int dwellSeconds, long exitEpochMs) { }

    /**
     * Extent and volume of durable history, for the data-sufficiency gate (FR-4.4).
     *
     * @param earliestEpochMs oldest durable record, or 0 if the store is empty
     * @param latestEpochMs   newest durable record, or 0
     * @param siteExits       total geofence EXIT events on record
     */
    public record DataWindow(long earliestEpochMs, long latestEpochMs, long siteExits) { }
}
