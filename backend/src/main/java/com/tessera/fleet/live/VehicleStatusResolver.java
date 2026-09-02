package com.tessera.fleet.live;

import org.springframework.stereotype.Component;

import com.tessera.fleet.config.FleetProperties;
import com.tessera.fleet.model.VehicleStatus;

/**
 * Derives the dispatcher-visible {@link VehicleStatus} (FR-1.1) from live-layer
 * facts. Kept tiny and pure so it is trivially unit-testable and identical
 * whether invoked on a position report, on job assignment, or on the periodic
 * offline sweep.
 */
@Component
public class VehicleStatusResolver {

    private final long offlineAfterMillis;

    public VehicleStatusResolver(FleetProperties properties) {
        this.offlineAfterMillis = properties.offlineAfterSeconds() * 1000L;
    }

    /**
     * @param lastReportEpochMs timestamp of the vehicle's most recent position report
     * @param nowEpochMs        current time
     * @param hasActiveJob      an assigned, not-yet-completed job exists
     * @param insideGeofence    vehicle is within a customer-site boundary (Phase 2+)
     */
    public VehicleStatus resolve(long lastReportEpochMs, long nowEpochMs,
                                 boolean hasActiveJob, boolean insideGeofence) {
        if (lastReportEpochMs <= 0 || nowEpochMs - lastReportEpochMs > offlineAfterMillis) {
            return VehicleStatus.OFFLINE;
        }
        if (insideGeofence) {
            return VehicleStatus.ON_SITE;
        }
        return hasActiveJob ? VehicleStatus.EN_ROUTE : VehicleStatus.AVAILABLE;
    }

    public long offlineAfterMillis() {
        return offlineAfterMillis;
    }
}
