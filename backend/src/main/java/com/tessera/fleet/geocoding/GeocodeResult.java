package com.tessera.fleet.geocoding;

/**
 * One geocoding suggestion (FR-6.1). Carries real coordinates so a selected
 * suggestion can go straight into a nearest-vehicle search without a second
 * round trip (FR-6.2).
 *
 * @param displayName human-readable address as Nominatim returned it
 * @param latitude    WGS-84 latitude
 * @param longitude   WGS-84 longitude
 * @param category    OSM class (e.g. "highway", "building", "place")
 * @param type        OSM type (e.g. "residential", "house")
 * @param importance  Nominatim's 0–1 relevance score (used for ordering)
 */
public record GeocodeResult(
        String displayName,
        double latitude,
        double longitude,
        String category,
        String type,
        double importance) {
}
