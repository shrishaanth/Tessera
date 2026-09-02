package com.tessera.fleet.durable;

/**
 * A dispatch job as stored durably (SRS §7 "Job"). Persisted write-through from
 * the live {@code JobService}. Phase 3 adds the reporting fields
 * ({@code route}, {@code siteId}, expected/actual arrival).
 */
public record JobRecord(
        String jobId,
        String route,
        String destinationAddress,
        double destLatitude,
        double destLongitude,
        String siteId,
        String assignedVehicleId,
        String driverName,
        String status,
        long createdAtEpochMs,
        Long assignedAtEpochMs,
        Long expectedArrivalEpochMs,
        Long actualArrivalEpochMs,
        Long completedAtEpochMs) {
}
