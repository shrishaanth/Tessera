package com.tessera.fleet.durable;

/**
 * A dispatch job as stored durably (SRS §7 "Job"). Persisted write-through from
 * the live {@code JobService} so jobs survive a restart and Phase 3 reporting has
 * a history to query.
 */
public record JobRecord(
        String jobId,
        String destinationAddress,
        double destLatitude,
        double destLongitude,
        String assignedVehicleId,
        String status,
        long createdAtEpochMs,
        Long assignedAtEpochMs,
        Long completedAtEpochMs) {
}
