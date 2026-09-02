package com.tessera.fleet.job;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.tessera.fleet.durable.DurableStore;
import com.tessera.fleet.durable.JobRecord;
import com.tessera.fleet.geofence.Site;
import com.tessera.fleet.geofence.SiteService;
import com.tessera.fleet.live.LiveFleetService;
import com.tessera.fleet.model.Job;
import com.tessera.fleet.model.JobStatus;
import com.tessera.fleet.model.Vehicle;
import com.tessera.fleet.routing.TravelTimeService;

/**
 * Job creation, single-action assignment (FR-2.4) and arrival-completion (FR-4.1).
 *
 * <p>An in-memory map is the live index; every change is written through to the
 * {@link DurableStore} (best-effort — a durable write failure never blocks a
 * dispatch action, SRS §2.5) and the map is rehydrated on startup. On assignment
 * the destination is linked to the customer site that contains it and an expected
 * arrival time is recorded (assignment time + road-network ETA); the job is
 * completed when the vehicle's geofence ENTER at that site fires.
 */
@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private final ConcurrentHashMap<String, Job> jobs = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong(1000);
    private final LiveFleetService liveFleet;
    private final DurableStore durableStore;
    private final SiteService siteService;
    private final TravelTimeService travelTime;

    public JobService(LiveFleetService liveFleet, DurableStore durableStore,
                      SiteService siteService, TravelTimeService travelTime) {
        this.liveFleet = liveFleet;
        this.durableStore = durableStore;
        this.siteService = siteService;
        this.travelTime = travelTime;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void rehydrate() {
        try {
            long maxSeq = sequence.get();
            for (JobRecord r : durableStore.loadJobs()) {
                jobs.put(r.jobId(), toJob(r));
                maxSeq = Math.max(maxSeq, parseSeq(r.jobId()));
            }
            sequence.set(maxSeq);
            if (!jobs.isEmpty()) {
                log.info("Rehydrated {} job(s) from the durable store", jobs.size());
            }
        } catch (Exception e) {
            log.warn("Could not rehydrate jobs from the durable store: {}", e.toString());
        }
    }

    public Job create(String route, String destinationAddress, double destLat, double destLon) {
        String id = "JOB-" + sequence.incrementAndGet();
        long now = System.currentTimeMillis();
        String siteId = siteService.siteContaining(destLat, destLon).map(Site::id).orElse(null);
        Job job = new Job(id, blankToNull(route), destinationAddress, destLat, destLon, siteId,
                null, null, JobStatus.UNASSIGNED, now, 0L, 0L, 0L, 0L);
        jobs.put(id, job);
        persist(job);
        return job;
    }

    public Optional<Job> get(String id) {
        return Optional.ofNullable(jobs.get(id));
    }

    public List<Job> all() {
        return jobs.values().stream()
                .sorted((a, b) -> Long.compare(b.createdAtEpochMs(), a.createdAtEpochMs()))
                .toList();
    }

    public Job assign(String jobId, String vehicleId) {
        Job job = jobs.get(jobId);
        if (job == null) {
            throw new IllegalArgumentException("Unknown job " + jobId);
        }
        if (job.status() == JobStatus.ASSIGNED) {
            throw new IllegalStateException("Job " + jobId + " is already assigned to "
                    + job.assignedVehicleId());
        }
        Vehicle vehicle = liveFleet.getVehicle(vehicleId);
        if (vehicle == null) {
            throw new IllegalArgumentException("Unknown vehicle " + vehicleId);
        }
        long assignedAt = System.currentTimeMillis();
        double etaSeconds = travelTime.travelSecondsBetween(
                vehicle.latitude(), vehicle.longitude(), job.destLatitude(), job.destLongitude());
        long expectedArrival = Double.isFinite(etaSeconds)
                ? assignedAt + Math.round(etaSeconds * 1000.0) : 0L;

        Job assigned = job.assigned(vehicleId, vehicle.driverName(), job.siteId(),
                assignedAt, expectedArrival);
        jobs.put(jobId, assigned);
        liveFleet.setCurrentJob(vehicleId, jobId);
        persist(assigned);
        return assigned;
    }

    /**
     * A vehicle has entered a site (geofence ENTER). If it has an assigned,
     * not-yet-arrived job for that site, mark it completed (FR-4.1).
     */
    public Optional<Job> recordArrival(String vehicleId, String siteId, long arrivalEpochMs) {
        for (Job job : jobs.values()) {
            if (job.status() == JobStatus.ASSIGNED
                    && vehicleId.equals(job.assignedVehicleId())
                    && siteId.equals(job.siteId())
                    && job.actualArrivalEpochMs() == 0) {
                Job completed = job.completedOnArrival(arrivalEpochMs);
                jobs.put(job.id(), completed);
                liveFleet.clearCurrentJob(vehicleId);
                persist(completed);
                log.debug("Job {} completed on arrival at {} ({})",
                        job.id(), siteId, completed.arrivedOnTime(0) ? "on time" : "late");
                return Optional.of(completed);
            }
        }
        return Optional.empty();
    }

    private void persist(Job job) {
        try {
            durableStore.saveJob(new JobRecord(job.id(), job.route(), job.destinationAddress(),
                    job.destLatitude(), job.destLongitude(), job.siteId(), job.assignedVehicleId(),
                    job.driverName(), job.status().name(), job.createdAtEpochMs(),
                    zeroToNull(job.assignedAtEpochMs()), zeroToNull(job.expectedArrivalEpochMs()),
                    zeroToNull(job.actualArrivalEpochMs()), zeroToNull(job.completedAtEpochMs())));
        } catch (Exception e) {
            log.warn("Durable job write failed for {}: {}", job.id(), e.toString());
        }
    }

    private static Job toJob(JobRecord r) {
        return new Job(r.jobId(), r.route(), r.destinationAddress(), r.destLatitude(),
                r.destLongitude(), r.siteId(), r.assignedVehicleId(), r.driverName(),
                JobStatus.valueOf(r.status()), r.createdAtEpochMs(),
                nz(r.assignedAtEpochMs()), nz(r.expectedArrivalEpochMs()),
                nz(r.actualArrivalEpochMs()), nz(r.completedAtEpochMs()));
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    private static Long zeroToNull(long v) {
        return v == 0 ? null : v;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static long parseSeq(String jobId) {
        try {
            return Long.parseLong(jobId.substring(jobId.lastIndexOf('-') + 1));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** Test/support hook. */
    public void clear() {
        jobs.clear();
    }
}
