package com.tessera.fleet.geofence;

/**
 * Published when a vehicle's debounced ENTER into a site is confirmed. Lets the
 * job layer close out a job on arrival without the geofence layer depending on it
 * (arrival = geofence ENTER at the destination site — FR-4.1).
 */
public record GeofenceEnteredEvent(String vehicleId, String siteId, long epochMillis) { }
