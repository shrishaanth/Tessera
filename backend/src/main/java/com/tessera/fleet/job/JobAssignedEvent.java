package com.tessera.fleet.job;

/**
 * Published when a job is assigned to a vehicle. The built-in position simulator
 * listens for this to route that vehicle to the destination along the road
 * network (FR-2); it is inert for a real position feed, which cannot be steered.
 *
 * @param jobId         the assigned job
 * @param vehicleId     the vehicle it was assigned to
 * @param destLatitude  job destination latitude
 * @param destLongitude job destination longitude
 */
public record JobAssignedEvent(String jobId, String vehicleId,
                               double destLatitude, double destLongitude) { }
