package com.geotracker.geofence;

import com.geotracker.index.CowQuadtree;
import com.geotracker.index.HamtIndex;
import com.geotracker.model.BoundingBox;
import com.geotracker.model.Position;
import com.geotracker.model.ZoneEvent;

import java.util.*;

public class GeofenceEngine {
    public record Zone(String id, List<Position> polygon, BoundingBox bbox) {}

    private final CowQuadtree quadtree;
    private final HamtIndex hamt;
    private final Map<String, Set<Long>> previousContained = new HashMap<>();
    private final List<Zone> zones;

    public GeofenceEngine(CowQuadtree quadtree, HamtIndex hamt, List<Zone> zones) {
        this.quadtree = quadtree;
        this.hamt = hamt;
        this.zones = zones;
        for (Zone zone : zones) {
            previousContained.put(zone.id(), new HashSet<>());
        }
    }

    public synchronized List<ZoneEvent> check() {
        List<ZoneEvent> events = new ArrayList<>();
        for (Zone zone : zones) {
            Set<Long> candidates = new HashSet<>(quadtree.rangeQuery(zone.bbox()));
            Set<Long> currentContained = new HashSet<>();
            for (long vehicleId : candidates) {
                Position pos = hamt.get(vehicleId);
                if (pos == null) continue;
                if (RayCaster.contains(pos, zone.polygon())) {
                    currentContained.add(vehicleId);
                    if (!previousContained.get(zone.id()).contains(vehicleId)) {
                        events.add(new ZoneEvent(vehicleId, zone.id(), ZoneEvent.EventType.ENTER, System.currentTimeMillis()));
                    }
                }
            }
            for (long vehicleId : previousContained.get(zone.id())) {
                if (!currentContained.contains(vehicleId)) {
                    events.add(new ZoneEvent(vehicleId, zone.id(), ZoneEvent.EventType.EXIT, System.currentTimeMillis()));
                }
            }
            previousContained.put(zone.id(), currentContained);
        }
        return events;
    }

    public void shutdown() {
    }
}
