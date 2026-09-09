package com.tessera.fleet.job;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.tessera.fleet.geofence.GeofenceEnteredEvent;
import com.tessera.fleet.ingestion.VehicleArrivedEvent;

/**
 * Closes out a job when its vehicle reaches the destination (FR-4.1). Two
 * triggers: a geofence ENTER at the destination site, or — when the destination
 * is not inside any site — the simulator reporting road-network arrival. Kept
 * separate from the geofence and ingestion layers to avoid a dependency cycle;
 * both only publish events.
 */
@Component
public class JobArrivalListener {

    private final JobService jobService;

    public JobArrivalListener(JobService jobService) {
        this.jobService = jobService;
    }

    @EventListener
    public void onGeofenceEntered(GeofenceEnteredEvent event) {
        jobService.recordArrival(event.vehicleId(), event.siteId(), event.epochMillis());
    }

    @EventListener
    public void onVehicleArrived(VehicleArrivedEvent event) {
        jobService.recordArrivalAtDestination(event.vehicleId(), event.epochMillis());
    }
}
