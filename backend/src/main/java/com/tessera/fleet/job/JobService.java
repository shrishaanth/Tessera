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
import com.tessera.fleet.live.LiveFleetService;
import com.tessera.fleet.model.Job;
import com.tessera.fleet.model.JobStatus;

/**
 * Job creation and assignment (FR-2.4).
 *
 * <p>An in-memory map is the live index; every change is also written through to
 * the {@link DurableStore} (best-effort — a durable write failure never blocks a
 * dispatch action, SRS §2.5) and the map is rehydrated from the store on startup.
 */
@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private final ConcurrentHashMap<String, Job> jobs = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong(1000);
    private final LiveFleetService liveFleet;
    private final DurableStore durableStore;

    public JobService(LiveFleetService liveFleet, DurableStore durableStore) {
        this.liveFleet = liveFleet;
        this.durableStore = durableStore;
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

    public Job create(String destinationAddress, double destLat, double destLon) {
        String id = "JOB-" + sequence.incrementAndGet();
        long now = System.currentTimeMillis();
        Job job = new Job(id, destinationAddress, destLat, destLon, null,
                JobStatus.UNASSIGNED, now, 0L);
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
        persist(assigned);
        return assigned;
    }

    private void persist(Job job) {
        try {
            durableStore.saveJob(new JobRecord(job.id(), job.destinationAddress(),
                    job.destLatitude(), job.destLongitude(), job.assignedVehicleId(),
                    job.status().name(), job.createdAtEpochMs(),
                    job.assignedAtEpochMs() == 0 ? null : job.assignedAtEpochMs(), null));
        } catch (Exception e) {
            log.warn("Durable job write failed for {}: {}", job.id(), e.toString());
        }
    }

    private static Job toJob(JobRecord r) {
        return new Job(r.jobId(), r.destinationAddress(), r.destLatitude(), r.destLongitude(),
                r.assignedVehicleId(), JobStatus.valueOf(r.status()), r.createdAtEpochMs(),
                r.assignedAtEpochMs() == null ? 0L : r.assignedAtEpochMs());
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
