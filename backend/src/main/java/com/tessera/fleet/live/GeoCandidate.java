package com.tessera.fleet.live;

/**
 * A vehicle returned by a Redis GEOSEARCH prefilter, with its straight-line
 * distance from the query point.
 */
public record GeoCandidate(
        String vehicleId,
        double latitude,
        double longitude,
        double straightLineMeters) {
}
