package com.tessera.fleet.model;

/**
 * The dispatcher-visible status of a vehicle (FR-1.1). This is a <em>resolved</em>
 * value computed from the live layer — freshness of the last report, whether an
 * active job is assigned, and (Phase 2+) geofence state — not a raw field stored
 * on the vehicle.
 */
public enum VehicleStatus {
    /** Fresh position, no active job — can take work. */
    AVAILABLE,
    /** Fresh position, has an assigned job it has not yet completed. */
    EN_ROUTE,
    /** Inside a customer-site geofence (Phase 2 drives this; kept here for a stable enum). */
    ON_SITE,
    /** No position report within the configured offline window. */
    OFFLINE
}
