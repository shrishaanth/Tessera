package com.tessera.fleet.job;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.tessera.fleet.geofence.GeofenceEnteredEvent;

/**
 * Closes out a job when its vehicle enters the destination site (FR-4.1). Kept
 * separate from the geofence layer to avoid a dependency cycle — the geofence
 * layer only publishes {@link GeofenceEnteredEvent}.
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
}
