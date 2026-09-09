package com.tessera.fleet.ingestion;

/**
 * Published when a routed simulator vehicle reaches its assigned destination.
 * Lets a job be completed on arrival even when the destination is not inside a
 * geofenced customer site (so no geofence ENTER fires). FR-4.1.
 *
 * @param vehicleId   the vehicle that arrived
 * @param epochMillis arrival time
 */
public record VehicleArrivedEvent(String vehicleId, long epochMillis) { }
