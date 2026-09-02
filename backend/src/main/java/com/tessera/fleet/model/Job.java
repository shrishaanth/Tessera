package com.tessera.fleet.model;

/**
 * A dispatch job: a destination that needs a vehicle.
 *
 * <p>Phase 1 keeps jobs in memory only — durable persistence arrives with the
 * cold layer in Phase 2. The shape here matches the SRS §7 {@code Job} entity so
 * the durable mapping is a lift, not a redesign.
 *
 * @param id                 generated job id
 * @param destinationAddress typed address as entered, or {@code null} if the
 *        dispatcher picked a point on the map
 * @param destLatitude       resolved destination latitude
 * @param destLongitude      resolved destination longitude
 * @param assignedVehicleId  vehicle the job was assigned to, or {@code null}
 * @param status             lifecycle status
 * @param createdAtEpochMs   creation time
 * @param assignedAtEpochMs  assignment time, or {@code 0} if unassigned
 */
public record Job(
        String id,
        String destinationAddress,
        double destLatitude,
        double destLongitude,
        String assignedVehicleId,
        JobStatus status,
        long createdAtEpochMs,
        long assignedAtEpochMs) {

    public Job assignedTo(String vehicleId, long whenEpochMs) {
        return new Job(id, destinationAddress, destLatitude, destLongitude,
                vehicleId, JobStatus.ASSIGNED, createdAtEpochMs, whenEpochMs);
    }
}
