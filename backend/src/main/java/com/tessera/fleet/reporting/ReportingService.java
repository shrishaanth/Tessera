package com.tessera.fleet.reporting;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Service;

import com.tessera.fleet.durable.DurableStore;
import com.tessera.fleet.geofence.GeofenceService;
import com.tessera.fleet.reporting.ReportingFacts.CompletedJobFact;
import com.tessera.fleet.reporting.ReportingFacts.DataWindow;
import com.tessera.fleet.reporting.ReportingFacts.SiteVisitFact;
import com.tessera.fleet.reporting.ReportModels.DwellReport;
import com.tessera.fleet.reporting.ReportModels.FilterOptions;
import com.tessera.fleet.reporting.ReportModels.OnTimeReport;
import com.tessera.fleet.reporting.ReportModels.Readiness;
import com.tessera.fleet.reporting.ReportModels.SiteDwell;
import com.tessera.fleet.reporting.ReportModels.Trend;
import com.tessera.fleet.reporting.ReportModels.WeekPoint;

/**
 * Historical performance reporting (FR-4). Reads bounded fact lists from the
 * {@link DurableStore} and does the grouping, averaging and period-over-period
 * trend maths here (NFR-4 — no extra infrastructure until real load demands it).
 * Served request/response, not real-time (SRS §5.3).
 */
@Service
public class ReportingService {

    private final DurableStore durableStore;
    private final GeofenceService geofenceService;
    private final ReportingProperties properties;

    public ReportingService(DurableStore durableStore, GeofenceService geofenceService,
                            ReportingProperties properties) {
        this.durableStore = durableStore;
        this.geofenceService = geofenceService;
        this.properties = properties;
    }

    // ---------------------------------------------------------------- FR-4.1

    public OnTimeReport onTime(ReportFilter filter) {
        long now = System.currentTimeMillis();
        long to = filter.to(now);
        long from = filter.from(to - 30L * 86_400_000L); // default: trailing 30 days
        long span = Math.max(1, to - from);

        List<CompletedJobFact> current = filteredCompleted(from, to, filter);
        List<CompletedJobFact> previous = filteredCompleted(from - span, from, filter);

        Double currentPct = onTimePct(current);
        Double previousPct = onTimePct(previous);

        int onTime = (int) current.stream().filter(j -> j.onTime(properties.onTimeGraceMillis())).count();

        return new OnTimeReport(from, to, current.size(), onTime, currentPct,
                weekly(current), Trend.of(currentPct, previousPct), !readiness().ready());
    }

    private List<CompletedJobFact> filteredCompleted(long from, long to, ReportFilter f) {
        return durableStore.completedJobs(from, to).stream()
                .filter(j -> f.route() == null || f.route().equals(j.route()))
                .filter(j -> f.driverName() == null || f.driverName().equals(j.driverName()))
                .filter(j -> f.siteId() == null || f.siteId().equals(j.siteId()))
                .toList();
    }

    private Double onTimePct(List<CompletedJobFact> jobs) {
        if (jobs.isEmpty()) {
            return null;
        }
        long onTime = jobs.stream().filter(j -> j.onTime(properties.onTimeGraceMillis())).count();
        return round1(100.0 * onTime / jobs.size());
    }

    private List<WeekPoint> weekly(List<CompletedJobFact> jobs) {
        Map<Long, int[]> byWeek = new TreeMap<>(); // weekStartMs -> [completed, onTime]
        for (CompletedJobFact j : jobs) {
            long weekStart = weekStartMs(j.completedAtEpochMs());
            int[] acc = byWeek.computeIfAbsent(weekStart, k -> new int[2]);
            acc[0]++;
            if (j.onTime(properties.onTimeGraceMillis())) {
                acc[1]++;
            }
        }
        List<WeekPoint> out = new ArrayList<>();
        byWeek.forEach((weekStart, acc) -> out.add(new WeekPoint(weekStart, acc[0], acc[1],
                acc[0] == 0 ? null : round1(100.0 * acc[1] / acc[0]))));
        return out;
    }

