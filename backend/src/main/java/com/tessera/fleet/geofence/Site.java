package com.tessera.fleet.geofence;

import com.tessera.fleet.durable.SiteRecord;

/**
 * A customer site as the live geofence engine holds it: identity and metadata
 * plus a ready-to-query {@link SiteGeometry}.
 */
public final class Site {

    private final String id;
    private final String name;
    private final String address;
    private final SiteGeometry geometry;
    private final Double centerLat;
    private final Double centerLon;
    private final Double radiusMeters;
    private final Integer dwellAlertSeconds;
    private final long createdAtEpochMs;

    public Site(String id, String name, String address, SiteGeometry geometry,
               Double centerLat, Double centerLon, Double radiusMeters,
               Integer dwellAlertSeconds, long createdAtEpochMs) {
        this.id = id;
        this.name = name;
        this.address = address;
        this.geometry = geometry;
        this.centerLat = centerLat;
        this.centerLon = centerLon;
        this.radiusMeters = radiusMeters;
        this.dwellAlertSeconds = dwellAlertSeconds;
        this.createdAtEpochMs = createdAtEpochMs;
    }

    public static Site fromRecord(SiteRecord r) {
        return new Site(r.siteId(), r.name(), r.address(),
                SiteGeometry.fromWkt(r.boundaryWkt()),
                r.centerLat(), r.centerLon(), r.radiusMeters(),
                r.dwellAlertSeconds(), r.createdAtEpochMs());
    }

    public SiteRecord toRecord() {
        return new SiteRecord(id, name, address, geometry.toWkt(),
                centerLat, centerLon, radiusMeters, dwellAlertSeconds, createdAtEpochMs);
    }

    public boolean contains(double lat, double lon) {
        return geometry.contains(lat, lon);
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String address() {
        return address;
    }

    public SiteGeometry geometry() {
        return geometry;
    }

    public Double centerLat() {
        return centerLat;
    }

    public Double centerLon() {
        return centerLon;
    }

    public Double radiusMeters() {
        return radiusMeters;
    }

    public Integer dwellAlertSeconds() {
        return dwellAlertSeconds;
    }

    public long createdAtEpochMs() {
        return createdAtEpochMs;
    }
}
