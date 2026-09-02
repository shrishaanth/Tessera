package com.tessera.fleet.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.tessera.fleet.durable.InMemoryDurableStore;
import com.tessera.fleet.geofence.Site;
import com.tessera.fleet.geofence.SiteGeometry;
import com.tessera.fleet.geofence.SiteService;
import com.tessera.fleet.live.LiveFleetService;
import com.tessera.fleet.model.Job;
import com.tessera.fleet.model.JobStatus;
import com.tessera.fleet.model.Vehicle;
import com.tessera.fleet.model.VehicleStatus;
import com.tessera.fleet.routing.TravelTimeService;
import com.tessera.fleet.support.TestFixtures;

/**
 * Assignment records the destination site + an expected arrival time, and a
 * geofence ENTER at that site completes the job — with on-time computed against
 * the expected arrival (FR-4.1).
 */
class JobArrivalCompletionTest {

    private static final Site SITE = new Site("S1", "Acme Corp", null,
            SiteGeometry.fromRadius(42.3560, -71.0635, 150), 42.3560, -71.0635, 150.0, null, 0L);

    private LiveFleetService liveFleet;
    private SiteService siteService;
    private InMemoryDurableStore durable;
    private JobService jobService;

    @BeforeEach
    void setUp() {
        liveFleet = mock(LiveFleetService.class);
        siteService = mock(SiteService.class);
        durable = new InMemoryDurableStore();
        TravelTimeService travelTime = new TravelTimeService(TestFixtures.realRoadGraph());
        jobService = new JobService(liveFleet, durable, siteService, travelTime);

        when(siteService.siteContaining(anyDouble(), anyDouble())).thenReturn(Optional.of(SITE));
        Vehicle vehicle = new Vehicle("CAR-2", "Ada", VehicleStatus.AVAILABLE,
                42.3606, -71.0585, 0, 20, System.currentTimeMillis(), null, null);
        when(liveFleet.getVehicle("CAR-2")).thenReturn(vehicle);
    }

    @Test
    void createLinksTheDestinationToTheContainingSite() {
        Job job = jobService.create("North Loop", "Acme Corp", 42.3560, -71.0635);
        assertThat(job.route()).isEqualTo("North Loop");
        assertThat(job.siteId()).isEqualTo("S1");
    }

    @Test
    void assignRecordsDriverExpectedArrivalAndFlipsLiveStatus() {
        Job job = jobService.create(null, "Acme Corp", 42.3560, -71.0635);
        long before = System.currentTimeMillis();
        Job assigned = jobService.assign(job.id(), "CAR-2");

        assertThat(assigned.status()).isEqualTo(JobStatus.ASSIGNED);
        assertThat(assigned.driverName()).isEqualTo("Ada");
        assertThat(assigned.assignedVehicleId()).isEqualTo("CAR-2");
        assertThat(assigned.expectedArrivalEpochMs()).isGreaterThan(before);
        verify(liveFleet).setCurrentJob("CAR-2", job.id());
    }

    @Test
    void geofenceEnterAtTheDestinationSiteCompletesTheJobAndScoresOnTime() {
        Job job = jobService.create(null, "Acme Corp", 42.3560, -71.0635);
        Job assigned = jobService.assign(job.id(), "CAR-2");

        long onTimeArrival = assigned.expectedArrivalEpochMs() - 30_000; // 30s early
        Optional<Job> completed = jobService.recordArrival("CAR-2", "S1", onTimeArrival);

        assertThat(completed).isPresent();
        assertThat(completed.get().status()).isEqualTo(JobStatus.COMPLETED);
        assertThat(completed.get().actualArrivalEpochMs()).isEqualTo(onTimeArrival);
        assertThat(completed.get().completedAtEpochMs()).isEqualTo(onTimeArrival);
        assertThat(completed.get().arrivedOnTime(0)).isTrue();
        verify(liveFleet).clearCurrentJob("CAR-2");

        assertThat(durable.completedJobs(0, onTimeArrival + 1000)).hasSize(1);
    }

    @Test
    void arrivalAtADifferentSiteOrWithNoMatchingJobIsANoOp() {
        Job job = jobService.create(null, "Acme Corp", 42.3560, -71.0635);
        jobService.assign(job.id(), "CAR-2");

        assertThat(jobService.recordArrival("CAR-2", "OTHER-SITE", System.currentTimeMillis()))
                .isEmpty();
        assertThat(jobService.recordArrival("CAR-999", "S1", System.currentTimeMillis()))
                .isEmpty();
        assertThat(jobService.get(job.id()).orElseThrow().status()).isEqualTo(JobStatus.ASSIGNED);
    }

    @Test
    void lateArrivalIsNotScoredOnTime() {
        Job job = jobService.create(null, "Acme Corp", 42.3560, -71.0635);
        Job assigned = jobService.assign(job.id(), "CAR-2");

        Job completed = jobService.recordArrival(
                "CAR-2", "S1", assigned.expectedArrivalEpochMs() + 600_000).orElseThrow();
        assertThat(completed.arrivedOnTime(300_000)).isFalse();
    }
}
