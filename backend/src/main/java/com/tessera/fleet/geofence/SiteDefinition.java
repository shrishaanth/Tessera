package com.tessera.fleet.geofence;

import java.util.List;

/**
 * Request shape for defining or updating a customer site (FR-3.1). Exactly one of
 * {@code polygon} or ({@code centerLat}, {@code centerLon}, {@code radiusMeters})
 * must be supplied.
 *
 * @param name              display name (required)
 * @param address           free-text address (optional)
 * @param polygon           boundary vertices as {@code [lat, lon]} pairs, ≥ 3
 * @param centerLat         radius-site centre latitude
 * @param centerLon         radius-site centre longitude
 * @param radiusMeters      radius in metres
 * @param dwellAlertSeconds per-site dwell alert threshold; null = use the default (FR-3.5)
 */
public record SiteDefinition(
        String name,
        String address,
        List<List<Double>> polygon,
        Double centerLat,
        Double centerLon,
        Double radiusMeters,
        Integer dwellAlertSeconds) {

    public boolean isRadius() {
        return centerLat != null && centerLon != null && radiusMeters != null;
    }

    public boolean isPolygon() {
        return polygon != null && polygon.size() >= 3;
    }
}
