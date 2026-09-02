package com.tessera.fleet.reporting;

/** Row shapes the {@code DurableStore} returns for the reporting layer to aggregate. */
public final class ReportingFacts {

    private ReportingFacts() { }

    /**
     * One completed job (FR-4.1). "Arrival" is a geofence ENTER at the
     * destination site; {@code expectedArrivalEpochMs} is assignment time plus
     * the road-network ETA computed then.
     */
    public record CompletedJobFact(
            String jobId,
            String route,
            String driverName,
            String siteId,
            long expectedArrivalEpochMs,
            long actualArrivalEpochMs,
            long completedAtEpochMs) {

        public boolean onTime(long graceMillis) {
            return actualArrivalEpochMs > 0 && expectedArrivalEpochMs > 0
                    && actualArrivalEpochMs <= expectedArrivalEpochMs + graceMillis;
        }
    }

    /** One recorded site visit — a geofence EXIT with its dwell time (FR-4.2). */
    public record SiteVisitFact(String siteId, int dwellSeconds, long exitEpochMs) { }

    /**
     * Extent and volume of durable history, for the data-sufficiency gate (FR-4.4).
     *
     * @param earliestEpochMs oldest durable record, or 0 if the store is empty
     * @param latestEpochMs   newest durable record, or 0
     * @param completedJobs   total completed jobs on record
     * @param siteExits       total geofence EXIT events on record
     */
    public record DataWindow(long earliestEpochMs, long latestEpochMs,
                             long completedJobs, long siteExits) { }
}
