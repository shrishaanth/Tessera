package com.geotracker.geofence;

import com.geotracker.index.CowQuadtree;
import com.geotracker.index.HamtIndex;
import com.geotracker.index.IndexerThread;
import com.geotracker.index.SpatialSnapshot;
import com.geotracker.model.BoundingBox;
import com.geotracker.model.Position;
import com.geotracker.model.ZoneEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class GeofenceEngine {
    public record Zone(String id, List<Position> polygon, BoundingBox bbox) {}
    public record UserZone(
            String zoneId,
            String name,
            List<Position> polygon,
            BoundingBox bbox,
            Set<Long> monitoredVehicleIds,
            boolean alertOnEnter,
            boolean alertOnExit,
            long createdAt
    ) {}

    private final IndexerThread[] indexers;
    private final CowQuadtree[] quadtrees;
    private final HamtIndex[] hamts;
    private final Map<String, Set<Long>> previousContained = new ConcurrentHashMap<>();
    private final List<Zone> zones;
    private final ConcurrentHashMap<String, UserZone> userZones = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastEventTime = new ConcurrentHashMap<>();
    private static final long EVENT_COOLDOWN_MS = 3000;

    public GeofenceEngine(IndexerThread[] indexers, List<Zone> zones) {
        this.indexers = indexers;
        this.quadtrees = new CowQuadtree[0];
        this.hamts = new HamtIndex[0];
        this.zones = zones;
        for (Zone zone : zones) {
            previousContained.put(zone.id(), new HashSet<>());
        }
    }

    public GeofenceEngine(CowQuadtree[] quadtrees, HamtIndex[] hamts, List<Zone> zones) {
        this.indexers = null;
        this.quadtrees = quadtrees;
        this.hamts = hamts;
        this.zones = zones;
        for (Zone zone : zones) {
            previousContained.put(zone.id(), new HashSet<>());
        }
    }

    public String createZone(String name, List<Position> polygon, Set<Long> vehicleIds, boolean onEnter, boolean onExit) {
        String zoneId = "zone_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8);
        BoundingBox bbox = computeBbox(polygon);
        UserZone zone = new UserZone(zoneId, name, polygon, bbox, vehicleIds, onEnter, onExit, System.currentTimeMillis());
        userZones.put(zoneId, zone);
        previousContained.put(zoneId, new HashSet<>());
        return zoneId;
    }

    public void deleteZone(String zoneId) {
        userZones.remove(zoneId);
        previousContained.remove(zoneId);
    }

    public void updateZone(String zoneId, String name, List<Position> polygon, Set<Long> vehicleIds) {
        UserZone existing = userZones.get(zoneId);
        if (existing == null) return;
        BoundingBox bbox = computeBbox(polygon);
        UserZone updated = new UserZone(zoneId, name, polygon, bbox, vehicleIds, existing.alertOnEnter(), existing.alertOnExit(), existing.createdAt());
        userZones.put(zoneId, updated);
    }

    public List<UserZone> getAllUserZones() {
        return new ArrayList<>(userZones.values());
    }

    public UserZone getUserZone(String zoneId) {
        return userZones.get(zoneId);
    }

    public List<ZoneEvent> check() {
        List<ZoneEvent> events = new ArrayList<>();
        for (Zone zone : zones) {
            events.addAll(checkZone(zone.id(), zone.polygon(), zone.bbox(), Set.of(), true, true));
        }
        for (UserZone zone : userZones.values()) {
            events.addAll(checkZone(zone.zoneId(), zone.polygon(), zone.bbox(), zone.monitoredVehicleIds(), zone.alertOnEnter(), zone.alertOnExit()));
        }
        return events;
    }

    private List<ZoneEvent> checkZone(String zoneId, List<Position> polygon, BoundingBox bbox, Set<Long> monitoredIds, boolean onEnter, boolean onExit) {
        List<ZoneEvent> events = new ArrayList<>();
        Set<Long> candidates = new HashSet<>();
        if (indexers != null) {
            for (IndexerThread indexer : indexers) {
                SpatialSnapshot snapshot = indexer.getPublishedSnapshot();
                if (snapshot != null) {
                    candidates.addAll(snapshot.quadtree().rangeQuery(bbox));
                }
            }
        } else {
            for (CowQuadtree qt : quadtrees) {
                candidates.addAll(qt.rangeQuery(bbox));
            }
        }
        Set<Long> currentContained = new HashSet<>();
        long now = System.currentTimeMillis();
        for (long vehicleId : candidates) {
            if (!monitoredIds.isEmpty() && !monitoredIds.contains(vehicleId)) continue;
            Position pos = null;
            if (indexers != null) {
                for (IndexerThread indexer : indexers) {
                    SpatialSnapshot snapshot = indexer.getPublishedSnapshot();
                    if (snapshot != null) {
                        pos = snapshot.hamt().get(vehicleId);
                        if (pos != null) break;
                    }
                }
            } else {
                for (HamtIndex h : hamts) {
                    pos = h.get(vehicleId);
                    if (pos != null) break;
                }
            }
            if (pos == null) continue;
            if (RayCaster.contains(pos, polygon)) {
                currentContained.add(vehicleId);
                Set<Long> previous = previousContained.getOrDefault(zoneId, Collections.emptySet());
                if (onEnter && !previous.contains(vehicleId)) {
                    String key = vehicleId + ":" + zoneId + ":enter";
                    long last = lastEventTime.getOrDefault(key, 0L);
                    if (now - last >= EVENT_COOLDOWN_MS) {
                        events.add(new ZoneEvent(vehicleId, zoneId, ZoneEvent.EventType.ENTER, now));
                        lastEventTime.put(key, now);
                    }
                }
            }
        }
        Set<Long> previous = previousContained.getOrDefault(zoneId, Collections.emptySet());
        if (onExit) {
            for (long vehicleId : previous) {
                if (!currentContained.contains(vehicleId)) {
                    String key = vehicleId + ":" + zoneId + ":exit";
                    long last = lastEventTime.getOrDefault(key, 0L);
                    if (now - last >= EVENT_COOLDOWN_MS) {
                        events.add(new ZoneEvent(vehicleId, zoneId, ZoneEvent.EventType.EXIT, now));
                        lastEventTime.put(key, now);
                    }
                }
            }
        }
        previousContained.put(zoneId, currentContained);
        return events;
    }

    private BoundingBox computeBbox(List<Position> polygon) {
        if (polygon == null || polygon.isEmpty()) {
            return new BoundingBox(0, 0, 0, 0);
        }
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (Position p : polygon) {
            minX = Math.min(minX, p.x());
            minY = Math.min(minY, p.y());
            maxX = Math.max(maxX, p.x());
            maxY = Math.max(maxY, p.y());
        }
        return new BoundingBox(minX, minY, maxX, maxY);
    }
}
