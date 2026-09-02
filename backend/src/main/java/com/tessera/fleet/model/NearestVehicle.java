package com.tessera.fleet.model;

/**
 * One ranked entry in a nearest-available-vehicle shortlist (FR-2.1–FR-2.3).
 * Ranking key is {@link #travelSeconds()} — real road-network travel time, not
 * straight-line distance (FR-2.2).
 *
 * @param vehicleId        the candidate vehicle
 * @param driverName       driver display name (may be {@code null})
 * @param straightLineMeters great-circle distance to the job, for reference only
 * @param travelSeconds    estimated road-network drive time to the job
 * @param latitude         vehicle latitude at time of query
 * @param longitude        vehicle longitude at time of query
 */
public record NearestVehicle(
        String vehicleId,
        String driverName,
        double straightLineMeters,
        double travelSeconds,
        double latitude,
        double longitude) {
}
