package com.tessera.fleet.model;

/**
 * The map-visible status of a vehicle (FR-1.1). This is a <em>resolved</em> value
 * computed from the live layer — freshness of the last report and (Phase 2+)
 * geofence state — not a raw field stored on the vehicle.
 */
public enum VehicleStatus {
    /** Reporting a fresh position and not inside a customer site. */
    ACTIVE,
    /** Inside a customer-site geofence (Phase 2 drives this). */
    ON_SITE,
    /** No position report within the configured offline window. */
    OFFLINE
}
