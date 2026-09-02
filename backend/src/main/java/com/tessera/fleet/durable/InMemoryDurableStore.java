package com.tessera.fleet.durable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

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
