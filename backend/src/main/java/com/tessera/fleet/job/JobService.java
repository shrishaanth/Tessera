package com.tessera.fleet.job;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Service;

import com.tessera.fleet.live.LiveFleetService;
import com.tessera.fleet.model.Job;
import com.tessera.fleet.model.JobStatus;

/**
 * Job creation and assignment (FR-2.4).
 *
 * <p>Phase 1 stores jobs in memory only — the durable layer (Phase 2) will own
 * the {@code Job} table. The API surface here is deliberately the one the SRS §7
 * entity implies, so swapping the map for a repository is mechanical.
 */
@Service
public class JobService {

    private final ConcurrentHashMap<String, Job> jobs = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong(1000);
    private final LiveFleetService liveFleet;

    public JobService(LiveFleetService liveFleet) {
        this.liveFleet = liveFleet;
    }

    public Job create(String destinationAddress, double destLat, double destLon) {
        String id = "JOB-" + sequence.incrementAndGet();
        long now = System.currentTimeMillis();
        Job job = new Job(id, destinationAddress, destLat, destLon, null,
                JobStatus.UNASSIGNED, now, 0L);
        jobs.put(id, job);
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

    /**
     * Assign {@code jobId} to {@code vehicleId} in a single action (FR-2.4) and
     * flip the vehicle's live status to {@code EN_ROUTE}.
     *
     * @throws IllegalArgumentException if the job is unknown or the vehicle is not live
     * @throws IllegalStateException    if the job is already assigned
     */
    public Job assign(String jobId, String vehicleId) {
        Job job = jobs.get(jobId);
        if (job == null) {
            throw new IllegalArgumentException("Unknown job " + jobId);
        }
        if (job.status() == JobStatus.ASSIGNED) {
            throw new IllegalStateException("Job " + jobId + " is already assigned to "
                    + job.assignedVehicleId());
        }
        if (!liveFleet.exists(vehicleId)) {
            throw new IllegalArgumentException("Unknown vehicle " + vehicleId);
        }
        Job assigned = job.assignedTo(vehicleId, System.currentTimeMillis());
        jobs.put(jobId, assigned);
        liveFleet.setCurrentJob(vehicleId, jobId);
        return assigned;
    }

    /** Test/support hook. */
    public void clear() {
        jobs.clear();
    }
}
