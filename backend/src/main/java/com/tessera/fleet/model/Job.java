package com.tessera.fleet.model;

/**
 * A dispatch job: a destination that needs a vehicle.
 *
 * <p>The shape matches the SRS §7 {@code Job} entity, extended in Phase 3 with the
 * fields on-time reporting needs (FR-4.1): an optional {@code route} tag, the
 * customer {@code siteId} the destination falls in, and the expected vs actual
 * arrival timestamps. "Arrival" is a geofence ENTER at the destination site.
 *
 * @param id                     generated job id
 * @param route                  optional route/round label for reporting, or {@code null}
 * @param destinationAddress     typed address as entered, or {@code null} for a map pick
 * @param destLatitude           resolved destination latitude
 * @param destLongitude          resolved destination longitude
 * @param siteId                 customer site containing the destination, or {@code null}
 * @param assignedVehicleId      vehicle the job was assigned to, or {@code null}
 * @param driverName             driver at time of assignment (captured for reporting), or {@code null}
 * @param status                 lifecycle status
 * @param createdAtEpochMs       creation time
 * @param assignedAtEpochMs      assignment time, or {@code 0} if unassigned
 * @param expectedArrivalEpochMs assignment time + road-network ETA, or {@code 0}
 * @param actualArrivalEpochMs   geofence ENTER at the destination site, or {@code 0}
 * @param completedAtEpochMs     completion time, or {@code 0}
 */
public record Job(
        String id,
        String route,
        String destinationAddress,
        double destLatitude,
        double destLongitude,
        String siteId,
        String assignedVehicleId,
        String driverName,
        JobStatus status,
        long createdAtEpochMs,
        long assignedAtEpochMs,
        long expectedArrivalEpochMs,
        long actualArrivalEpochMs,
        long completedAtEpochMs) {

    public Job assigned(String vehicleId, String driverName, String siteId,
                        long whenEpochMs, long expectedArrivalEpochMs) {
        return new Job(id, route, destinationAddress, destLatitude, destLongitude, siteId,
                vehicleId, driverName, JobStatus.ASSIGNED, createdAtEpochMs, whenEpochMs,
                expectedArrivalEpochMs, actualArrivalEpochMs, completedAtEpochMs);
    }

    public Job completedOnArrival(long arrivalEpochMs) {
        return new Job(id, route, destinationAddress, destLatitude, destLongitude, siteId,
                assignedVehicleId, driverName, JobStatus.COMPLETED, createdAtEpochMs,
                assignedAtEpochMs, expectedArrivalEpochMs, arrivalEpochMs, arrivalEpochMs);
    }

    /** On time if it arrived no later than expected plus the grace window (FR-4.1). */
    public boolean arrivedOnTime(long graceMillis) {
        return actualArrivalEpochMs > 0 && expectedArrivalEpochMs > 0
                && actualArrivalEpochMs <= expectedArrivalEpochMs + graceMillis;
    }
}
