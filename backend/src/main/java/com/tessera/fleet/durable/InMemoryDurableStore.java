package com.tessera.fleet.durable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import com.tessera.fleet.reporting.ReportingFacts.CompletedJobFact;
import com.tessera.fleet.reporting.ReportingFacts.DataWindow;
import com.tessera.fleet.reporting.ReportingFacts.SiteVisitFact;

/**
 * In-memory {@link DurableStore}. The default when the {@code durable} profile is
 * off, and the store used by the fast (no-Docker) integration tests. Position
 * history is bounded so a long-running dev session does not exhaust the heap;
 * geofence events, sites and jobs are kept in full.
 */
public class InMemoryDurableStore implements DurableStore {

    private static final int MAX_POSITIONS = 500_000;

    private final CopyOnWriteArrayList<PositionRecord> positions = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<GeofenceEventRecord> geofenceEvents = new CopyOnWriteArrayList<>();
    private final Map<String, SiteRecord> sites = new ConcurrentHashMap<>();
    private final Map<String, JobRecord> jobs = new ConcurrentHashMap<>();
    private final AtomicLong positionsWritten = new AtomicLong();

    @Override
    public void savePositions(List<PositionRecord> batch) {
        positions.addAll(batch);
        positionsWritten.addAndGet(batch.size());
        int overflow = positions.size() - MAX_POSITIONS;
        if (overflow > 0) {
            positions.subList(0, overflow).clear();
        }
    }

    @Override
    public void saveGeofenceEvents(List<GeofenceEventRecord> batch) {
        geofenceEvents.addAll(batch);
    }

    @Override
    public List<SiteRecord> loadSites() {
        return new ArrayList<>(sites.values());
    }

    @Override
    public void saveSite(SiteRecord site) {
        sites.put(site.siteId(), site);
    }

    @Override
    public void deleteSite(String siteId) {
        sites.remove(siteId);
    }

    @Override
    public void saveJob(JobRecord job) {
        jobs.put(job.jobId(), job);
    }

    @Override
    public List<JobRecord> loadJobs() {
        return new ArrayList<>(jobs.values());
    }

    @Override
    public List<GeofenceEventRecord> recentGeofenceEvents(String vehicleId, String siteId, int limit) {
        return geofenceEvents.stream()
                .filter(e -> vehicleId == null || vehicleId.equals(e.vehicleId()))
                .filter(e -> siteId == null || siteId.equals(e.siteId()))
                .sorted(Comparator.comparingLong(GeofenceEventRecord::epochMillis).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public Optional<GeofenceEventRecord> lastGeofenceEvent(String vehicleId, String siteId) {
        return recentGeofenceEvents(vehicleId, siteId, 1).stream().findFirst();
    }

    @Override
    public long positionCount() {
        return positionsWritten.get();
    }

    @Override
    public List<CompletedJobFact> completedJobs(long fromMs, long toMs) {
        List<CompletedJobFact> out = new ArrayList<>();
        for (JobRecord j : jobs.values()) {
            if (!"COMPLETED".equals(j.status()) || j.completedAtEpochMs() == null) {
                continue;
            }
            long c = j.completedAtEpochMs();
            if (c < fromMs || c >= toMs) {
                continue;
            }
            out.add(new CompletedJobFact(j.jobId(), j.route(), j.driverName(), j.siteId(),
                    j.expectedArrivalEpochMs() == null ? 0 : j.expectedArrivalEpochMs(),
                    j.actualArrivalEpochMs() == null ? 0 : j.actualArrivalEpochMs(), c));
        }
        return out;
    }

    @Override
    public List<SiteVisitFact> siteVisits(long fromMs, long toMs) {
        List<SiteVisitFact> out = new ArrayList<>();
        for (GeofenceEventRecord e : geofenceEvents) {
            if (e.type() != GeofenceEventRecord.Type.EXIT || e.dwellSeconds() == null) {
                continue;
            }
            if (e.epochMillis() < fromMs || e.epochMillis() >= toMs) {
                continue;
            }
            out.add(new SiteVisitFact(e.siteId(), e.dwellSeconds(), e.epochMillis()));
        }
        return out;
    }

    @Override
    public DataWindow reportingWindow() {
        long earliest = Long.MAX_VALUE;
        long latest = 0;
        for (PositionRecord p : positions) {
            earliest = Math.min(earliest, p.epochMillis());
            latest = Math.max(latest, p.epochMillis());
        }
        for (GeofenceEventRecord e : geofenceEvents) {
            earliest = Math.min(earliest, e.epochMillis());
            latest = Math.max(latest, e.epochMillis());
        }
        long completed = 0;
        long exits = 0;
        for (JobRecord j : jobs.values()) {
            if ("COMPLETED".equals(j.status())) {
                completed++;
                if (j.completedAtEpochMs() != null) {
                    earliest = Math.min(earliest, j.completedAtEpochMs());
                    latest = Math.max(latest, j.completedAtEpochMs());
                }
            }
        }
        for (GeofenceEventRecord e : geofenceEvents) {
            if (e.type() == GeofenceEventRecord.Type.EXIT) {
                exits++;
            }
        }
        return new DataWindow(earliest == Long.MAX_VALUE ? 0 : earliest, latest, completed, exits);
    }

    @Override
    public boolean healthy() {
        return true;
    }

    /** Test hook. */
    public void clear() {
        positions.clear();
        geofenceEvents.clear();
        sites.clear();
        jobs.clear();
        positionsWritten.set(0);
    }
}
