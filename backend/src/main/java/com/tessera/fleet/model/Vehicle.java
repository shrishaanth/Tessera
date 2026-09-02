package com.tessera.fleet.model;

/**
 * A live snapshot of one vehicle, assembled from the Redis live layer.
 *
 * @param vehicleId       stable identifier
 * @param driverName      driver display name (may be {@code null})
 * @param status          resolved dispatcher-visible status (FR-1.1)
 * @param latitude        last known latitude
 * @param longitude       last known longitude
 * @param headingDeg      last known heading, or {@code NaN}
 * @param speedKph        last known speed, or {@code NaN}
 * @param lastReportEpochMs timestamp of the last position report
 * @param currentJobId    id of the assigned job, or {@code null}
 * @param onSiteId        id of the customer site the vehicle is currently inside
 *        (Phase 2 geofencing), or {@code null}
 */
public record Vehicle(
        String vehicleId,
        String driverName,
        VehicleStatus status,
        double latitude,
        double longitude,
        double headingDeg,
        double speedKph,
        long lastReportEpochMs,
        String currentJobId,
        String onSiteId) {
}
