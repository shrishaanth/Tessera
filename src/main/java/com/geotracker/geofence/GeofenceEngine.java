package com.geotracker.geofence;

import com.geotracker.index.CowQuadtree;
import com.geotracker.index.HamtIndex;
import com.geotracker.index.IndexerThread;
import com.geotracker.index.SpatialSnapshot;
import com.geotracker.model.BoundingBox;
import com.geotracker.model.Position;
import com.geotracker.model.ZoneEvent;

import java.util.*;

public class GeofenceEngine {
    public record Zone(String id, List<Position> polygon, BoundingBox bbox) {}

    private final IndexerThread[] indexers;
    private final CowQuadtree[] quadtrees;
    private final HamtIndex[] hamts;
    private final Map<String, Set<Long>> previousContained = new HashMap<>();
    private final List<Zone> zones;

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

    public synchronized List<ZoneEvent> check() {
        List<ZoneEvent> events = new ArrayList<>();
        for (Zone zone : zones) {
            Set<Long> candidates = new HashSet<>();
            if (indexers != null) {
                for (IndexerThread indexer : indexers) {
                    SpatialSnapshot snapshot = indexer.getPublishedSnapshot();
                    if (snapshot != null) {
                        candidates.addAll(snapshot.quadtree().rangeQuery(zone.bbox()));
                    }
                }
            } else {
                for (CowQuadtree qt : quadtrees) {
                    candidates.addAll(qt.rangeQuery(zone.bbox()));
                }
            }
            Set<Long> currentContained = new HashSet<>();
            for (long vehicleId : candidates) {
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
}
