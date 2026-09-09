package com.tessera.fleet.ingestion;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.tessera.fleet.job.JobAssignedEvent;

/**
 * Bridges job assignment to the built-in simulator: an assigned vehicle drives to
 * its destination along the road network instead of roaming (FR-2). Kept separate
 * from {@link com.tessera.fleet.job.JobService} to avoid a dependency on the
 * ingestion layer. Inert when the active feed is a real one — there is nothing to
 * steer.
 */
@Component
public class SimulatorRouting {

    private final PositionSource source;

    public SimulatorRouting(PositionSource source) {
        this.source = source;
    }

    @EventListener
    public void onJobAssigned(JobAssignedEvent event) {
        if (source instanceof SimulatedPositionSource sim) {
            sim.assignDestination(event.vehicleId(),
                    event.destLatitude(), event.destLongitude());
        }
    }
}
