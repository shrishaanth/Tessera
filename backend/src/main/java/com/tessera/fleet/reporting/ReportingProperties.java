package com.tessera.fleet.reporting;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Reporting tunables, including the explicit answer to SRS Appendix B's open item:
 * the minimum data-collection period and sample size before FR-4's average-dwell
 * figures may be presented as reliable (FR-4.4).
 *
 * @param minCollectionDays days of history required before reports are "ready"
 * @param minSiteExits      recorded visits required before a site's average dwell is "ready"
 * @param syntheticHistory  set by the demo profile — history was back-filled and
 *        the UI must say so
 */
@ConfigurationProperties(prefix = "tessera.reporting")
public record ReportingProperties(
        int minCollectionDays,
        int minSiteExits,
        boolean syntheticHistory) {
}
