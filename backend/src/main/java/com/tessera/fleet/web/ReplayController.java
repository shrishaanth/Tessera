package com.tessera.fleet.web;

import java.time.LocalDate;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tessera.fleet.live.LiveFleetService;
import com.tessera.fleet.replay.TrajectoryService;
import com.tessera.fleet.replay.TrajectoryService.Trajectory;

/**
 * Incident-review trajectory replay (FR-5). Reads the durable position history;
 * request/response, not real-time (SRS §5.3).
 */
@RestController
@RequestMapping("/api/replay")
public class ReplayController {

    private final TrajectoryService trajectoryService;
    private final LiveFleetService liveFleet;

    public ReplayController(TrajectoryService trajectoryService, LiveFleetService liveFleet) {
        this.trajectoryService = trajectoryService;
        this.liveFleet = liveFleet;
    }

    public record ReplayVehicle(String vehicleId, String driverName) { }

    /** Vehicles that can be replayed (those currently in the live fleet). */
    @GetMapping("/vehicles")
    public List<ReplayVehicle> vehicles() {
        return liveFleet.allVehicles().stream()
                .map(v -> new ReplayVehicle(v.vehicleId(), v.driverName()))
                .sorted((a, b) -> a.vehicleId().compareTo(b.vehicleId()))
                .toList();
    }

    /**
     * A vehicle's recorded path. Supply {@code date=YYYY-MM-DD} (UTC day) or an
     * explicit {@code from}/{@code to} epoch-millis range; defaults to the last 24h.
     */
    @GetMapping("/trajectory")
    public Trajectory trajectory(
            @RequestParam String vehicleId,
            @RequestParam(required = false) String date,
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to) {
        if (date != null && !date.isBlank()) {
            return trajectoryService.forDay(vehicleId, LocalDate.parse(date));
        }
        long now = System.currentTimeMillis();
        long f = from != null ? from : now - 86_400_000L;
        long t = to != null ? to : now;
        return trajectoryService.between(vehicleId, f, t);
    }
}
