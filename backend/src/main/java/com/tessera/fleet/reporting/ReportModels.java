package com.tessera.fleet.reporting;

import java.util.List;

/** Response shapes for the reporting API (FR-4). */
public final class ReportModels {

    private ReportModels() { }

    /** Period-over-period change for a single metric (FR-4.3). */
    public record Trend(Double previousValue, Double deltaValue, String direction) {

        public static Trend of(Double current, Double previous) {
            if (current == null || previous == null) {
                return new Trend(previous, null, "flat");
            }
            double delta = current - previous;
            String dir = Math.abs(delta) < 1e-9 ? "flat" : (delta > 0 ? "up" : "down");
            return new Trend(previous, delta, dir);
        }
    }

    public record WeekPoint(long weekStartEpochMs, int completed, int onTime, Double onTimePct) { }

    /**
     * @param onTimePct  {@code null} when no completed jobs matched
     * @param byWeek     weekly breakdown ("On-time % by week")
     * @param trend      vs the immediately preceding period of equal length
     * @param provisional true until the data-sufficiency gate is met (FR-4.4)
     */
    public record OnTimeReport(
            Long fromEpochMs,
            Long toEpochMs,
            int completed,
            int onTime,
            Double onTimePct,
            List<WeekPoint> byWeek,
            Trend trend,
            boolean provisional) { }

    public record SiteDwell(
            String siteId,
            String siteName,
            int visits,
            Double avgDwellSeconds,
            boolean enoughData) { }

    public record DwellReport(
            Long fromEpochMs,
            Long toEpochMs,
            int totalVisits,
            Double overallAvgDwellSeconds,
            List<SiteDwell> bySite,
            Trend trend,
            boolean provisional) { }

    /**
     * FR-4.4 data-sufficiency gate.
     *
     * @param ready            all thresholds met
     * @param reasons          why it is not ready (empty when ready)
     * @param syntheticHistory history was back-filled for the demo
     */
    public record Readiness(
            boolean ready,
            long collectionDays,
            int minCollectionDays,
            long completedJobs,
            int minCompletedJobs,
            long siteExits,
            int minSiteExits,
            List<String> reasons,
            boolean syntheticHistory) { }

    public record FilterOptions(List<String> routes, List<String> drivers,
                                List<SiteOption> sites) {
        public record SiteOption(String id, String name) { }
    }
}
