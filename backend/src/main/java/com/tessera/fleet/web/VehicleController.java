package com.tessera.fleet.web;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.tessera.fleet.durable.DurableStore;
import com.tessera.fleet.durable.GeofenceEventRecord;
import com.tessera.fleet.geofence.Site;
import com.tessera.fleet.geofence.SiteService;
import com.tessera.fleet.job.JobService;
import com.tessera.fleet.live.LiveFleetService;
import com.tessera.fleet.model.Job;
import com.tessera.fleet.model.NearestVehicle;
import com.tessera.fleet.model.StatusChange;
import com.tessera.fleet.model.Vehicle;
import com.tessera.fleet.model.VehicleStatus;
import com.tessera.fleet.nearest.NearestVehicleService;
import com.tessera.fleet.routing.TravelTimeService;

/**
 * Read side of the dispatcher live map (FR-1) and the nearest-available-vehicle
 * shortlist (FR-2.1–FR-2.3).
 */
@RestController
@RequestMapping("/api/vehicles")
public class VehicleController {

    private final LiveFleetService liveFleet;
    private final NearestVehicleService nearestVehicleService;
    private final JobService jobService;
    private final TravelTimeService travelTime;
    private final SiteService siteService;
    private final DurableStore durableStore;

    public VehicleController(LiveFleetService liveFleet,
                             NearestVehicleService nearestVehicleService,
                             JobService jobService,
                             TravelTimeService travelTime,
                             SiteService siteService,
                             DurableStore durableStore) {
        this.liveFleet = liveFleet;
        this.nearestVehicleService = nearestVehicleService;
        this.jobService = jobService;
        this.travelTime = travelTime;
        this.siteService = siteService;
        this.durableStore = durableStore;
    }

    public record VehicleDetail(
            Vehicle vehicle,
            Job currentJob,
            Double etaSeconds,
            String onSiteName,
            List<GeofenceEventRecord> recentGeofenceEvents,
            List<StatusChange> statusHistory) { }

    /** Full fleet snapshot, optionally filtered by status (FR-1.1, FR-1.3). */
    @GetMapping
    public List<Vehicle> list(@RequestParam(name = "status", required = false) String status) {
        if (status == null || status.isBlank()) {
            return liveFleet.allVehicles();
        }
        VehicleStatus filter = VehicleStatus.valueOf(status.trim().toUpperCase());
        return liveFleet.vehiclesWithStatus(filter);
    }

    /** Vehicle detail: current job, ETA, recent status history (FR-1.4). */
    @GetMapping("/{vehicleId}")
    public ResponseEntity<VehicleDetail> detail(@PathVariable String vehicleId) {
        Vehicle vehicle = liveFleet.getVehicle(vehicleId);
        if (vehicle == null) {
            return ResponseEntity.notFound().build();
        }
        Job job = vehicle.currentJobId() == null ? null
                : jobService.get(vehicle.currentJobId()).orElse(null);
        Double eta = null;
        if (job != null) {
            double seconds = travelTime.travelSecondsBetween(
                    vehicle.latitude(), vehicle.longitude(),
                    job.destLatitude(), job.destLongitude());
            eta = Double.isInfinite(seconds) ? null : seconds;
        }
        String onSiteName = vehicle.onSiteId() == null ? null
                : siteService.get(vehicle.onSiteId()).map(Site::name).orElse(vehicle.onSiteId());
        return ResponseEntity.ok(new VehicleDetail(
                vehicle, job, eta, onSiteName,
                durableStore.recentGeofenceEvents(vehicleId, null, 10),
                liveFleet.statusHistory(vehicleId)));
    }

    /**
     * Ranked nearest-available-vehicle shortlist for a job location (FR-2.1–FR-2.3).
     * Ranking is by road-network travel time (FR-2.2).
     */
    @GetMapping("/nearest")
    public List<NearestVehicle> nearest(@RequestParam double lat,
                                        @RequestParam double lon,
                                        @RequestParam(name = "limit", defaultValue = "0") int limit) {
        return nearestVehicleService.nearestAvailable(lat, lon, limit);
    }
}