    private static long weekStartMs(long epochMs) {
        return Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    // ---------------------------------------------------------------- FR-4.2

    public DwellReport dwell(ReportFilter filter) {
        long now = System.currentTimeMillis();
        long to = filter.to(now);
        long from = filter.from(to - 30L * 86_400_000L);
        long span = Math.max(1, to - from);

        List<SiteVisitFact> current = filteredVisits(from, to, filter);
        List<SiteVisitFact> previous = filteredVisits(from - span, from, filter);

        Map<String, String> names = new LinkedHashMap<>();
        geofenceService.sites().forEach(s -> names.put(s.id(), s.name()));

        Map<String, long[]> bySite = new LinkedHashMap<>(); // siteId -> [count, sumDwell]
        for (SiteVisitFact v : current) {
            long[] acc = bySite.computeIfAbsent(v.siteId(), k -> new long[2]);
            acc[0]++;
            acc[1] += v.dwellSeconds();
        }
        List<SiteDwell> siteRows = new ArrayList<>();
        bySite.forEach((siteId, acc) -> siteRows.add(new SiteDwell(
                siteId, names.getOrDefault(siteId, siteId),
                (int) acc[0], acc[0] == 0 ? null : round1((double) acc[1] / acc[0]),
                acc[0] >= properties.minSiteExits())));
        siteRows.sort(Comparator.comparing(SiteDwell::siteName));

        Double currentAvg = avgDwell(current);
        Double previousAvg = avgDwell(previous);

        return new DwellReport(from, to, current.size(), currentAvg, siteRows,
                Trend.of(currentAvg, previousAvg), !readiness().ready());
    }

    private List<SiteVisitFact> filteredVisits(long from, long to, ReportFilter f) {
        return durableStore.siteVisits(from, to).stream()
                .filter(v -> f.siteId() == null || f.siteId().equals(v.siteId()))
                .toList();
    }

    private static Double avgDwell(List<SiteVisitFact> visits) {
        if (visits.isEmpty()) {
            return null;
        }
        return round1(visits.stream().mapToInt(SiteVisitFact::dwellSeconds).average().orElse(0));
    }

    // ---------------------------------------------------------------- FR-4.4

    public Readiness readiness() {
        DataWindow w = durableStore.reportingWindow();
        long now = System.currentTimeMillis();
        long collectionDays = w.earliestEpochMs() == 0 ? 0
                : ChronoUnit.DAYS.between(Instant.ofEpochMilli(w.earliestEpochMs()), Instant.ofEpochMilli(now));

        List<String> reasons = new ArrayList<>();
        if (collectionDays < properties.minCollectionDays()) {
            reasons.add("Only " + collectionDays + " of " + properties.minCollectionDays()
                    + " days of history collected");
        }
        if (w.completedJobs() < properties.minCompletedJobs()) {
            reasons.add("Only " + w.completedJobs() + " of " + properties.minCompletedJobs()
                    + " completed jobs on record");
        }
        boolean ready = reasons.isEmpty();
        return new Readiness(ready, collectionDays, properties.minCollectionDays(),
                w.completedJobs(), properties.minCompletedJobs(),
                w.siteExits(), properties.minSiteExits(), reasons,
                properties.syntheticHistory());
    }

    // ---------------------------------------------------------------- filters

    public FilterOptions filterOptions() {
        long now = System.currentTimeMillis();
        List<CompletedJobFact> all = durableStore.completedJobs(0, now);
        List<String> routes = all.stream().map(CompletedJobFact::route)
                .filter(r -> r != null && !r.isBlank()).distinct().sorted().toList();
        List<String> drivers = all.stream().map(CompletedJobFact::driverName)
                .filter(d -> d != null && !d.isBlank()).distinct().sorted().toList();
        List<FilterOptions.SiteOption> sites = geofenceService.sites().stream()
                .map(s -> new FilterOptions.SiteOption(s.id(), s.name())).toList();
        return new FilterOptions(routes, drivers, sites);
    }

    private static Double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
