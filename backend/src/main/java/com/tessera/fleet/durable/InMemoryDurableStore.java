package com.tessera.fleet.durable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import com.tessera.fleet.reporting.ReportingFacts.DataWindow;
import com.tessera.fleet.reporting.ReportingFacts.SiteVisitFact;

/**
 * In-memory {@link DurableStore}. The default when the {@code durable} profile is
 * off, and the store used by the fast (no-Docker) integration tests. Position
 * history is bounded so a long-running dev session does not exhaust the heap;
 * geofence events and sites are kept in full.
 */
public class InMemoryDurableStore implements DurableStore {

    private static final int MAX_POSITIONS = 500_000;

    private final CopyOnWriteArrayList<PositionRecord> positions = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<GeofenceEventRecord> geofenceEvents = new CopyOnWriteArrayList<>();
    private final Map<String, SiteRecord> sites = new ConcurrentHashMap<>();
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
    public List<SiteRecord> searchSites(String query, int limit) {
        return sites.values().stream()
                .map(s -> Map.entry(s, Math.max(
                        com.tessera.fleet.search.TextSimilarity.score(query, s.name()),
                        0.6 * com.tessera.fleet.search.TextSimilarity.score(
                                query, s.address() == null ? "" : s.address()))))
                .filter(e -> e.getValue() >= 0.15)
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
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
    public List<PositionRecord> trajectory(String vehicleId, long fromMs, long toMs) {
        return positions.stream()
                .filter(p -> vehicleId.equals(p.vehicleId()))
                .filter(p -> p.epochMillis() >= fromMs && p.epochMillis() < toMs)
                .sorted(Comparator.comparingLong(PositionRecord::epochMillis))
                .toList();
    }

    @Override
    public DataWindow reportingWindow() {
        long earliest = Long.MAX_VALUE;
        long latest = 0;
        for (PositionRecord p : positions) {
            earliest = Math.min(earliest, p.epochMillis());
            latest = Math.max(latest, p.epochMillis());
        }
        long exits = 0;
        for (GeofenceEventRecord e : geofenceEvents) {
            earliest = Math.min(earliest, e.epochMillis());
            latest = Math.max(latest, e.epochMillis());
            if (e.type() == GeofenceEventRecord.Type.EXIT) {
                exits++;
            }
        }
        return new DataWindow(earliest == Long.MAX_VALUE ? 0 : earliest, latest, exits);
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
        positionsWritten.set(0);
    }
}
