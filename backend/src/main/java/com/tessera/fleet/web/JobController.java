package com.tessera.fleet.web;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tessera.fleet.job.JobService;
import com.tessera.fleet.model.Job;
import com.tessera.fleet.model.NearestVehicle;
import com.tessera.fleet.nearest.NearestVehicleService;

/**
 * Job creation and single-action assignment (FR-2.1, FR-2.4).
 *
 * <p>Phase 1 accepts a job location as coordinates (a map click in the UI).
 * Typed-address geocoding is FR-6 / Phase 4; the {@code destinationAddress} field
 * is carried through now so the contract does not change later.
 */
@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobService jobService;
    private final NearestVehicleService nearestVehicleService;

    public JobController(JobService jobService, NearestVehicleService nearestVehicleService) {
        this.jobService = jobService;
        this.nearestVehicleService = nearestVehicleService;
    }

    public record CreateJobRequest(
            String destinationAddress,
            double destLatitude,
            double destLongitude) { }

    public record CreateJobResponse(Job job, List<NearestVehicle> nearestAvailable) { }

    public record AssignRequest(@NotBlank String vehicleId) { }

    @GetMapping
    public List<Job> list() {
        return jobService.all();
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<Job> get(@PathVariable String jobId) {
        return jobService.get(jobId).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Create a job and return it together with the ranked nearest-available
     * shortlist for its location, so the dispatcher gets one round trip from
     * "location confirmed" to "pick a vehicle" (FR-2.1, FR-2.3).
     */
    @PostMapping
    public ResponseEntity<CreateJobResponse> create(@RequestBody CreateJobRequest body) {
        if (body.destLatitude() < -90 || body.destLatitude() > 90
                || body.destLongitude() < -180 || body.destLongitude() > 180) {
            return ResponseEntity.badRequest().build();
        }
        Job job = jobService.create(
                body.destinationAddress(), body.destLatitude(), body.destLongitude());
        List<NearestVehicle> nearest = nearestVehicleService.nearestAvailable(
                body.destLatitude(), body.destLongitude(), 0);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new CreateJobResponse(job, nearest));
    }

    /** Assign a job to a vehicle in a single action (FR-2.4). */
    @PostMapping("/{jobId}/assign")
    public Job assign(@PathVariable String jobId, @Valid @RequestBody AssignRequest body) {
        return jobService.assign(jobId, body.vehicleId());
    }
}
