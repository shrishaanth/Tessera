package com.tessera.fleet.geofence;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.tessera.fleet.durable.DurableStore;
import com.tessera.fleet.durable.SiteRecord;

/**
 * Customer-site CRUD (FR-3.1). Sites are written to the durable store and the
 * live {@link GeofenceEngine} is rebuilt after every change so geofencing picks
 * them up immediately.
 */
@Service
public class SiteService {

    private final DurableStore durableStore;
    private final GeofenceService geofenceService;

    public SiteService(DurableStore durableStore, GeofenceService geofenceService) {
        this.durableStore = durableStore;
        this.geofenceService = geofenceService;
    }

    public List<Site> list() {
        return new ArrayList<>(geofenceService.sites());
    }

    public Optional<Site> get(String siteId) {
        return geofenceService.sites().stream().filter(s -> s.id().equals(siteId)).findFirst();
    }

    public Site create(SiteDefinition def) {
        return upsert("SITE-" + UUID.randomUUID().toString().substring(0, 8), def,
                System.currentTimeMillis());
    }

    public Site update(String siteId, SiteDefinition def) {
        long createdAt = get(siteId)
                .map(Site::createdAtEpochMs)
                .orElseThrow(() -> new IllegalArgumentException("Unknown site " + siteId));
        return upsert(siteId, def, createdAt);
    }

    public void delete(String siteId) {
        if (get(siteId).isEmpty()) {
            throw new IllegalArgumentException("Unknown site " + siteId);
        }
        durableStore.deleteSite(siteId);
        geofenceService.reloadSites();
    }

    private Site upsert(String siteId, SiteDefinition def, long createdAtEpochMs) {
        if (def.name() == null || def.name().isBlank()) {
            throw new IllegalArgumentException("Site name is required");
        }
        SiteGeometry geometry = geometryOf(def);
        SiteRecord record = new SiteRecord(
                siteId, def.name().trim(), def.address(), geometry.toWkt(),
                def.isRadius() ? def.centerLat() : null,
                def.isRadius() ? def.centerLon() : null,
                def.isRadius() ? def.radiusMeters() : null,
                def.dwellAlertSeconds(), createdAtEpochMs);
        durableStore.saveSite(record);
        geofenceService.reloadSites();
        return Site.fromRecord(record);
    }

    private static SiteGeometry geometryOf(SiteDefinition def) {
        if (def.isRadius() && def.isPolygon()) {
            throw new IllegalArgumentException("Provide either a polygon or a centre+radius, not both");
        }
        if (def.isRadius()) {
            return SiteGeometry.fromRadius(def.centerLat(), def.centerLon(), def.radiusMeters());
        }
        if (def.isPolygon()) {
            List<double[]> ring = new ArrayList<>(def.polygon().size());
            for (List<Double> p : def.polygon()) {
                if (p.size() < 2) {
                    throw new IllegalArgumentException("Each polygon vertex needs [lat, lon]");
                }
                ring.add(new double[] {p.get(0), p.get(1)});
            }
            return SiteGeometry.fromPolygon(ring);
        }
        throw new IllegalArgumentException(
                "A site needs a polygon (>= 3 vertices) or a centre and radiusMeters");
    }
}
