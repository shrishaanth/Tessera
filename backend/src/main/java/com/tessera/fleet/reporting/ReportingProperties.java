package com.tessera.fleet.reporting;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Reporting tunables, including the explicit answer to SRS Appendix B's open item:
 * the minimum data-collection period and sample sizes before FR-4's figures may be
 * presented as reliable (FR-4.4).
 *
 * @param minCollectionDays  days of history required before reports are "ready"
 * @param minCompletedJobs   completed jobs required before on-time % is "ready"
 * @param minSiteExits       recorded visits required before a site's average dwell is "ready"
 * @param onTimeGraceSeconds a job counts as on time if it arrives no later than
 *        its expected arrival plus this grace window
 * @param syntheticHistory   set by the demo profile — history was back-filled and
 *        the UI must say so
 */
@ConfigurationProperties(prefix = "tessera.reporting")
public record ReportingProperties(
        int minCollectionDays,
        int minCompletedJobs,
        int minSiteExits,
        int onTimeGraceSeconds,
        boolean syntheticHistory) {

    public long onTimeGraceMillis() {
        return onTimeGraceSeconds * 1000L;
    }
}
