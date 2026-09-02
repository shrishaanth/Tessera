package com.tessera.fleet.durable;

/**
 * A customer site (SRS §7 "Site") as stored durably.
 *
 * <p>The boundary (FR-3.1) is carried as WKT in lon/lat (SRID 4326) order so the
 * durable store stays independent of any geometry library: PostGIS parses it with
 * {@code ST_GeomFromText}, the live geofence engine with a JTS {@code WKTReader}.
 * For radius-defined sites the WKT is the buffered circle, and {@code centerLat/
 * centerLon/radiusMeters} are kept so the definition round-trips exactly.
 *
 * @param siteId            stable id (caller-supplied or generated)
 * @param name              display name
 * @param address           free-text address, or {@code null}
 * @param boundaryWkt       polygon boundary as WKT, lon/lat order
 * @param centerLat         radius-site centre latitude, or {@code null} for a polygon site
 * @param centerLon         radius-site centre longitude, or {@code null}
 * @param radiusMeters      radius in metres, or {@code null}
 * @param dwellAlertSeconds per-site dwell alert threshold, or {@code null} to use the default (FR-3.5)
 * @param createdAtEpochMs  creation time
 */
public record SiteRecord(
        String siteId,
        String name,
        String address,
        String boundaryWkt,
        Double centerLat,
        Double centerLon,
        Double radiusMeters,
        Integer dwellAlertSeconds,
        long createdAtEpochMs) {
}
